#!/usr/bin/env python3
import json
import time
import requests
import statistics
from pathlib import Path
from typing import List, Any, Dict, Optional

URL = "http://localhost:8000/clinics/search"
ROOT = Path(__file__).resolve().parent.parent
EMBEDDING_DIM = 384
REQUEST_TIMEOUT = 30.0
REQUEST_RETRIES = 2
TOP_K = 5

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
    """
    Пробует прочитать файл в нескольких кодировках и вернуть список float длины EMBEDDING_DIM.
    Бросает RuntimeError с понятным сообщением при ошибке.
    """
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

def build_payload(q: Dict[str, Any]) -> Dict[str, Any]:
    emb_file = q.get("embedding_file")
    if not emb_file:
        raise RuntimeError("Query must include embedding_file")
    emb = load_embedding_from_file(ROOT / emb_file)
    return {
        "query": q.get("query", ""),
        "embedding": emb,
        "type": q.get("type"),
        "district": q.get("district"),
        "limit": int(q.get("limit", TOP_K)),
        "alpha": float(q.get("alpha", 0.7)),
        "beta": float(q.get("beta", 0.3)),
    }

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

def percentile(sorted_values: List[float], p: float) -> float:
    """
    Простой расчёт перцентиля p в диапазоне 0..100 по отсортированному списку.
    Возвращает значение интерполированно при необходимости.
    """
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

def main():
    queries_path = ROOT / "test_queries.json"
    if not queries_path.exists():
        raise SystemExit(f"Missing test queries file: {queries_path}")
    # читаем JSON с поддержкой BOM/utf-16
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

    for q in queries:
        try:
            payload = build_payload(q)
        except Exception as e:
            print(f"Skipping query due to build error: {e}")
            continue

        try:
            start = time.time()
            r = send_request_with_retries(URL, payload)
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

        top_ids = [item.get("id") for item in results[:TOP_K]]
        expected = q.get("expected", [])
        if expected:
            prec = len(set(top_ids) & set(expected)) / float(TOP_K)
            precisions.append(prec)

        print(f"Query '{q.get('query')}' -> {len(results)} results, latency {latency:.3f}s")

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
        print("Mean Precision@5:", f"{statistics.mean(precisions):.4f}")

if __name__ == "__main__":
    main()
