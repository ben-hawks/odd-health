#!/usr/bin/env python3
"""Offline helper for Even R1 ring protocol work. Never touches Bluetooth.

    r1tool.py frames                     hex frames for a read-only sync session
    r1tool.py decode capture.log         decode frames found in a log -> JSON lines
    r1tool.py compare decoded.jsonl DIR  check decoded records against an Even export
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
from datetime import datetime
from pathlib import Path

from r1 import protocol as p
from r1.decode import decode_frame

HEX_RUN = re.compile(r"(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}[-: ]?){6,}(?![0-9A-Fa-f])")


def cmd_frames(args: argparse.Namespace) -> int:
    now = int(time.time())
    tz_min = args.tz_offset_min
    if tz_min is None:
        tz_min = -(time.altzone if time.localtime().tm_isdst > 0 else time.timezone) // 60
    print(f"# write to {p.WRITE_CHAR_UUID} (write without response), one at a time, ~200 ms apart")
    print(f"# clock: epoch={now} tz_offset_min={tz_min}; regenerate if this is more than a few minutes old")
    for label, frame in p.session_frames(now, tz_min, setup=args.with_setup):
        print(f"{label:22s} {frame.hex()}")
    return 0


def frames_in_log(lines):
    """Yield (line_no, inner_frame_bytes) for every complete frame in a text log."""
    reasm = p.Reassembler()
    pending = False
    for no, line in enumerate(lines, 1):
        runs = HEX_RUN.findall(line)
        if not runs:
            continue
        raw = bytes.fromhex(re.sub(r"[-: ]", "", runs[-1]))
        if raw[0] == 0 and not pending and p.crc32_ring(raw[5:]) != int.from_bytes(raw[1:5], "little"):
            continue  # some other hex blob, not a ring frame
        try:
            inner = reasm.feed(raw)
        except ValueError as err:
            print(f"line {no}: {err}", file=sys.stderr)
            pending = False
            continue
        pending = inner is None
        if inner is not None:
            yield no, inner


def cmd_decode(args: argparse.Namespace) -> int:
    src = sys.stdin if args.log == "-" else open(args.log, errors="replace")
    ack_seq = args.ack_seq
    n = 0
    for no, inner in frames_in_log(src):
        try:
            frame = p.parse_inner(inner)
        except ValueError as err:
            print(f"line {no}: {err}", file=sys.stderr)
            continue
        out = {"line": no, "frame": frame.name, "seq": frame.seq, "data_hex": frame.data.hex()}
        if frame.status in (p.STATUS_PUSH, p.STATUS_ACK):
            out["inner_crc_ok"] = frame.inner_crc_ok
            try:
                decoded = decode_frame(frame)
            except ValueError as err:
                decoded, out["decode_error"] = None, str(err)
            if decoded is not None:
                out["decoded"] = decoded
            if frame.module == p.MODULE_HEALTH and frame.status == p.STATUS_PUSH:
                ack = p.build_frame(1, 0, 0x7E, p.STATUS_SET, ack_seq, p.packet_ack_payload(frame))
                out["packet_ack_hex"] = ack.hex()
                ack_seq += 1
        print(json.dumps(out))
        n += 1
    print(f"{n} frame(s) decoded", file=sys.stderr)
    return 0 if n else 1


def _epoch(iso: str) -> int:
    return int(datetime.fromisoformat(iso).timestamp())


def _load_export(directory: Path) -> dict:
    def rows(name):
        with open(directory / name, newline="") as fh:
            return list(csv.DictReader(fh))

    export = {"vitals": {}, "steps": {}, "calories": {}, "sleep": {}}
    for metric, name in (("heartRate", "heart_rate.csv"), ("spo2", "blood_oxygen.csv"), ("hrv", "hrv.csv")):
        for r in rows(name):
            export["vitals"].setdefault((metric, _epoch(r["timestamp"]), r["ty"]), set()).add(float(r["value"]))
    for r in rows("steps.csv"):
        export["steps"].setdefault(_epoch(r["timestamp"]), set()).add(float(r["value"]))
    for r in rows("calories.csv"):
        export["calories"].setdefault(_epoch(r["timestamp"]), set()).add(float(r["value"]))
    for r in rows("sleep.csv"):
        export["sleep"][(_epoch(r["start_ts"]), _epoch(r["end_ts"]))] = r
    return export


def cmd_compare(args: argparse.Namespace) -> int:
    export = _load_export(Path(args.export_dir))
    tally: dict[str, list[int]] = {}

    def check(kind: str, found, ok: bool) -> None:
        t = tally.setdefault(kind, [0, 0, 0])  # match, mismatch, not in export
        t[2 if found is None else 0 if ok else 1] += 1

    for line in open(args.decoded):
        rec = json.loads(line)
        dec, metric = rec.get("decoded"), rec["frame"].split("/")[1]
        if not dec:
            continue
        if metric in ("heartRate", "spo2", "hrv"):
            for r in dec["records"]:
                for ty in ("min", "max", "avg"):
                    found = export["vitals"].get((metric, r["ts"], ty)) if r["ts"] else None
                    check(metric, found, found is not None and r[ty] in found)
        elif metric == "activity":
            for r in dec["records"]:
                for kind, key in (("steps", "steps"), ("calories", "kcal_total")):
                    found = export[kind].get(r["ts"]) if r["ts"] else None
                    check(kind, found, found is not None and r[key] in found)
        elif metric == "sleep" and dec.get("type") == 1:
            row = export["sleep"].get((dec["start_ts"], dec["end_ts"]))
            ok = row is not None and json.loads(row["stages"]) == dec["stages"] and all(
                int(row[k]) == dec[k] for k in ("total_time", "wake_time", "rem_time", "light_time", "deep_time"))
            check("sleep", row, ok)

    print(f"{'metric':10s} {'match':>7s} {'mismatch':>9s} {'not in export':>14s}")
    for kind, (ok, bad, missing) in tally.items():
        print(f"{kind:10s} {ok:7d} {bad:9d} {missing:14d}")
    if not tally:
        print("no decoded health records found", file=sys.stderr)
        return 1
    return 1 if any(t[1] for t in tally.values()) else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="action", required=True)
    f = sub.add_parser("frames")
    f.add_argument("--with-setup", action="store_true",
                   help="also emit the healthEnable and systemTime writes the official app sends")
    f.add_argument("--tz-offset-min", type=int, help="UTC offset in minutes (default: this machine's)")
    f.set_defaults(fn=cmd_frames)
    d = sub.add_parser("decode")
    d.add_argument("log", help="text log containing hex frames (nRF Connect export, logcat, ...) or -")
    d.add_argument("--ack-seq", type=int, default=100, help="sequence number for generated packetAck frames")
    d.set_defaults(fn=cmd_decode)
    c = sub.add_parser("compare")
    c.add_argument("decoded", help="output of `decode`")
    c.add_argument("export_dir", help="unzipped Even_health_data directory")
    c.set_defaults(fn=cmd_compare)
    args = parser.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
