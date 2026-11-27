"""Tests for check-budgets.py: python3 -m unittest discover -s scripts/perf"""

import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path

_spec = importlib.util.spec_from_file_location("check_budgets", Path(__file__).with_name("check-budgets.py"))
check_budgets = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_budgets)


def snapshot(pss, java=1000, native=5000):
    return {
        "meminfo_kb": {"total_pss": pss, "java_heap": java, "native_heap": native, "graphics": 0},
        "gralloc": {"buffers": 0, "estimate_kb": 0},
        "gfxinfo": {"janky_percent": 1.5, "p50_ms": 8, "p90_ms": 12, "p99_ms": 20},
    }


class CheckBudgetsTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.budgets = self.dir / "budgets.json"
        self.budgets.write_text(
            json.dumps(
                {
                    "tolerance_percent": {"apk": 0, "memory": 5},
                    "margin_percent": {"apk": 2, "memory": 10},
                    "margin_floor": {"apk": 16384, "memory": 1024},
                    "apk": {},
                    "memory": {},
                    "timing": {"gating": False},
                }
            )
        )
        self.results = self.dir / "results.json"
        self.results.write_text(
            json.dumps({"profile": "test", "memory": {"home_idle": {"main": snapshot(40000)}}})
        )
        self.apk = self.dir / "app.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("classes.dex", b"\0" * 1000)
            archive.writestr("classes2.dex", b"\0" * 500)
            archive.writestr("assets/photo.jpg", b"\1" * 3000)

    def run_check(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            code = check_budgets.main(["--budgets", str(self.budgets), *map(str, args)])
        return code, out.getvalue()

    def record(self):
        self.run_check("--record", self.results, "--apk", f"app={self.apk}")
        return json.loads(self.budgets.read_text())

    def test_record_sets_measured_plus_margin(self):
        budgets = self.record()
        pss = budgets["memory"]["test"]["home_idle"]["main"]["total_pss_kb"]
        self.assertEqual(40000, pss["measured"])
        self.assertEqual(44032, pss["budget"])  # +10%, rounded up to 256 KB
        java = budgets["memory"]["test"]["home_idle"]["main"]["java_heap_kb"]
        self.assertEqual(2048, java["budget"])  # the 1024 KB floor beats 10% of 1000
        self.assertNotIn("graphics_kb", budgets["memory"]["test"]["home_idle"]["main"])  # 0: not reported
        self.assertEqual(1500, budgets["apk"]["app"]["dex_bytes"]["measured"])
        self.assertEqual(8, budgets["timing"]["test"]["home_idle"]["main"]["frame_p50_ms"])

    def test_passes_at_the_recorded_values(self):
        self.record()
        code, out = self.run_check(self.results, "--apk", f"app={self.apk}")
        self.assertEqual(0, code, out)
        self.assertIn("OK:", out)

    def test_fails_when_a_budget_is_lowered(self):
        budgets = self.record()
        budgets["memory"]["test"]["home_idle"]["main"]["total_pss_kb"]["budget"] = 30000
        self.budgets.write_text(json.dumps(budgets))
        code, out = self.run_check(self.results)
        self.assertEqual(1, code, out)
        self.assertIn("FAIL", out)
        self.assertIn("total_pss_kb", out)

    def test_within_tolerance_only_warns(self):
        budgets = self.record()
        budgets["memory"]["test"]["home_idle"]["main"]["total_pss_kb"]["budget"] = 39000  # 40000 is +2.6%
        self.budgets.write_text(json.dumps(budgets))
        code, out = self.run_check(self.results)
        self.assertEqual(0, code, out)
        self.assertIn("warn", out)

    def test_apk_size_is_exact(self):
        budgets = self.record()
        budgets["apk"]["app"]["dex_bytes"]["budget"] = 1499
        self.budgets.write_text(json.dumps(budgets))
        code, out = self.run_check("--apk", f"app={self.apk}")
        self.assertEqual(1, code, out)

    def test_missing_measurement_fails(self):
        self.record()
        self.results.write_text(json.dumps({"profile": "test", "memory": {"other": {"main": snapshot(1)}}}))
        code, out = self.run_check(self.results)
        self.assertEqual(1, code, out)
        self.assertIn("MISSING", out)

    def test_unknown_profile_is_an_error(self):
        self.record()
        self.results.write_text(json.dumps({"profile": "nope", "memory": {"home_idle": {"main": snapshot(1)}}}))
        with self.assertRaises(SystemExit) as raised:
            self.run_check(self.results)
        self.assertIn("no memory budgets for profile 'nope'", str(raised.exception))

    def test_recording_a_provisional_profile_drops_its_note(self):
        budgets = json.loads(self.budgets.read_text())
        budgets["memory"]["test"] = {"_note": "copied from another profile", "home_idle": {"main": {}}}
        self.budgets.write_text(json.dumps(budgets))
        recorded = self.record()
        self.assertNotIn("_note", recorded["memory"]["test"])
        self.assertIn("total_pss_kb", recorded["memory"]["test"]["home_idle"]["main"])


if __name__ == "__main__":
    unittest.main()
