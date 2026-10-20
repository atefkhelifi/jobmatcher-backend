#!/usr/bin/env python3
"""License gate: fails when a dependency is under a license this project cannot ship with.

Usage: python scripts/license-gate.py [--skip-maven] [--skip-npm]

The project is Apache-2.0. A dependency passes when at least one of its licenses is on the ALLOWED list (so "EPL-2.0 or
LGPL-2.1" passes through EPL-2.0). A dependency whose only licenses are strong copyleft (GPL, AGPL, SSPL), source-available
or unknown fails, unless it is in EXCEPTIONS with a written reason. Exceptions are reviewed by hand and listed in docs/THIRD_PARTY.md.

Maven: runs the license-maven-plugin over compile and runtime dependencies of backend/ (test and provided scope are not shipped).
npm:   reads the "license" field of every package in frontend/node_modules (run `npm ci` first), development tools included.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BACKEND = ROOT / "backend"
FRONTEND = ROOT / "frontend"

ALLOWED = re.compile(
    r"apache|\bmit\b|\bbsd|\bisc\b|eclipse (public|distribution)|\bepl\b|\bedl\b|\bcc0\b|public domain|zero-clause|0bsd|"
    r"\bcddl\b|mozilla public|\bmpl\b|python-2\.0|blueoak|unlicense|bouncy castle|\bwtfpl\b|\bzlib\b|cc-by-4\.0|"
    r"the json license",
    re.IGNORECASE,
)

# Strong copyleft, network copyleft and source-available licenses. LGPL alone is not allowed here: it needs a decision.
DENIED = re.compile(r"\bagpl\b|\bsspl\b|commons clause|\bgpl|\blgpl|business source|elastic license|proprietary|unknown", re.IGNORECASE)

# artifact (Maven coordinates or npm name) -> reason it is accepted although no license on it is on the allowed list.
EXCEPTIONS = {
    "com.github.junrar:junrar": "UnRar license: allows extraction, forbids building a RAR compressor. Only reading is done (Tika).",
    "org.codelibs:jhighlight": "Dual CDDL 1.0 / LGPL 2.1; the CDDL option is file-level copyleft and unmodified.",
}


def classify(licenses, artifact):
    """Returns (ok, reason)."""
    if artifact in EXCEPTIONS:
        return True, "exception: " + EXCEPTIONS[artifact]
    if any(ALLOWED.search(l) and not re.search(r"\bgpl|\blgpl", l, re.IGNORECASE) for l in licenses):
        return True, ""
    if not licenses:
        return False, "no license declared"
    bad = [l for l in licenses if DENIED.search(l)]
    return False, "only " + ", ".join(bad or licenses)


def maven():
    out_dir = BACKEND / "target" / "licenses"
    cmd = [
        str(BACKEND / ("mvnw.cmd" if sys.platform == "win32" else "mvnw")), "-q",
        "org.codehaus.mojo:license-maven-plugin:2.4.0:add-third-party",
        f"-Dlicense.outputDirectory={out_dir}", "-Dlicense.thirdPartyFilename=THIRD-PARTY.txt",
        "-Dlicense.excludedScopes=test,provided",
    ]
    subprocess.run(cmd, cwd=BACKEND, check=True)
    failures = []
    count = 0
    for line in (out_dir / "THIRD-PARTY.txt").read_text(encoding="utf-8").splitlines():
        m = re.match(r"^\s+((?:\([^)]*(?:\([^)]*\))?[^)]*\)\s)+)(.*)\((\S+?):(\S+?):(\S+) - ", line)
        if not m:
            continue
        count += 1
        licenses = [x.strip() for x in re.findall(r"\(((?:[^()]|\([^()]*\))*)\)", m.group(1))]
        artifact = f"{m.group(3)}:{m.group(4)}"
        ok, why = classify(licenses, artifact)
        if not ok:
            failures.append(f"maven {artifact}:{m.group(5)}  {why}")
    print(f"Maven: {count} dependencies checked")
    return failures


def npm():
    modules = FRONTEND / "node_modules"
    if not modules.is_dir():
        print("npm: frontend/node_modules not found; run `npm ci` in frontend/ first", file=sys.stderr)
        return ["npm: node_modules missing"]
    failures = []
    count = 0
    for pkg in modules.glob("**/package.json"):
        rel = pkg.relative_to(modules).parts
        # Only top-level packages of each node_modules directory, not files inside them.
        depth = 2 if rel[0].startswith("@") else 1
        if len(rel) != depth + 1:
            continue
        try:
            data = json.loads(pkg.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        name = data.get("name")
        if not name:
            continue
        lic = data.get("license") or data.get("licenses")
        if isinstance(lic, dict):
            lic = lic.get("type")
        if isinstance(lic, list):
            lic = " OR ".join(x.get("type", "") if isinstance(x, dict) else str(x) for x in lic)
        licenses = [p.strip(" ()") for p in re.split(r"\s+(?:OR|AND)\s+|/", str(lic))] if lic else []
        count += 1
        ok, why = classify(licenses, name)
        if not ok:
            failures.append(f"npm {name}@{data.get('version', '?')}  {why}")
    print(f"npm: {count} packages checked")
    return failures


def main():
    failures = []
    if "--skip-maven" not in sys.argv:
        failures += maven()
    if "--skip-npm" not in sys.argv:
        failures += npm()
    for f in failures:
        print("DISALLOWED", f)
    print(f"{len(failures)} dependencies fail the license gate")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
