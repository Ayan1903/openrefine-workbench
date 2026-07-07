#!/usr/bin/env python3
"""
scripts/bench_search.py
Creates bench_ci.csv atomically for CI.
Usage: python scripts/bench_search.py --out-csv bench_ci.csv
"""
from __future__ import annotations
import argparse
import csv
import logging
import os
import sys
import tempfile
from pathlib import Path
from typing import List

EXIT_OK = 0
EXIT_ERROR = 2

def write_csv_atomic(path: Path, rows: List[List[str]]) -> None:
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

def run(out_csv: str, dry_run: bool = False, verbose: bool = False) -> int:
    logging.basicConfig(level=logging.DEBUG if verbose else logging.INFO,
                        format="%(levelname)s: %(message)s")
    logger = logging.getLogger("bench_search")
    out_path = Path(out_csv)
    rows = [["query", "score"], ["example", "0.0"]]
    logger.debug("Prepared rows: %s", rows)
    if dry_run:
        logger.info("Dry run enabled. Would write to: %s", out_path)
        return EXIT_OK
    try:
        write_csv_atomic(out_path, rows)
        logger.info("Created %s (placeholder)", out_path)
        return EXIT_OK
    except Exception as exc:
        logger.error("Error creating %s: %s", out_path, exc, exc_info=verbose)
        return EXIT_ERROR

def parse_args(argv=None):
    p = argparse.ArgumentParser(description="Run a tiny bench and write results to CSV")
    p.add_argument("--out-csv", "-o", default="bench_ci.csv", help="Path to output CSV file")
    p.add_argument("--dry-run", action="store_true", help="Do not write files, only simulate")
    p.add_argument("--verbose", "-v", action="store_true", help="Enable verbose logging")
    return p.parse_args(argv)

def main(argv=None):
    args = parse_args(argv)
    return run(out_csv=args.out_csv, dry_run=args.dry_run, verbose=args.verbose)

if __name__ == "__main__":
    raise SystemExit(main())
