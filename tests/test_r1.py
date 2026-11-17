"""Run with: python3 -m unittest discover -s tests"""

import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

from r1 import protocol as p  # noqa: E402
from r1.decode import decode_activity, decode_frame, decode_sleep, decode_vitals  # noqa: E402
from r1tool import frames_in_log  # noqa: E402

# Official-app outbound frames published in not-benny/hermes-g2.
CAPTURED_OUTBOUND = [
    "00971953f964016401000000080d003f0101",
    "00d5faceaf640164280000000e0c00912f",
    "0000045a68640164290000000f0c00015d",
    "000e8624a96401642c0000000418001ede020000000000000000000000",
]


def ring_frame(module, cmd, sub, status, seq, data, chunk=None):
    """Build ring -> phone notifications (MODBUS inner CRC), optionally fragmented."""
    inner = bytearray(struct.pack("<BBBHBBBHH", 0x64, module, 0x64, seq, status, cmd, sub,
                                  12 + len(data), 0) + data)
    struct.pack_into("<H", inner, 10, p.crc16_modbus(bytes(inner)))
    inner = bytes(inner)
    head = struct.pack("<I", p.crc32_ring(inner))
    parts = [inner] if chunk is None else [inner[i:i + chunk] for i in range(0, len(inner), chunk)]
    return [bytes([len(parts) - 1 - i]) + head + part for i, part in enumerate(parts)]


class FramingTest(unittest.TestCase):
    def test_captured_frames_pass_transport_crc(self):
        for hexframe in CAPTURED_OUTBOUND:
            raw = bytes.fromhex(hexframe)
            self.assertEqual(p.crc32_ring(raw[5:]), int.from_bytes(raw[1:5], "little"))
            p.parse_inner(raw[5:])

    def test_build_roundtrip(self):
        raw = p.build_frame(2, 1, 1, p.STATUS_REQ, 7)
        frame = p.parse_inner(p.Reassembler().feed(raw))
        self.assertEqual((frame.module, frame.cmd, frame.sub_cmd, frame.seq, frame.data), (2, 1, 1, 7, b""))
        self.assertEqual(frame.name, "health/heartRate/daily req")

    def test_dangerous_commands_refused(self):
        for sub in p.DANGEROUS_SYSTEM_SUBCMDS:
            with self.assertRaises(ValueError):
                p.build_frame(1, 0, sub, p.STATUS_SET, 1)

    def test_session_starts_with_pair_auth_and_avoids_dangerous_commands(self):
        frames = p.session_frames(1_800_000_000, -360, setup=True)
        self.assertEqual(len(p.session_frames(1_800_000_000, -360)), len(frames) - 2)
        self.assertEqual(frames[0][1], p.PAIR_AUTH_FRAME)
        for _, raw in frames:
            frame = p.parse_inner(raw[5:])
            self.assertNotIn((frame.module, frame.sub_cmd),
                             {(1, s) for s in p.DANGEROUS_SYSTEM_SUBCMDS})

    def test_fragment_reassembly(self):
        data = bytes(range(200))
        notifications = ring_frame(2, 5, 1, p.STATUS_PUSH, 9, data, chunk=60)
        self.assertGreater(len(notifications), 1)
        reasm = p.Reassembler()
        results = [reasm.feed(n) for n in notifications]
        self.assertTrue(all(r is None for r in results[:-1]))
        frame = p.parse_inner(results[-1])
        self.assertEqual(frame.data, data)
        self.assertTrue(frame.inner_crc_ok)

    def test_missing_fragment_detected(self):
        notifications = ring_frame(2, 5, 1, p.STATUS_PUSH, 9, bytes(200), chunk=60)
        reasm = p.Reassembler()
        with self.assertRaises(ValueError):
            for n in notifications[1:]:
                reasm.feed(n)

    def test_packet_ack_payload(self):
        frame = p.parse_inner(ring_frame(2, 2, 1, p.STATUS_PUSH, 0x1234, b"\0")[0][5:])
        self.assertEqual(p.packet_ack_payload(frame), bytes.fromhex("02020100341200000000"))

    def test_frames_in_log_handles_separators_and_noise(self):
        raw = ring_frame(1, 0, 1, p.STATUS_ACK, 3, b"\x57")[0]
        log = [
            "I 12:00:01.123 Connected to AA:BB:CC:DD:EE:FF\n",
            f"I 12:00:02.000 Notification received from {p.NOTIFY_CHAR_UUID}, value: (0x) "
            + "-".join(f"{b:02X}" for b in raw) + "\n",
            "raw=" + raw.hex() + "\n",
        ]
        found = list(frames_in_log(log))
        self.assertEqual([no for no, _ in found], [2])  # line 3 repeats line 2
        self.assertEqual(decode_frame(p.parse_inner(found[0][1])), {"battery_percent": 0x57})

    def test_frames_in_log_dedupes_fragments(self):
        notifications = ring_frame(2, 5, 1, p.STATUS_PUSH, 9, bytes(range(200)), chunk=60)
        log = [f"{tag} value: (0x) " + "-".join(f"{b:02X}" for b in n) + "\n"
               for n in notifications for tag in ("I", "A")]
        found = list(frames_in_log(log))
        self.assertEqual(len(found), 1)
        self.assertEqual(p.parse_inner(found[0][1]).data, bytes(range(200)))

    def test_frames_in_log_reads_app_capture_format(self):
        raw = ring_frame(1, 0, 1, p.STATUS_ACK, 3, b"\x57")[0]
        log = [
            "# 2026-11-17T00:27:05.246-06:00 system/deviceStatus ack #3 — battery 87%\n",
            f"2026-11-17T00:27:05.246-06:00\tN\tbae80013\t{raw.hex()}\n",
        ]
        self.assertEqual([no for no, _ in frames_in_log(log)], [2])

    def test_device_status_and_info(self):
        status = p.parse_inner(ring_frame(1, 0, 1, p.STATUS_ACK, 3, bytes([80, 2, 1, 0, 0, 0, 0]))[0][5:])
        self.assertEqual(decode_frame(status), {"battery_percent": 80, "charge_state": "not_charging"})
        info = p.parse_inner(ring_frame(1, 0, 2, p.STATUS_ACK, 4,
                                        b"2.2.6.0009".ljust(16, b"\0") + b"603MV1.9.3".ljust(16, b"\0"))[0][5:])
        self.assertEqual(decode_frame(info), {"firmware": "2.2.6.0009", "hardware": "603MV1.9.3"})


class DecodeTest(unittest.TestCase):
    # Published payload vectors from not-benny/hermes-g2 (pre-clock-sync, so unanchored).
    def test_heart_rate_vector(self):
        d = decode_vitals(bytes.fromhex("03000000000000da6200006a044958 3b05697a570668715800000000"), wide=False)
        self.assertEqual(d["current"], 106)
        self.assertEqual([(r["hour"], r["avg"], r["max"], r["min"]) for r in d["records"]],
                         [(4, 73, 88, 59), (5, 105, 122, 87), (6, 104, 113, 88)])
        self.assertIsNone(d["records"][0]["ts"])

    def test_hrv_vector(self):
        d = decode_vitals(bytes.fromhex(
            "03000000000000b3620000270004350035003500056200620062000641004100410000000000"), wide=True)
        self.assertEqual(d["current"], 39)
        self.assertEqual([(r["hour"], r["avg"]) for r in d["records"]], [(4, 53), (5, 98), (6, 65)])

    def test_vitals_anchored_timestamps(self):
        tz, base = -360, 1_800_000_000 - (1_800_000_000 - 360 * 60) % 86400
        data = struct.pack("<BhIIB", 1, tz, base, base + 3700, 61) + bytes([1, 60, 70, 50])
        d = decode_vitals(data, wide=False)
        self.assertEqual(d["records"][0]["ts"], base + 3600)
        self.assertEqual(d["current_ts"], base + 3700)

    def test_activity(self):
        tz, base = 0, 1_800_000_000 - 1_800_000_000 % 86400
        data = struct.pack("<BhI", 1, tz, base) + struct.pack("<BHHH", 71, 0, 3, 15)
        rec = decode_activity(data)["records"][0]
        self.assertEqual((rec["ts"], rec["steps"], rec["kcal_total"], rec["kcal_resting"]),
                         (base + 71 * 600, 0, 15, 12))

    def test_sleep_type1(self):
        runs = [(2, 24), (3, 12), (0, 8)]
        data = struct.pack("<BBBBBBBBHhIIHHHHHBB", 1, 90, 82, 0, 10, 0, 60, 30, 352, -360,
                           1000, 1000 + 44 * 30, 36 * 30, 8 * 30, 0, 24 * 30, 12 * 30, len(runs), 0)
        data += b"".join(struct.pack("<BH", *r) for r in runs) + bytes(4)
        d = decode_sleep(data)
        self.assertEqual(d["skin_temp_c"], 35.2)
        self.assertEqual(d["stages"], [{"type": t, "half_minutes": n} for t, n in runs])
        self.assertEqual((d["light_time"], d["deep_time"], d["wake_time"]), (720, 360, 240))

    def test_sleep_type2(self):
        data = bytes([2]) + bytes(11) + struct.pack("<II", 100, 400) + bytes(16)
        self.assertEqual(decode_sleep(data), {"type": 2, "rel_start": 100, "rel_end": 400})


if __name__ == "__main__":
    unittest.main()
