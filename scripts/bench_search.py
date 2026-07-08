#!/usr/bin/env python3
"""
scripts/bench_search.py

Создаёт bench_ci.csv атомарно для CI.

Примеры:
  python scripts/bench_search.py --out-csv bench_ci.csv
  python scripts/bench_search.py --output bench_ci.csv --verbose
  python scripts/bench_search.py --out-csv bench_ci.csv --dry-run
"""
from __future__ import annotations
import argparse
import csv
import logging
import os
import sys
import tempfile
import time
from pathlib import Path
from typing import List

EXIT_OK = 0
EXIT_ERROR = 2

def write_csv_atomic(path: Path, rows: List[List[str]]) -> None:
    """
    Записывает rows в CSV по пути path атомарно:
    создаёт временный файл в той же директории и затем заменяет целевой файл.
    """
    parent = path.parent if path.parent != Path("") else Path(".")
    parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=".tmp-", dir=str(parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            writer = csv.writer(f)
            writer.writerows(rows)
        os.replace(tmp_path, str(path))
    except Exception:
        try:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)
        except Exception:
            pass
        raise

def prepare_rows_example() -> List[List[str]]:
    """
    Подготовка примерных строк для bench_ci.csv.
    Формат: timestamp,query,latency,results_count,precision
    """
    header = ["timestamp", "query", "latency", "results_count", "precision"]
    now = time.time()
    rows = [
        header,
        [f"{now:.6f}", "example query 1", f"{2.071982:.6f}", "2", "1.0000"],
        [f"{now + 2:.6f}", "example query 2", f"{2.065257:.6f}", "2", "1.0000"],
    ]
    return rows

def run(out_csv: str, dry_run: bool = False, verbose: bool = False) -> int:
    """
    Основная логика: подготовить строки и записать CSV атомарно.
    Возвращает код выхода.
    """
    logging.basicConfig(level=logging.DEBUG if verbose else logging.INFO,
                        format="%(levelname)s: %(message)s")
    logger = logging.getLogger("bench_search")

    if not out_csv:
        logger.error("No output path provided.")
        return EXIT_ERROR

    out_path = Path(out_csv)
    logger.debug("Output path: %s", out_path)

    rows = prepare_rows_example()
    logger.debug("Prepared %d rows; header: %s", len(rows), rows[0] if rows else None)

    if dry_run:
        logger.info("Dry run enabled. Would write %d rows to: %s", len(rows), out_path)
        return EXIT_OK

    try:
        write_csv_atomic(out_path, rows)
        logger.info("Created %s (placeholder data)", out_path)
        return EXIT_OK
    except Exception as exc:
        logger.exception("Error creating %s: %s", out_path, exc)
        return EXIT_ERROR

def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate bench_ci.csv for CI (atomic write).")
    parser.add_argument('--out-csv', dest='out_csv', help='Output CSV file (preferred)')
    # Backwards compatibility: accept --output as an alias for --out-csv
    parser.add_argument('--output', dest='out_csv', help=argparse.SUPPRESS)
    parser.add_argument('--dry-run', action='store_true', help='Do not write file, only simulate')
    parser.add_argument('--verbose', action='store_true', help='Verbose logging')
    return parser.parse_args(argv)

def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    out_csv = args.out_csv or "bench_ci.csv"
    return run(out_csv=out_csv, dry_run=bool(getattr(args, "dry_run", False)), verbose=bool(getattr(args, "verbose", False)))

if __name__ == "__main__":
    raise SystemExit(main())
