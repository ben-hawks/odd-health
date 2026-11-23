"""Even Realities R1 ring: binary health channel framing.

Offline only. Nothing in this module talks to a device.

Wire layout (see docs/protocol.md):

    notification / write  = [fragIndex u8][crc32 u32 LE][payload slice]
    reassembled payload   = inner frame
    inner frame           = [0x64][module][0x64][seq u16][status][cmd][subCmd]
                            [innerLen u16][crc16 u16][data...]
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

SERVICE_UUID = "bae80001-4f05-4503-8e65-3af1f7329d1f"
WRITE_CHAR_UUID = "bae80012-4f05-4503-8e65-3af1f7329d1f"
NOTIFY_CHAR_UUID = "bae80013-4f05-4503-8e65-3af1f7329d1f"
STATUS_NOTIFY_CHAR_UUID = "bae80011-4f05-4503-8e65-3af1f7329d1f"

MARKER = 0x64
HEADER_LEN = 12

MODULE_SYSTEM, MODULE_HEALTH, MODULE_SPORT = 1, 2, 3
STATUS_REQ, STATUS_SET, STATUS_PUSH, STATUS_ACK = 0, 1, 2, 3

# Sequence numbers. The Even app and the ring count up from low numbers and
# Odd Health uses 0x4F00-0x4FFF, so a reply can be matched to its sender.
# Frames built here for manual tests use their own range, so a test reply is
# never mistaken for one of Odd Health's own polls (or the other way round).
APP_SEQ_RANGE = range(0x4F00, 0x5000)
TEST_SEQ_BASE = 0x7E00
TEST_SEQ_RANGE = range(TEST_SEQ_BASE, TEST_SEQ_BASE + 0x100)

MODULES = {1: "system", 2: "health", 3: "sport"}
STATUSES = {0: "req", 1: "set", 2: "push", 3: "ack"}
HEALTH_CMDS = {1: "heartRate", 2: "spo2", 3: "temperature", 4: "hrv", 5: "activity", 6: "sleep"}
HEALTH_SUBCMDS = {1: "daily", 2: "point", 3: "measure"}
SYSTEM_SUBCMDS = {
    0x01: "deviceStatus", 0x02: "deviceInfo", 0x03: "wearStatus", 0x04: "userInfo",
    0x05: "systemTime", 0x07: "touchSwitch", 0x08: "pairAuth", 0x09: "otaStart", 0x0A: "advStart",
    0x0B: "getAlgoKeyStatus", 0x0C: "setAlgoKey", 0x0E: "healthSettingsStatus",
    0x0F: "systemSettingsStatus", 0x10: "deviceSerial", 0x11: "nvRecover",
    0x12: "powerControl", 0x13: "pairDelete", 0x7E: "packetAck", 0x7F: "heartbeatPack",
    0x82: "removeRingNotify",
}

# System sub-commands that change pairing, firmware, keys, NVM or power state,
# or the ring's link to the glasses (0x07 switches the glasses touch source,
# 0x0A and 0x82 rewrite or erase the stored glasses addresses).
# build_frame() refuses them; reading health data never needs them.
DANGEROUS_SYSTEM_SUBCMDS = frozenset({0x07, 0x09, 0x0A, 0x0C, 0x11, 0x12, 0x13, 0x82})

# The official app's session-open frame, captured verbatim (seq=1, data=0x01).
PAIR_AUTH_FRAME = bytes.fromhex("00971953f964016401000000080d003f0101")


def _crc32_table() -> tuple[int, ...]:
    table = []
    for i in range(256):
        c = i << 24
        for _ in range(8):
            c = ((c << 1) ^ 0x1EDC6F41) if c & 0x80000000 else (c << 1)
            c &= 0xFFFFFFFF
        table.append(c)
    return tuple(table)


_CRC32 = _crc32_table()


def crc32_ring(data: bytes) -> int:
    """Transport CRC: Castagnoli polynomial, MSB-first, init 0, no xorout."""
    c = 0
    for b in data:
        c = ((c << 8) & 0xFFFFFFFF) ^ _CRC32[((c >> 24) ^ b) & 0xFF]
    return c


def _zero_crc_slot(inner: bytes) -> bytes:
    return inner[:10] + b"\x00\x00" + inner[12:]


def crc16_modbus(inner: bytes) -> int:
    """Inner CRC on ring -> phone frames (crc slot treated as zero)."""
    c = 0xFFFF
    for b in _zero_crc_slot(inner):
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return c


def crc16_ccitt_false(inner: bytes) -> int:
    """Inner CRC we put on phone -> ring frames (crc slot treated as zero).

    The official app's outbound CRC-16 is not identified yet and the ring does
    not appear to check it; this matches what the reference implementation sends.
    """
    c = 0xFFFF
    for b in _zero_crc_slot(inner):
        c ^= b << 8
        for _ in range(8):
            c = ((c << 1) ^ 0x1021) & 0xFFFF if c & 0x8000 else (c << 1) & 0xFFFF
    return c


@dataclass(frozen=True)
class Frame:
    module: int
    seq: int
    status: int
    cmd: int
    sub_cmd: int
    data: bytes
    inner_crc: int
    inner_crc_ok: bool  # MODBUS check; expected True only for ring -> phone

    @property
    def name(self) -> str:
        mod = MODULES.get(self.module, f"mod{self.module}")
        if self.module == MODULE_HEALTH:
            what = f"{HEALTH_CMDS.get(self.cmd, hex(self.cmd))}/{HEALTH_SUBCMDS.get(self.sub_cmd, hex(self.sub_cmd))}"
        elif self.module == MODULE_SYSTEM and self.cmd == 0:
            what = SYSTEM_SUBCMDS.get(self.sub_cmd, hex(self.sub_cmd))
        else:
            what = f"{self.cmd:#x}/{self.sub_cmd:#x}"
        return f"{mod}/{what} {STATUSES.get(self.status, self.status)}"


def parse_inner(inner: bytes) -> Frame:
    if len(inner) < HEADER_LEN:
        raise ValueError(f"inner frame too short ({len(inner)} bytes)")
    if inner[0] != MARKER or inner[2] != MARKER:
        raise ValueError("bad frame markers")
    seq, status, cmd, sub_cmd, length, crc = struct.unpack_from("<HBBBHH", inner, 3)
    if length != len(inner):
        raise ValueError(f"declared length {length} != actual {len(inner)}")
    return Frame(inner[1], seq, status, cmd, sub_cmd, inner[HEADER_LEN:], crc,
                 crc == crc16_modbus(inner))


def build_frame(module: int, cmd: int, sub_cmd: int, status: int, seq: int,
                data: bytes = b"") -> bytes:
    """Build one single-fragment phone -> ring frame."""
    if module == MODULE_SYSTEM and cmd == 0 and sub_cmd in DANGEROUS_SYSTEM_SUBCMDS:
        raise ValueError(f"refusing to build {SYSTEM_SUBCMDS[sub_cmd]}: mutates ring state")
    length = HEADER_LEN + len(data)
    inner = bytearray(struct.pack("<BBBHBBBHH", MARKER, module, MARKER, seq & 0xFFFF,
                                  status, cmd, sub_cmd, length, 0) + data)
    struct.pack_into("<H", inner, 10, crc16_ccitt_false(bytes(inner)))
    return b"\x00" + struct.pack("<I", crc32_ring(bytes(inner))) + bytes(inner)


class Reassembler:
    """Feed raw notifications in arrival order; yields complete inner frames.

    Fragments of one batch share bytes 1..4 (the CRC-32 of the whole inner
    frame) and count down to fragment index 0.
    """

    def __init__(self) -> None:
        self._batch: int | None = None
        self._parts: list[tuple[int, bytes]] = []

    def feed(self, notification: bytes) -> bytes | None:
        if len(notification) < 5:
            raise ValueError("fragment too short")
        index = notification[0]
        batch = struct.unpack_from("<I", notification, 1)[0]
        if batch != self._batch:
            self._batch, self._parts = batch, []
        self._parts.append((index, notification[5:]))
        if index != 0:
            return None
        parts, self._batch, self._parts = self._parts, None, []
        inner = b"".join(p for _, p in sorted(parts, key=lambda x: -x[0]))
        if crc32_ring(inner) != batch:
            raise ValueError(f"batch {batch:#010x}: CRC mismatch (missing fragment?)")
        return inner


# --- read-only session -------------------------------------------------------

def system_time_payload(epoch_sec: int, tz_offset_min: int) -> bytes:
    return struct.pack("<hI", tz_offset_min, epoch_sec)


def health_enable_payload(epoch_sec: int) -> bytes:
    return struct.pack("<IB", epoch_sec, 1) + bytes(19)


def packet_ack_payload(received: Frame) -> bytes:
    """Cursor ack for a received health push: pulls the ring's next batch."""
    return struct.pack("<BBBBHI", received.module, received.cmd, received.sub_cmd, 0,
                       received.seq, 0)


def session_frames(epoch_sec: int, tz_offset_min: int, setup: bool = False) -> list[tuple[str, bytes]]:
    """The health sync sequence, in order, as (label, bytes to write).

    With setup=False every frame is a read. setup=True adds the two writes the
    official app makes each session: enable health tracking and sync the clock.
    """
    out = [("pairAuth", PAIR_AUTH_FRAME)]
    seq = TEST_SEQ_BASE

    def add(label: str, module: int, cmd: int, sub: int, status: int, data: bytes = b"") -> None:
        nonlocal seq
        out.append((label, build_frame(module, cmd, sub, status, seq, data)))
        seq += 1

    add("deviceInfo GET", 1, 0, 0x02, STATUS_REQ)
    if setup:
        add("healthEnable SET", 1, 0, 0x0E, STATUS_SET, health_enable_payload(epoch_sec))
        add("systemTime SET", 1, 0, 0x05, STATUS_PUSH, system_time_payload(epoch_sec, tz_offset_min))
    add("deviceStatus GET", 1, 0, 0x01, STATUS_REQ)
    for cmd in (1, 2, 4, 5, 6, 3):
        add(f"{HEALTH_CMDS[cmd]}/daily GET", 2, cmd, 1, STATUS_REQ)
    return out
