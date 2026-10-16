"""Measure selected stack fragments without the old attr.py's fixed phase boundaries.

Example: python attribute.py RUN --frame ScaledBitmapPainter.scale: --frame settingsSearchEntries
Use --start-seconds / --end-seconds to select tasks by their start time after premain.
Attribution is inclusive, sample-weighted full task duration, not an independent CPU measurement.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path


def read_timeline(path):
    frames, tasks, samples = {}, {}, defaultdict(list)
    shutdown = False
    with path.open(encoding="utf-8", errors="replace") as lines:
        for line in lines:
            fields = line.rstrip("\n").split(" ")
            try:
                if fields[0] == "D":
                    frames[int(fields[1])] = " ".join(fields[2:])
                elif fields[0] == "T":
                    tasks[int(fields[1])] = {
                        "start": int(fields[2]), "duration": int(fields[3]),
                        "kind": fields[4], "classes": int(fields[5]),
                    }
                elif fields[0] == "S" and fields[1] != "-1":
                    samples[int(fields[1])].append(
                        [int(value) for value in fields[3].split(",") if value]
                    )
                elif fields[0] == "Q" and "shutdown" in " ".join(fields[1:]):
                    shutdown = True
            except (IndexError, ValueError):
                # The recorder may end with a partial line; expose its shutdown state below.
                continue
    return frames, tasks, samples, shutdown


def quantile(values, q):
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * q
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--frame", action="append", required=True,
                        help="Literal stack fragment; repeat for independent inclusive counts")
    parser.add_argument("--start-seconds", type=float, default=0)
    parser.add_argument("--end-seconds", type=float)
    parser.add_argument("--hitch-ms", type=float, default=25)
    args = parser.parse_args()
    if args.start_seconds < 0 or args.hitch_ms <= 0:
        parser.error("Start must be nonnegative and hitch threshold must be positive")
    if args.end_seconds is not None and args.end_seconds <= args.start_seconds:
        parser.error("End must be after start")
    if any(not fragment for fragment in args.frame):
        parser.error("Frame fragments must not be empty")

    frames, tasks, samples, shutdown = read_timeline(args.run / "timeline.tsv")
    selected = {seq: task for seq, task in tasks.items()
                if task["start"] >= args.start_seconds * 1e6
                and (args.end_seconds is None or task["start"] < args.end_seconds * 1e6)}
    frame_ms = [task["duration"] / 1000 for task in selected.values() if task["kind"] == "F"]
    hitches = {seq: task for seq, task in selected.items() if task["duration"] >= args.hitch_ms * 1000}
    matches = {fragment: {"samples": 0, "weighted_ms": 0.0} for fragment in dict.fromkeys(args.frame)}
    for seq, task in hitches.items():
        stack_samples = samples.get(seq, [])
        if not stack_samples:
            continue
        weight_ms = task["duration"] / 1000 / len(stack_samples)
        for ids in stack_samples:
            stack = [frames.get(frame_id, "?") for frame_id in ids]
            for fragment, metric in matches.items():
                if any(fragment in frame for frame in stack):
                    metric["samples"] += 1
                    metric["weighted_ms"] += weight_ms

    report = {
        "run": args.run.name, "normal_shutdown_marker": shutdown,
        "start_seconds": args.start_seconds, "end_seconds": args.end_seconds,
        "hitch_threshold_ms": args.hitch_ms, "tasks": len(selected),
        "hitches": len(hitches), "unsampled_hitches": sum(not samples.get(seq) for seq in hitches),
        "frames": len(frame_ms), "p50_frame_ms": quantile(frame_ms, 0.5),
        "p95_frame_ms": quantile(frame_ms, 0.95), "worst_frame_ms": max(frame_ms, default=None),
        "frames_ge50ms": sum(value >= 50 for value in frame_ms),
        "frames_ge100ms": sum(value >= 100 for value in frame_ms),
        "frame_matches": matches,
        "attribution": "Inclusive sample-weighted full hitch duration; matching totals can overlap.",
    }
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
