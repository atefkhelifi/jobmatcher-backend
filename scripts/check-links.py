#!/usr/bin/env python3
"""Checks that every link in the learning-resource catalogue still answers.

Usage: python scripts/check-links.py [--strict]

Reads backend/src/main/resources/skills/learning-resources.csv. A link counts as alive when it answers with a 2xx or 3xx status
(HEAD first, then GET, because some sites refuse HEAD). Sites that block automated clients with 401/403/429 are reported as
"unverified", not as broken. With --strict the script exits 1 when a link is broken, so it can run on a schedule in CI.
"""
import csv
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

CATALOGUE = Path(__file__).resolve().parent.parent / "backend" / "src" / "main" / "resources" / "skills" / "learning-resources.csv"
HEADERS = {"User-Agent": "JobMatcherAI-link-check/0.2 (+https://github.com/; open-source link checker)"}
TIMEOUT = 15


def rows():
    with CATALOGUE.open(encoding="utf-8") as f:
        lines = [l for l in f if l.strip() and not l.startswith("#")]
    return list(csv.reader(lines, delimiter=";"))


def status(url):
    last = None
    for method in ("HEAD", "GET"):
        req = urllib.request.Request(url, method=method, headers=HEADERS)
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                return r.status
        except urllib.error.HTTPError as e:
            last = e.code
            if method == "GET" or e.code not in (400, 403, 405, 501):
                return e.code
        except Exception as e:  # DNS, TLS, timeout
            last = type(e).__name__
    return last


def classify(code):
    if isinstance(code, int):
        if 200 <= code < 400:
            return "ok"
        if code in (401, 403, 429):
            return "unverified"
    return "broken"


def main():
    data = rows()
    bad = 0
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda r: status(r[2]), data))
    for r, code in zip(data, results):
        kind = classify(code)
        if kind != "ok":
            print(f"{kind.upper():10} {code}  {r[0]}: {r[2]}")
        if kind == "broken":
            bad += 1
    print(f"{len(data)} links checked, {bad} broken")
    if bad and "--strict" in sys.argv:
        sys.exit(1)


if __name__ == "__main__":
    main()
