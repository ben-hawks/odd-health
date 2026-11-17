"""Decode R1 health payloads (the `data` region of an inner frame) to dicts."""

from __future__ import annotations

import struct

from .protocol import MODULE_HEALTH, MODULE_SYSTEM, Frame

SLEEP_STAGES = {0: "awake", 1: "rem", 2: "light", 3: "deep"}
CHARGE_STATES = {1: "charging", 2: "not_charging", 3: "full"}


def _ascii(raw: bytes) -> str:
    return raw.split(b"\0")[0].decode("ascii", "replace")


def _day_anchor(tz_min: int, day_base: int) -> bool:
    """True when day_base is a plausible local midnight for tz_min."""
    return -840 <= tz_min <= 840 and day_base != 0 and (day_base + tz_min * 60) % 86400 == 0


def decode_vitals(data: bytes, wide: bool) -> dict:
    """Hourly min/max/avg. wide=True for HRV (u16 ms), False for HR/SpO2 (u8)."""
    if len(data) < 12:
        raise ValueError("vitals payload too short")
    count, tz_min, day_base, current_ts = struct.unpack_from("<BhII", data, 0)
    fmt, off = ("<BHHH", 13) if wide else ("<BBBB", 12)
    stride = struct.calcsize(fmt)
    if off + count * stride > len(data):
        raise ValueError("vitals payload truncated")
    anchored = _day_anchor(tz_min, day_base)
    current = struct.unpack_from("<H" if wide else "<B", data, 11)[0]
    records = []
    for i in range(count):
        hour, avg, mx, mn = struct.unpack_from(fmt, data, off + i * stride)
        records.append({
            "hour": hour, "avg": avg, "max": mx, "min": mn,
            "ts": day_base + hour * 3600 if anchored and hour < 24 else None,
        })
    return {"tz_offset_min": tz_min, "day_base": day_base if anchored else None,
            "current": current,
            "current_ts": current_ts if anchored and day_base <= current_ts < day_base + 86400 else None,
            "records": records}


def decode_activity(data: bytes) -> dict:
    """10-minute buckets of steps and calories."""
    if len(data) < 7:
        raise ValueError("activity payload too short")
    count, tz_min, day_base = struct.unpack_from("<BhI", data, 0)
    if 7 + count * 7 > len(data):
        raise ValueError("activity payload truncated")
    anchored = _day_anchor(tz_min, day_base)
    records = []
    for i in range(count):
        slot, steps, active, total = struct.unpack_from("<BHHH", data, 7 + i * 7)
        records.append({
            "slot": slot, "steps": steps, "kcal_total": total, "kcal_active": active,
            "kcal_resting": total - active,
            "ts": day_base + slot * 600 if anchored and slot < 144 else None,
        })
    return {"tz_offset_min": tz_min, "day_base": day_base if anchored else None, "records": records}


def decode_sleep(data: bytes) -> dict:
    if not data:
        raise ValueError("empty sleep payload")
    if data[0] == 2:
        # Interval-only record; endpoints are relative seconds with an unknown base.
        if len(data) < 20:
            raise ValueError("sleep type-2 payload too short")
        start, end = struct.unpack_from("<II", data, 12)
        return {"type": 2, "rel_start": start, "rel_end": end}
    if data[0] != 1 or len(data) < 32:
        raise ValueError(f"unknown sleep record type {data[0]}")
    (_, efficiency, score, _, _, _, _, _, temp, tz_min, start, end,
     asleep, awake, rem, light, deep, runs, _) = struct.unpack_from("<BBBBBBBBHhIIHHHHHBB", data, 0)
    if 32 + runs * 3 > len(data):
        raise ValueError("sleep payload truncated")
    stages = [dict(zip(("type", "half_minutes"), struct.unpack_from("<BH", data, 32 + i * 3)))
              for i in range(runs)]
    return {"type": 1, "start_ts": start, "end_ts": end, "tz_offset_min": tz_min,
            "efficiency": efficiency, "score": score,
            "skin_temp_c": temp / 10 if temp else None,
            "total_time": asleep, "wake_time": awake, "rem_time": rem,
            "light_time": light, "deep_time": deep, "stages": stages}


def decode_frame(frame: Frame) -> dict | None:
    """Decode a ring -> phone frame's payload, or None if it is not understood."""
    d = frame.data
    if frame.module == MODULE_HEALTH and frame.sub_cmd == 1 and d:
        if frame.cmd in (1, 2, 3):
            return decode_vitals(d, wide=False)
        if frame.cmd == 4:
            return decode_vitals(d, wide=True)
        if frame.cmd == 5:
            return decode_activity(d)
        if frame.cmd == 6:
            return decode_sleep(d)
    if frame.module == MODULE_SYSTEM and frame.cmd == 0 and d:
        if frame.sub_cmd == 0x01:
            out = {"battery_percent": d[0]}
            if len(d) > 1:
                out["charge_state"] = CHARGE_STATES.get(d[1], d[1])
            return out
        if frame.sub_cmd == 0x02:
            out = {"firmware": _ascii(d[:16])}
            if len(d) >= 32:
                out["hardware"] = _ascii(d[16:32])
            return out
    return None
