"""Run with: python -I -m unittest discover -s scripts -p "test_*.py"

Checks that the license gate really fails on licenses the project cannot ship with.
"""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("license_gate", Path(__file__).with_name("license-gate.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class LicenseGateTest(unittest.TestCase):
    def ok(self, licenses, artifact="x:y"):
        return gate.classify(licenses, artifact)[0]

    def test_permissive_licenses_pass(self):
        for lic in ("Apache License, Version 2.0", "MIT", "BSD-3-Clause", "ISC", "EPL-2.0", "Eclipse Distribution License - v 1.0", "0BSD", "CC0-1.0"):
            self.assertTrue(self.ok([lic]), lic)

    def test_strong_and_network_copyleft_fail(self):
        for lic in ("GPL-3.0-only", "GNU General Public License v2", "AGPL-3.0", "SSPL-1.0", "Commons Clause", "Business Source License 1.1"):
            self.assertFalse(self.ok([lic]), lic)

    def test_lgpl_alone_fails_but_a_dual_with_a_permissive_license_passes(self):
        self.assertFalse(self.ok(["LGPL-2.1-only"]))
        self.assertTrue(self.ok(["EPL-2.0", "LGPL-2.1-only"]))
        self.assertTrue(self.ok(["EPL 2.0", "GPL2 w/ CPE"]))

    def test_unknown_or_missing_license_fails(self):
        self.assertFalse(self.ok([]))
        self.assertFalse(self.ok(["Unknown"]))
        self.assertFalse(self.ok(["Some custom license"]))

    def test_a_reviewed_exception_passes_with_its_reason(self):
        ok, why = gate.classify(["UnRar License"], "com.github.junrar:junrar")
        self.assertTrue(ok)
        self.assertIn("exception", why)


if __name__ == "__main__":
    unittest.main()
