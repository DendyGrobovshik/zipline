#!/usr/bin/env python3
"""Plot per-cycle JVM heap and RSS from MemoryTrackingTest.memoryProfileForPlot.

The test prints CSV lines (memprofile,cycle,heapBytes,rssBytes) into the
Gradle test XML reports. This script extracts them and writes a PNG.

Usage:
  ./gradlew :zipline:jvmTest --tests "app.cash.zipline.MemoryTrackingTest.memoryProfileForPlot"
  ./gradlew :zipline:iosSimulatorArm64Test --tests "app.cash.zipline.MemoryTrackingTest.memoryProfileForPlot"
  python3 zipline/plot_memory_profile.py [output.png]
"""

import re
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent

# Each scenario must run in a FRESH JVM (a later test inherits the converged
# plateau of earlier tests in the same JVM). Run them separately and copy the
# reports aside:
#   ./gradlew :zipline:jvmTest --tests "...memoryProfileForPlotBytecode"
#   cp zipline/build/test-results/jvmTest/TEST-app.cash.zipline.MemoryTrackingTest.xml /tmp/mt-bytecode.xml
#   ./gradlew :zipline:jvmTest --tests "...memoryProfileForPlot"
#   cp ... /tmp/mt-compile.xml
JVM_REPORT = ROOT / "build/test-results/jvmTest/TEST-app.cash.zipline.MemoryTrackingTest.xml"
SERIES = [
    ("RSS (compile per cycle)", "memprofile,", "tab:red",
     [Path("/tmp/mt-compile.xml"), JVM_REPORT]),
    ("RSS (cached bytecode)", "memprofile-bc,", "tab:green",
     [Path("/tmp/mt-bytecode.xml"), JVM_REPORT]),
]

IOS_REPORT = (
    ROOT / "build/test-results/iosSimulatorArm64Test"
    / "TEST-iosSimulatorArm64Test.app.cash.zipline.MemoryTrackingTest.xml"
)

LINE = re.compile(r"memprofile,(\d+),(-?\d+),(-?\d+)")
LINE_BC = re.compile(r"memprofile-bc,(\d+),(-?\d+),(-?\d+)")


def load(path, pattern):
    cycles, heap, rss = [], [], []
    if not path.exists():
        return cycles, heap, rss
    for match in pattern.finditer(path.read_text(errors="replace")):
        cycles.append(int(match.group(1)))
        heap.append(int(match.group(2)))
        rss.append(int(match.group(3)))
    return cycles, heap, rss


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "memory_profile.png"
    fig, axes = plt.subplots(2, 1, figsize=(10, 12), sharex=False)

    # JVM: one series per report file (each scenario from its own fresh JVM).
    ax = axes[0]
    heap = []
    cycles = []
    plotted = False
    for label, prefix, color, candidates in SERIES:
        pattern = re.compile(re.escape(prefix) + r"(\d+),(-?\d+),(-?\d+)")
        for candidate in candidates:
            cycles, heap, rss = load(candidate, pattern)
            if cycles:
                ax.plot(cycles, [b / 1048576 for b in rss], label=label, color=color)
                plotted = True
                break
    if not plotted:
        ax.set_title("JVM: no memprofile lines found")
    else:
        ax.set_ylabel("RSS, MB")
        if any(h >= 0 for h in heap):
            ax_heap = ax.twinx()
            ax_heap.plot(
                cycles,
                [h / 1048576 if h >= 0 else float("nan") for h in heap],
                label="JVM heap (used)",
                color="tab:blue",
                alpha=0.7,
            )
            ax_heap.set_ylabel("JVM heap, MB", color="tab:blue")
            ax_heap.tick_params(axis="y", labelcolor="tab:blue")
            # Frame the data with margins instead of starting at zero, so a
            # flat line sits mid-plot rather than hugging a border.
            heap_mb = [h / 1048576 for h in heap if h >= 0]
            lo, hi = min(heap_mb), max(heap_mb)
            pad = max((hi - lo) * 0.5, hi * 0.05)
            ax_heap.set_ylim(max(lo - pad, 0), hi + pad)
    ax.set_title("JVM — create/load/destroy per cycle, no warmup")
    ax.set_xlabel("cycle")
    ax.grid(True, alpha=0.3)
    if plotted:
        ax.legend(loc="center right")

    # iOS simulator: both series may come from the same report (Kotlin/Native
    # has no shared-JVM carryover issue).
    ax = axes[1]
    plotted = False
    for label, pattern, color in [
        ("RSS (compile per cycle)", LINE, "tab:red"),
        ("RSS (cached bytecode)", LINE_BC, "tab:green"),
    ]:
        cycles, _, rss = load(IOS_REPORT, pattern)
        if cycles:
            ax.plot(cycles, [b / 1048576 for b in rss], label=label, color=color)
            plotted = True
    if plotted:
        ax.set_title("iOS simulator — create/load/destroy per cycle, no warmup")
        ax.legend(loc="center right")
    else:
        ax.set_title("iOS simulator: report not found")
    ax.set_xlabel("cycle")
    ax.set_ylabel("RSS, MB")
    ax.grid(True, alpha=0.3)

    fig.tight_layout()
    fig.savefig(out, dpi=150)
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
