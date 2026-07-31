#!/usr/bin/env python3
"""Aggregate JMeter .jtl results into comparison metrics + response-time charts."""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path


def try_import_matplotlib():
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt

        return plt
    except Exception:
        return None


def parse_jtl(path: Path) -> list[dict]:
    rows: list[dict] = []
    with path.open(encoding="utf-8", newline="") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            try:
                elapsed = float(row.get("elapsed") or 0)
                latency = float(row.get("Latency") or row.get("latency") or 0)
                success = str(row.get("success", "true")).lower() == "true"
                ts = float(row.get("timeStamp") or row.get("timestamp") or 0)
                label = row.get("label") or "ALL"
            except (TypeError, ValueError):
                continue
            # Skip parent transaction double-count? Keep all samples; report both ALL and per-label.
            rows.append(
                {
                    "elapsed": elapsed,
                    "latency": latency,
                    "success": success,
                    "timestamp": ts,
                    "label": label,
                }
            )
    return rows


def summarize(rows: list[dict], label_filter: str | None = None) -> dict:
    selected = [r for r in rows if label_filter is None or r["label"] == label_filter]
    if not selected:
        return {
            "samples": 0,
            "errors": 0,
            "error_rate": 0.0,
            "avg_response_time_ms": 0.0,
            "avg_turnaround_time_ms": 0.0,
            "p95_turnaround_time_ms": 0.0,
            "throughput_rps": 0.0,
            "duration_s": 0.0,
        }

    elapseds = [r["elapsed"] for r in selected]
    latencies = [r["latency"] for r in selected]
    errors = sum(1 for r in selected if not r["success"])
    stamps = [r["timestamp"] for r in selected if r["timestamp"] > 0]
    duration_s = max((max(stamps) - min(stamps)) / 1000.0, 0.001) if stamps else 0.001
    # Approximate end of last sample
    if stamps:
        duration_s = max((max(stamps) + max(elapseds) - min(stamps)) / 1000.0, 0.001)

    p95 = sorted(elapseds)[max(0, math.ceil(0.95 * len(elapseds)) - 1)]
    return {
        "samples": len(selected),
        "errors": errors,
        "error_rate": errors / len(selected),
        # Response time ~= TTFB (JMeter Latency)
        "avg_response_time_ms": statistics.fmean(latencies),
        # Turnaround time ~= end-to-end elapsed (request submit -> full response)
        "avg_turnaround_time_ms": statistics.fmean(elapseds),
        "p95_turnaround_time_ms": p95,
        "throughput_rps": len(selected) / duration_s,
        "duration_s": duration_s,
    }


def plot_response_curve(plt, rows: list[dict], out_path: Path, title: str) -> None:
    # Prefer individual samplers (exclude transaction controller aggregate if present alone)
    points = [r for r in rows if r["timestamp"] > 0]
    if not points:
        return
    t0 = min(r["timestamp"] for r in points)
    xs = [(r["timestamp"] - t0) / 1000.0 for r in points]
    ys = [r["elapsed"] for r in points]

    fig, ax = plt.subplots(figsize=(10, 4.5))
    ax.scatter(xs, ys, s=12, alpha=0.55, color="#1f6feb", label="Sample elapsed")
    # Rolling mean (simple window)
    window = max(5, len(ys) // 20)
    if len(ys) >= window:
        roll_x, roll_y = [], []
        for i in range(window - 1, len(ys)):
            roll_x.append(xs[i])
            roll_y.append(statistics.fmean(ys[i - window + 1 : i + 1]))
        ax.plot(roll_x, roll_y, color="#cf222e", linewidth=2, label=f"Rolling mean (n={window})")
    ax.set_xlabel("Time since start (s)")
    ax.set_ylabel("Turnaround / elapsed (ms)")
    ax.set_title(title)
    ax.grid(True, alpha=0.3)
    ax.legend(loc="upper right")
    fig.tight_layout()
    fig.savefig(out_path, dpi=140)
    plt.close(fig)


def plot_comparison_bars(plt, scenarios: list[dict], out_path: Path) -> None:
    labels = [s["name"] for s in scenarios]
    rt = [s["metrics"]["avg_response_time_ms"] for s in scenarios]
    tat = [s["metrics"]["avg_turnaround_time_ms"] for s in scenarios]
    thr = [s["metrics"]["throughput_rps"] for s in scenarios]

    x = list(range(len(labels)))
    width = 0.35
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8))

    axes[0].bar([i - width / 2 for i in x], rt, width, label="Avg response (Latency)", color="#0969da")
    axes[0].bar([i + width / 2 for i in x], tat, width, label="Avg turnaround (Elapsed)", color="#8250df")
    axes[0].set_xticks(x)
    axes[0].set_xticklabels(labels, rotation=20, ha="right")
    axes[0].set_ylabel("ms")
    axes[0].set_title("Average response vs turnaround")
    axes[0].legend()
    axes[0].grid(True, axis="y", alpha=0.3)

    axes[1].bar(x, thr, color="#1a7f37")
    axes[1].set_xticks(x)
    axes[1].set_xticklabels(labels, rotation=20, ha="right")
    axes[1].set_ylabel("requests / s")
    axes[1].set_title("Average throughput")
    axes[1].grid(True, axis="y", alpha=0.3)

    fig.tight_layout()
    fig.savefig(out_path, dpi=140)
    plt.close(fig)


def markdown_report(scenarios: list[dict], metric_defs: str) -> str:
    lines = [
        "# RepoPilot Performance Comparison",
        "",
        metric_defs,
        "",
        "| Scenario | Users | Samples | Avg Response (ms) | Avg Turnaround (ms) | Throughput (req/s) | Error % |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for s in scenarios:
        m = s["metrics"]
        lines.append(
            f"| {s['name']} | {s['threads']} | {m['samples']} | "
            f"{m['avg_response_time_ms']:.2f} | {m['avg_turnaround_time_ms']:.2f} | "
            f"{m['throughput_rps']:.3f} | {m['error_rate']*100:.2f}% |"
        )

    lines.extend(["", "## 1 VU vs 10 VU deltas", ""])
    by_repo: dict[str, dict[int, dict]] = defaultdict(dict)
    for s in scenarios:
        by_repo[s["repo"]][int(s["threads"])] = s

    for repo, mapping in by_repo.items():
        if 1 not in mapping or 10 not in mapping:
            continue
        a, b = mapping[1]["metrics"], mapping[10]["metrics"]
        lines.append(f"### {repo}")
        lines.append("")
        lines.append(
            f"- Turnaround: {a['avg_turnaround_time_ms']:.1f} ms → {b['avg_turnaround_time_ms']:.1f} ms "
            f"({((b['avg_turnaround_time_ms']/a['avg_turnaround_time_ms'])-1)*100:+.1f}% vs 1 VU)"
            if a["avg_turnaround_time_ms"]
            else "- Turnaround: n/a"
        )
        lines.append(
            f"- Throughput: {a['throughput_rps']:.3f} → {b['throughput_rps']:.3f} req/s "
            f"({(b['throughput_rps']/a['throughput_rps']):.2f}x vs 1 VU)"
            if a["throughput_rps"]
            else "- Throughput: n/a"
        )
        lines.append("")

    lines.extend(
        [
            "## Artifacts",
            "",
            "- Per-scenario JMeter HTML dashboard: `results/<runId>/<scenario>/html-report/index.html`",
            "- Response time curves: `results/<runId>/charts/*-response-curve.png`",
            "- Comparison bars: `results/<runId>/charts/comparison.png`",
            "- Machine-readable summary: `results/<runId>/summary.json`",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", required=True, help="Directory containing scenario subdirs with results.jtl")
    args = parser.parse_args()
    run_dir = Path(args.run_dir)
    if not run_dir.is_dir():
        raise SystemExit(f"Run dir not found: {run_dir}")

    charts_dir = run_dir / "charts"
    charts_dir.mkdir(parents=True, exist_ok=True)
    plt = try_import_matplotlib()

    scenarios: list[dict] = []
    jtl_files = sorted(run_dir.glob("*/results.jtl"))
    for jtl in jtl_files:
        meta_path = jtl.parent / "scenario.json"
        meta = json.loads(meta_path.read_text(encoding="utf-8-sig")) if meta_path.is_file() else {}
        rows = parse_jtl(jtl)
        # Prefer overall samples excluding Transaction Controller synthetic rows named UserJourney for throughput,
        # but include them in named metrics. Compute on non-transaction labels first.
        atomic = [r for r in rows if r["label"] != "UserJourney"]
        metrics = summarize(atomic if atomic else rows)
        scenario = {
            "name": meta.get("name", jtl.parent.name),
            "repo": meta.get("repo", jtl.parent.name),
            "threads": meta.get("threads", 0),
            "repo_id": meta.get("repo_id", ""),
            "metrics": metrics,
            "jtl": str(jtl),
        }
        scenarios.append(scenario)

        if plt:
            plot_response_curve(
                plt,
                atomic if atomic else rows,
                charts_dir / f"{jtl.parent.name}-response-curve.png",
                f"{scenario['name']} — response/turnaround over time",
            )

    if not scenarios:
        raise SystemExit(f"No results.jtl found under {run_dir}")

    scenarios.sort(key=lambda s: (s.get("repo", ""), int(s.get("threads") or 0), s.get("name", "")))

    if plt:
        plot_comparison_bars(plt, scenarios, charts_dir / "comparison.png")

    metric_defs = (
        "Metric definitions (JMeter):\n\n"
        "- **Avg Response Time**: mean of `Latency` (TTFB).\n"
        "- **Avg Turnaround Time**: mean of `elapsed` (submit → full response body).\n"
        "- **Throughput**: successful+failed samples / wall-clock test duration (req/s).\n"
    )
    report = markdown_report(scenarios, metric_defs)
    (run_dir / "COMPARISON.md").write_text(report, encoding="utf-8")
    (run_dir / "summary.json").write_text(json.dumps(scenarios, indent=2, ensure_ascii=False), encoding="utf-8")
    print(report)
    if not plt:
        print(
            "\n[warn] matplotlib not installed; skipped PNG charts. "
            "Install with: python -m pip install matplotlib\n"
            "JMeter HTML dashboards still contain response-time graphs.",
            flush=True,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
