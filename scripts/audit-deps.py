#!/usr/bin/env python3
"""Checks every resolved dependency against the public OSV vulnerability database (https://osv.dev).

Maven: runtime/compile dependencies of backend/ (via `mvnw dependency:list`).
npm:   every package in frontend/package-lock.json (production and development are reported separately).

Exit code 1 when a production dependency has a HIGH or CRITICAL advisory, or any advisory when --strict is given.
Needs network access to api.osv.dev. Standard library only.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = "https://api.osv.dev/v1"


def post(path, body):
    req = urllib.request.Request(API + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def get(path):
    with urllib.request.urlopen(API + path, timeout=60) as r:
        return json.load(r)


def maven_packages():
    out = tempfile.NamedTemporaryFile(delete=False, suffix=".txt")
    out.close()
    wrapper = os.path.join(ROOT, "backend", "mvnw.cmd" if os.name == "nt" else "mvnw")
    subprocess.run([wrapper, "-q", "dependency:list", f"-DoutputFile={out.name}", "-DincludeScope=runtime", "-DappendOutput=false"],
                   cwd=os.path.join(ROOT, "backend"), check=True, shell=os.name == "nt")
    pkgs = {}
    with open(out.name, encoding="utf-8") as f:
        for line in f:
            m = re.match(r"\s+([\w.\-]+):([\w.\-]+):[\w\-]+:(?:[\w\-]+:)?([\w.\-+]+):(compile|runtime)", line)
            if m:
                pkgs[(f"{m.group(1)}:{m.group(2)}", m.group(3))] = "prod"
    os.unlink(out.name)
    return [("Maven", name, version, scope) for (name, version), scope in sorted(pkgs.items())]


def npm_packages():
    with open(os.path.join(ROOT, "frontend", "package-lock.json"), encoding="utf-8") as f:
        lock = json.load(f)
    out = []
    for path, meta in lock.get("packages", {}).items():
        if not path or "version" not in meta:
            continue
        name = path.split("node_modules/")[-1]
        out.append(("npm", name, meta["version"], "dev" if meta.get("dev") else "prod"))
    return sorted(set(out))


def severity(vuln):
    sev = (vuln.get("database_specific") or {}).get("severity")
    if sev:
        return sev.upper()
    for s in vuln.get("severity", []):
        score = s.get("score", "")
        m = re.search(r"CVSS:3\.\d/.*", score)
        if m:
            return "UNKNOWN"
    return "UNKNOWN"


def fixed_versions(vuln, name):
    fixes = set()
    for a in vuln.get("affected", []):
        if a.get("package", {}).get("name") == name:
            for r in a.get("ranges", []):
                for e in r.get("events", []):
                    if "fixed" in e:
                        fixes.add(e["fixed"])
    return sorted(fixes)


def main():
    strict = "--strict" in sys.argv
    packages = maven_packages() + npm_packages()
    print(f"Checking {len(packages)} packages against OSV ...")
    findings = []
    for start in range(0, len(packages), 500):
        batch = packages[start:start + 500]
        res = post("/querybatch", {"queries": [{"package": {"name": n, "ecosystem": e}, "version": v} for e, n, v, _ in batch]})
        for pkg, r in zip(batch, res.get("results", [])):
            for v in r.get("vulns", []):
                findings.append((pkg, v["id"]))
    details = {}
    for _, vid in findings:
        if vid not in details:
            details[vid] = get(f"/vulns/{vid}")

    failing = 0
    for (eco, name, version, scope), vid in sorted(findings):
        d = details[vid]
        sev = severity(d)
        fixes = ", ".join(fixed_versions(d, name)) or "no fixed version listed"
        print(f"[{sev:8}] {scope:4} {eco:5} {name}@{version}  {vid}  {d.get('summary', '')[:90]}  (fixed in: {fixes})")
        if scope == "prod" and (sev in ("HIGH", "CRITICAL") or strict):
            failing += 1
    print(f"\n{len(findings)} advisories, {failing} that fail the check.")
    return 1 if failing else 0


if __name__ == "__main__":
    sys.exit(main())
