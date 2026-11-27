#!/usr/bin/env python3
"""Compares performance measurements with config/perf-budgets.json.

Measurements come from results files written by scripts/perf/memory-scenarios.sh
(memory and frame timing per scenario and process) and from APKs given with
--apk (file size and uncompressed dex size, read here). Prints one table row per
budgeted metric and exits 1 when any value is over its budget plus the
category's tolerance, or when a budgeted metric was not measured.

    check-budgets.py --apk launcher=app/launcher/build/outputs/apk/release/launcher-release.apk
    check-budgets.py build/perf/results.json

--record rewrites the budgets of everything measured as the measured value plus
the category's margin_percent (how the initial budgets were set). Review the
diff before committing it: a budget only ever goes up with a reason.

Python 3 standard library only.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import zipfile
from pathlib import Path

DEFAULT_BUDGETS = Path(__file__).resolve().parents[2] / "config" / "perf-budgets.json"

# Results key -> budget key for the gated memory values (all in KB).
MEMORY_METRICS = {
    ("meminfo_kb", "total_pss"): "total_pss_kb",
    ("meminfo_kb", "java_heap"): "java_heap_kb",
    ("meminfo_kb", "native_heap"): "native_heap_kb",
    ("meminfo_kb", "graphics"): "graphics_kb",
    ("gralloc", "estimate_kb"): "gralloc_estimate_kb",
}

# Recorded for the timing table, never gated.
TIMING_METRICS = {
    ("gfxinfo", "janky_percent"): "janky_percent",
    ("gfxinfo", "p50_ms"): "frame_p50_ms",
    ("gfxinfo", "p90_ms"): "frame_p90_ms",
    ("gfxinfo", "p99_ms"): "frame_p99_ms",
    ("timing", "start_ms"): "start_ms",
}


class Row:
    def __init__(self, category, scope, metric, measured, budget, tolerance, recorded=None):
        self.category = category
        self.scope = scope
        self.metric = metric
        self.measured = measured
        self.budget = budget
        self.tolerance = tolerance
        self.recorded = recorded

    @property
    def limit(self):
        if self.budget is None:
            return None
        return self.budget * (1 + self.tolerance / 100)

    @property
    def status(self):
        if self.budget is None:
            return "info"
        if self.measured is None:
            return "MISSING"
        if self.measured > self.limit:
            return "FAIL"
        if self.measured > self.budget:
            return "warn"
        return "ok"


def deep_merge(into, update):
    for key, value in update.items():
        if isinstance(value, dict) and isinstance(into.get(key), dict):
            deep_merge(into[key], value)
        else:
            into[key] = value
    return into


def apk_sizes(spec):
    name, sep, path = spec.partition("=")
    if not sep or not name or not path:
        raise SystemExit(f"--apk wants NAME=PATH, got {spec!r}")
    apk = Path(path)
    if not apk.is_file():
        raise SystemExit(f"APK not found: {apk}")
    with zipfile.ZipFile(apk) as archive:
        dex = sum(
            info.file_size
            for info in archive.infolist()
            if info.filename.startswith("classes") and info.filename.endswith(".dex")
        )
    return name, {"apk_bytes": apk.stat().st_size, "dex_bytes": dex}


def lookup(data, path):
    for key in path:
        if not isinstance(data, dict) or key not in data:
            return None
        data = data[key]
    return data


def collect_rows(budgets, results):
    rows = []
    tolerances = budgets.get("tolerance_percent", {})

    for app, measured in sorted(results.get("apk", {}).items()):
        app_budgets = budgets.get("apk", {}).get(app)
        if app_budgets is None:
            raise SystemExit(f"no APK budget for {app!r}; known: {', '.join(budgets.get('apk', {}))}")
        for metric, entry in app_budgets.items():
            rows.append(
                Row("apk", app, metric, measured.get(metric), entry["budget"], tolerances.get("apk", 0), entry.get("measured"))
            )

    memory = results.get("memory")
    if memory:
        profile = results.get("profile")
        profiles = budgets.get("memory", {})
        if profile not in profiles:
            known = ", ".join(name for name in profiles if not name.startswith("_"))
            raise SystemExit(f"no memory budgets for profile {profile!r}; known: {known}")
        for scenario, processes in profiles[profile].items():
            if scenario.startswith("_"):
                continue
            for process, metrics in processes.items():
                snapshot = lookup(memory, [scenario, process])
                for (section, key), metric in MEMORY_METRICS.items():
                    if metric not in metrics:
                        continue
                    value = lookup(snapshot, [section, key]) if snapshot else None
                    entry = metrics[metric]
                    rows.append(
                        Row(
                            "memory",
                            f"{scenario} {process}",
                            metric,
                            value,
                            entry["budget"],
                            tolerances.get("memory", 0),
                            entry.get("measured"),
                        )
                    )
        timing_baseline = budgets.get("timing", {}).get(profile, {})
        for scenario, processes in sorted(memory.items()):
            for process, snapshot in sorted(processes.items()):
                for (section, key), metric in TIMING_METRICS.items():
                    value = lookup(snapshot, [section, key])
                    if value is None:
                        continue
                    recorded = lookup(timing_baseline, [scenario, process, metric])
                    rows.append(Row("timing", f"{scenario} {process}", metric, value, None, 0, recorded))
    return rows


def number(value):
    if value is None:
        return "-"
    if isinstance(value, float) and not value.is_integer():
        return f"{value:,.2f}"
    return f"{int(value):,}"


def print_table(rows, out):
    header = ("category", "scenario / app", "metric", "measured", "budget", "limit", "use", "status", "when set")
    table = [header]
    for row in rows:
        use = "-"
        if row.budget and row.measured is not None:
            use = f"{100 * row.measured / row.budget:.0f}%"
        table.append(
            (
                row.category,
                row.scope,
                row.metric,
                number(row.measured),
                number(row.budget),
                number(row.limit if row.limit is None else math.floor(row.limit)),
                use,
                row.status,
                number(row.recorded),
            )
        )
    widths = [max(len(line[i]) for line in table) for i in range(len(header))]
    numeric = {3, 4, 5, 6, 8}
    for index, line in enumerate(table):
        cells = [cell.rjust(widths[i]) if i in numeric and index else cell.ljust(widths[i]) for i, cell in enumerate(line)]
        print("  ".join(cells).rstrip(), file=out)
        if index == 0:
            print("  ".join("-" * width for width in widths), file=out)


def record(budgets, results, path):
    margins = budgets.get("margin_percent", {})
    floors = budgets.get("margin_floor", {})

    def ceiling(value, category, step):
        # The margin is a percentage, but never less than the floor: small values (a 1.5 MB
        # Java heap) move by more than 10% between runs.
        headroom = max(value * margins.get(category, 0) / 100, floors.get(category, 0))
        return int(math.ceil((value + headroom) / step) * step)

    for app, measured in results.get("apk", {}).items():
        entries = budgets.setdefault("apk", {}).setdefault(app, {})
        for metric in ("apk_bytes", "dex_bytes"):
            value = measured[metric]
            entries[metric] = {"budget": ceiling(value, "apk", 1024), "measured": value}

    memory = results.get("memory")
    if memory:
        profile = results["profile"]
        target = budgets.setdefault("memory", {}).setdefault(profile, {})
        # Notes such as "provisional, copied from another profile" no longer hold once measured.
        for key in [key for key in target if key.startswith("_")]:
            del target[key]
        timing = budgets.setdefault("timing", {}).setdefault(profile, {})
        for scenario, processes in memory.items():
            for process, snapshot in processes.items():
                entries = target.setdefault(scenario, {}).setdefault(process, {})
                for (section, key), metric in MEMORY_METRICS.items():
                    value = lookup(snapshot, [section, key])
                    # 0 means "not reported here": emulators have no memtrack (Graphics) and
                    # gfxinfo lists a process's graphic buffers only from API 34.
                    if not value:
                        entries.pop(metric, None)
                        continue
                    entries[metric] = {
                        "budget": ceiling(value, "memory", 256),
                        "measured": value,
                    }
                for (section, key), metric in TIMING_METRICS.items():
                    value = lookup(snapshot, [section, key])
                    if value is not None:
                        timing.setdefault(scenario, {}).setdefault(process, {})[metric] = value
    path.write_text(json.dumps(budgets, indent=2) + "\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("results", nargs="*", type=Path, help="results JSON files (merged in order)")
    parser.add_argument("--apk", action="append", default=[], metavar="NAME=PATH", help="measure an APK, e.g. launcher=app.apk")
    parser.add_argument("--budgets", type=Path, default=DEFAULT_BUDGETS, help="budgets file (default: %(default)s)")
    parser.add_argument("--record", action="store_true", help="write the measured values plus margin as the new budgets")
    args = parser.parse_args(argv)

    results = {}
    for path in args.results:
        try:
            deep_merge(results, json.loads(path.read_text()))
        except (OSError, json.JSONDecodeError) as error:
            raise SystemExit(f"cannot read results {path}: {error}") from error
    for spec in args.apk:
        name, sizes = apk_sizes(spec)
        results.setdefault("apk", {})[name] = sizes
    if not results.get("apk") and not results.get("memory"):
        parser.error("nothing to check: give a results file or --apk")

    budgets = json.loads(args.budgets.read_text())
    if args.record:
        record(budgets, results, args.budgets)
        print(f"recorded budgets in {args.budgets}")
        return 0

    rows = collect_rows(budgets, results)
    print_table(rows, sys.stdout)
    failed = [row for row in rows if row.status in ("FAIL", "MISSING")]
    warned = [row for row in rows if row.status == "warn"]
    gated = [row for row in rows if row.budget is not None]
    print()
    if failed:
        print(f"FAILED: {len(failed)} of {len(gated)} budgets exceeded or not measured:")
        for row in failed:
            print(f"  {row.category} {row.scope} {row.metric}: {number(row.measured)} (budget {number(row.budget)})")
        print("If the increase is intended, raise the budget in config/perf-budgets.json with the reason in the commit.")
        return 1
    note = f", {len(warned)} over budget but within tolerance" if warned else ""
    print(f"OK: {len(gated)} budgets met{note}. Timing rows are recorded only, never gated (see the budgets file).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
