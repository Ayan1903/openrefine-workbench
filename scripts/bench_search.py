#!/usr/bin/env python3
import argparse
import csv
import json
import time
import requests
import statistics
from pathlib import Path
from typing import List, Any, Dict, Optional

# --- Конфигурация по умолчанию ---
DEFAULT_URL = "http://localhost:8000/clinics/search"
ROOT = Path(__file__).resolve().parent.parent
EMBEDDING_DIM = 384
REQUEST_TIMEOUT = 30.0
REQUEST_RETRIES = 2
DEFAULT_TOPK = 5

# --- Утилиты кодировок и загрузки ---
def try_decode_bytes(raw: bytes, encodings=("utf-8-sig", "utf-8", "utf-16", "utf-16-le", "utf-16-be", "latin-1")) -> Optional[str]:
    for enc in encodings:
        try:
            s = raw.decode(enc)
            if s and s.strip():
                return s
        except Exception:
            continue
    return None

def load_embedding_from_file(path: Path) -> List[float]:
    if not path.exists():
        raise RuntimeError(f"Embedding file not found: {path}")
    raw = path.read_bytes()
    s = try_decode_bytes(raw)
    if s is None:
        raise RuntimeError(f"Cannot decode embedding file {path} (tried utf-8/utf-16/latin-1)")
    try:
        emb = json.loads(s)
    except Exception as e:
        raise RuntimeError(f"Embedding file {path} is not valid JSON: {e}")
    if not isinstance(emb, list):
        raise RuntimeError("Embedding file does not contain a JSON array")
    if len(emb) != EMBEDDING_DIM:
        raise RuntimeError(f"Embedding length is {len(emb)}, expected {EMBEDDING_DIM}")
    try:
        return [float(x) for x in emb]
    except Exception as e:
        raise RuntimeError(f"Cannot convert embedding elements to float: {e}")

# --- Генерация эмбеддинга через HTTP сервис ---
def generate_embedding_live(query: str, embedder_url: str, timeout: float = 10.0) -> List[float]:
    """
    Ожидает POST {"text": "..."} и ответ JSON {"embedding": [...]} или просто [...]
    Возвращает список float длины EMBEDDING_DIM или бросает RuntimeError.
    """
    try:
        r = requests.post(embedder_url, json={"text": query}, timeout=timeout)
    except Exception as e:
        raise RuntimeError(f"Failed to call embedder at {embedder_url}: {e}")
    if r.status_code != 200:
        raise RuntimeError(f"Embedder returned status {r.status_code}: {r.text[:400]}")
    try:
        data = r.json()
    except Exception as e:
        raise RuntimeError(f"Embedder response is not valid JSON: {e}")
    emb = None
    if isinstance(data, dict) and "embedding" in data:
        emb = data["embedding"]
    elif isinstance(data, list):
        emb = data
    else:
        raise RuntimeError("Embedder returned unexpected JSON structure")
    if not isinstance(emb, list):
        raise RuntimeError("Embedder returned embedding in unexpected format")
    if len(emb) != EMBEDDING_DIM:
        raise RuntimeError(f"Embedder returned embedding length {len(emb)}, expected {EMBEDDING_DIM}")
    try:
        return [float(x) for x in emb]
    except Exception as e:
        raise RuntimeError(f"Cannot convert embedder output to floats: {e}")

# --- Заглушка генерации эмбеддинга (на случай отсутствия сервиса) ---
def generate_embedding_stub(query: str) -> List[float]:
    """
    Заглушка: возвращает нулевой вектор нужной длины.
    Замените на вызов реального генератора эмбеддингов при необходимости.
    """
    return [0.0] * EMBEDDING_DIM

# --- Построение полезной нагрузки ---
def build_payload(q: Dict[str, Any], use_live_embeddings: bool, embedder_url: Optional[str]) -> Dict[str, Any]:
    if use_live_embeddings:
        if embedder_url:
            emb = generate_embedding_live(q.get("query", ""), embedder_url)
        else:
            # если включено live, но URL не передан — используем заглушку, но предупреждаем
            print("Warning: --use-live-embeddings set but --embedder-url not provided; using stub embedding")
            emb = generate_embedding_stub(q.get("query", ""))
    else:
        emb_file = q.get("embedding_file")
        if not emb_file:
            raise RuntimeError("Query must include embedding_file when not using live embeddings")
        emb = load_embedding_from_file(ROOT / emb_file)
    if len(emb) != EMBEDDING_DIM:
        raise RuntimeError(f"Embedding length is {len(emb)}, expected {EMBEDDING_DIM}")
    return {
        "query": q.get("query", ""),
        "embedding": emb,
        "type": q.get("type"),
        "district": q.get("district"),
        "limit": int(q.get("limit", DEFAULT_TOPK)),
        "alpha": float(q.get("alpha", 0.7)),
        "beta": float(q.get("beta", 0.3)),
    }

# --- HTTP с ретраями ---
def send_request_with_retries(url: str, payload: Dict[str, Any], timeout: float = REQUEST_TIMEOUT, retries: int = REQUEST_RETRIES) -> requests.Response:
    last_exc = None
    for attempt in range(1, retries + 2):
        try:
            r = requests.post(url, json=payload, timeout=timeout)
            return r
        except Exception as e:
            last_exc = e
            if attempt <= retries:
                time.sleep(0.2 * attempt)
            else:
                raise RuntimeError(f"Request failed after {attempt} attempts: {e}") from e
    raise RuntimeError(f"Request failed: {last_exc}")

# --- Перцентиль и сводка ---
def percentile(sorted_values: List[float], p: float) -> float:
    if not sorted_values:
        return 0.0
    k = (len(sorted_values) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_values) - 1)
    if f == c:
        return sorted_values[int(k)]
    d0 = sorted_values[f] * (c - k)
    d1 = sorted_values[c] * (k - f)
    return d0 + d1

def summarize_latencies(latencies: List[float]) -> Dict[str, float]:
    if not latencies:
        return {"count": 0, "p50": 0.0, "p95": 0.0, "p99": 0.0, "mean": 0.0}
    s = sorted(latencies)
    return {
        "count": len(latencies),
        "p50": percentile(s, 50.0),
        "p95": percentile(s, 95.0),
        "p99": percentile(s, 99.0),
        "mean": statistics.mean(latencies),
    }

# --- Основная логика ---
def run_bench(url: str, queries_path: Path, topk: int, retries: int, use_live_embeddings: bool, out_csv: Optional[Path], embedder_url: Optional[str]):
    if not queries_path.exists():
        raise SystemExit(f"Missing test queries file: {queries_path}")
    raw = queries_path.read_bytes()
    s = try_decode_bytes(raw)
    if s is None:
        raise SystemExit("Cannot decode test_queries.json (tried utf-8/utf-16/latin-1)")
    try:
        queries = json.loads(s)
    except Exception as e:
        raise SystemExit(f"test_queries.json is not valid JSON: {e}")

    latencies: List[float] = []
    precisions: List[float] = []

    # Prepare CSV writer if requested
    csv_file = None
    csv_writer = None
    if out_csv:
        # ensure parent exists
        out_csv.parent.mkdir(parents=True, exist_ok=True)
        file_existed = out_csv.exists()
        csv_file = open(out_csv, "a", newline="", encoding="utf-8")
        csv_writer = csv.writer(csv_file)
        # header if file was empty
        if not file_existed or out_csv.stat().st_size == 0:
            csv_writer.writerow(["timestamp", "query", "latency", "results_count", "precision"])

    for q in queries:
        try:
            payload = build_payload(q, use_live_embeddings, embedder_url)
        except Exception as e:
            print(f"Skipping query due to build error: {e}")
            continue

        try:
            start = time.time()
            r = send_request_with_retries(url, payload, retries=retries)
            latency = time.time() - start
            latencies.append(latency)
        except Exception as e:
            print(f"Request error for query '{q.get('query','')}': {e}")
            continue

        if r.status_code != 200:
            print(f"Request failed: {r.status_code} {r.text[:400]}")
            continue

        try:
            results = r.json().get("results", [])
        except Exception:
            print("Response is not valid JSON")
            results = []

        top_ids = [item.get("id") for item in results[:topk]]
        expected = q.get("expected", [])
        prec = None
        if expected:
            prec = len(set(top_ids) & set(expected)) / float(topk)
            precisions.append(prec)

        print(f"Query '{q.get('query')}' -> {len(results)} results, latency {latency:.3f}s")

        if csv_writer:
            csv_writer.writerow([time.time(), q.get("query",""), f"{latency:.6f}", len(results), "" if prec is None else f"{prec:.4f}"])

    if csv_file:
        csv_file.close()

    summary = summarize_latencies(latencies)
    if summary["count"] > 0:
        print("Queries:", summary["count"])
        print("Mean latency:", f"{summary['mean']:.4f}s")
        print("P50 latency:", f"{summary['p50']:.4f}s")
        print("P95 latency:", f"{summary['p95']:.4f}s")
        print("P99 latency:", f"{summary['p99']:.4f}s")
    else:
        print("No successful requests recorded.")

    if precisions:
        print("Mean Precision@{}: {:.4f}".format(topk, statistics.mean(precisions)))

# --- CLI ---
def main():
    parser = argparse.ArgumentParser(description="Benchmark semantic search (bench_search)")
    parser.add_argument("--url", default=DEFAULT_URL, help="Search API URL")
    parser.add_argument("--queries", default=str(ROOT / "test_queries.json"), help="Path to test queries JSON")
    parser.add_argument("--topk", type=int, default=DEFAULT_TOPK, help="Top-K to evaluate")
    parser.add_argument("--retries", type=int, default=REQUEST_RETRIES, help="Number of request retries")
    parser.add_argument("--use-live-embeddings", action="store_true", help="Generate embeddings on the fly instead of reading files")
    parser.add_argument("--embedder-url", default=None, help="URL of embedding service (used with --use-live-embeddings)")
    parser.add_argument("--out-csv", default=None, help="Append results to CSV file (path)")
    args = parser.parse_args()

    run_bench(
        url=args.url,
        queries_path=Path(args.queries),
        topk=args.topk,
        retries=args.retries,
        use_live_embeddings=args.use_live_embeddings,
        out_csv=Path(args.out_csv) if args.out_csv else None,
        embedder_url=args.embedder_url,
    )

if __name__ == "__main__":
    main()
