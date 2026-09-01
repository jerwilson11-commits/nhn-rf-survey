"""
Reading and analysis layer for RF Test App session files.

Deliberately free of any MCP dependency so it can be exercised and tested on its own; the server
module is a thin wrapper that exposes these functions as tools. That split also means the analysis
can be reused by a report generator or a notebook without dragging a protocol along.

Design rules inherited from the app, and they matter more here than in the handset code because
this layer is what produces the numbers a client reads:

- **A missing value is not a zero.** "Not measured" and "measured badly" are different, and
  conflating them invents failures that never happened. Every aggregate reports how many samples
  it was actually computed from.
- **Percentiles, not just means.** A mean RSSI of -62 dBm hides the 5% of a venue sitting at -85.
  Coverage arguments are won and lost in the tail.
- **The schema evolved.** Files recorded before the throughput work carry 64 columns; later ones
  carry 67. Columns are resolved by name and absence is tolerated, so old sessions stay readable.
"""

from __future__ import annotations

import csv
import math
import os
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from typing import Any, Iterable, Optional

EARTH_RADIUS_M = 6_371_000.0

# Mirrors RssiBucket in the Android source. Defined once there, mirrored here rather than
# reinvented, so the MCP server, the handset plot, the KML and the QGIS styles cannot disagree
# about what "poor coverage" means.
RSSI_BUCKETS = [
    ("EXCELLENT", -55, "≥ -55 dBm"),
    ("GOOD", -65, "-56 to -65 dBm"),
    ("FAIR", -72, "-66 to -72 dBm"),
    ("POOR", -80, "-73 to -80 dBm"),
    ("BAD", -10_000, "< -80 dBm"),
]


def bucket_of(rssi: Optional[int]) -> Optional[str]:
    if rssi is None:
        return None
    for name, floor, _ in RSSI_BUCKETS:
        if rssi >= floor:
            return name
    return "BAD"


# ---------------------------------------------------------------------------
# Records
# ---------------------------------------------------------------------------


@dataclass
class Sample:
    seq: int
    timestamp: Optional[datetime]
    lat: Optional[float]
    lon: Optional[float]
    gps_accuracy_m: Optional[float]
    speed_mps: Optional[float]
    gps_provider: Optional[str]
    wifi_ssid: Optional[str]
    wifi_bssid: Optional[str]
    wifi_rssi: Optional[int]
    wifi_channel: Optional[int]
    wifi_band: Optional[str]
    wifi_width_mhz: Optional[int]
    wifi_tx_mbps: Optional[int]
    wifi_rx_mbps: Optional[int]
    wifi_cochannel: Optional[int]
    wifi_adjacent: Optional[int]
    dl_mbps: Optional[float]
    ul_mbps: Optional[float]
    latency_ms: Optional[float]
    jitter_ms: Optional[float]
    loss_pct: Optional[float]
    note: Optional[str]


@dataclass
class Session:
    name: str
    path: str
    samples: list[Sample] = field(default_factory=list)

    @property
    def started(self) -> Optional[datetime]:
        for s in self.samples:
            if s.timestamp:
                return s.timestamp
        return None

    @property
    def ended(self) -> Optional[datetime]:
        for s in reversed(self.samples):
            if s.timestamp:
                return s.timestamp
        return None

    @property
    def duration_s(self) -> Optional[float]:
        if self.started and self.ended:
            return (self.ended - self.started).total_seconds()
        return None


# ---------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------


def _f(row: dict[str, str], key: str) -> Optional[float]:
    v = row.get(key, "")
    if v is None or v == "":
        return None
    try:
        return float(v)
    except ValueError:
        return None


def _i(row: dict[str, str], key: str) -> Optional[int]:
    v = _f(row, key)
    return int(v) if v is not None else None


def _s(row: dict[str, str], key: str) -> Optional[str]:
    v = row.get(key, "")
    return v if v else None


def _ts(row: dict[str, str]) -> Optional[datetime]:
    v = row.get("timestamp_utc", "")
    if not v:
        return None
    try:
        return datetime.fromisoformat(v.replace("Z", "+00:00")).astimezone(timezone.utc)
    except ValueError:
        return None


def load_session(path: str) -> Session:
    """Parse one session CSV. csv.DictReader handles RFC 4180 quoting, which matters because
    SSIDs may contain commas and the neighbour JSON column always does."""
    name = os.path.splitext(os.path.basename(path))[0]
    session = Session(name=name, path=path)
    with open(path, newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            session.samples.append(
                Sample(
                    seq=_i(row, "seq") or 0,
                    timestamp=_ts(row),
                    lat=_f(row, "lat"),
                    lon=_f(row, "lon"),
                    gps_accuracy_m=_f(row, "gps_accuracy_m"),
                    speed_mps=_f(row, "speed_mps"),
                    gps_provider=_s(row, "gps_provider"),
                    wifi_ssid=_s(row, "wifi_ssid"),
                    wifi_bssid=_s(row, "wifi_bssid"),
                    wifi_rssi=_i(row, "wifi_rssi"),
                    wifi_channel=_i(row, "wifi_channel"),
                    wifi_band=_s(row, "wifi_band"),
                    wifi_width_mhz=_i(row, "wifi_width_mhz"),
                    wifi_tx_mbps=_i(row, "wifi_tx_mbps"),
                    wifi_rx_mbps=_i(row, "wifi_rx_mbps"),
                    wifi_cochannel=_i(row, "wifi_cochannel_count"),
                    wifi_adjacent=_i(row, "wifi_adjacent_count"),
                    dl_mbps=_f(row, "dl_mbps"),
                    ul_mbps=_f(row, "ul_mbps"),
                    latency_ms=_f(row, "latency_ms"),
                    jitter_ms=_f(row, "jitter_ms"),
                    loss_pct=_f(row, "loss_pct"),
                    note=_s(row, "note"),
                )
            )
    return session


def list_session_files(directory: str) -> list[str]:
    if not os.path.isdir(directory):
        return []
    return sorted(
        (os.path.join(directory, f) for f in os.listdir(directory) if f.lower().endswith(".csv")),
        key=os.path.getmtime,
        reverse=True,
    )


# ---------------------------------------------------------------------------
# Statistics
# ---------------------------------------------------------------------------


def percentile(values: list[float], p: float) -> Optional[float]:
    """Linear-interpolation percentile. `p` in 0..100."""
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    k = (len(ordered) - 1) * (p / 100.0)
    lo, hi = math.floor(k), math.ceil(k)
    if lo == hi:
        return ordered[int(k)]
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (k - lo)


def kpi_stats(values: list[float], total_samples: int) -> dict[str, Any]:
    """
    Summary for one KPI.

    `samples` and `missing` are always reported. An aggregate computed from 3 of 400 samples is
    not the same claim as one computed from 400, and a consumer that cannot see the difference
    will treat them identically.
    """
    if not values:
        return {"samples": 0, "missing": total_samples, "note": "no measurements"}
    return {
        "samples": len(values),
        "missing": total_samples - len(values),
        "min": round(min(values), 2),
        "p10": round(percentile(values, 10), 2),
        "median": round(percentile(values, 50), 2),
        "p90": round(percentile(values, 90), 2),
        "max": round(max(values), 2),
        "mean": round(sum(values) / len(values), 2),
    }


def haversine_m(a_lat: float, a_lon: float, b_lat: float, b_lon: float) -> float:
    dlat = math.radians(b_lat - a_lat)
    dlon = math.radians(b_lon - a_lon)
    mid = math.radians((a_lat + b_lat) / 2)
    return EARTH_RADIUS_M * math.hypot(dlon * math.cos(mid), dlat)


def summarise(session: Session) -> dict[str, Any]:
    n = len(session.samples)
    rssi = [float(s.wifi_rssi) for s in session.samples if s.wifi_rssi is not None]
    fixes = [s for s in session.samples if s.lat is not None and s.lon is not None]
    speedtests = [s for s in session.samples if s.dl_mbps is not None or s.ul_mbps is not None]

    bounds = None
    if fixes:
        bounds = {
            "min_lat": min(s.lat for s in fixes),
            "max_lat": max(s.lat for s in fixes),
            "min_lon": min(s.lon for s in fixes),
            "max_lon": max(s.lon for s in fixes),
        }

    out: dict[str, Any] = {
        "session": session.name,
        "samples": n,
        "geotagged_samples": len(fixes),
        "samples_without_fix": n - len(fixes),
        "started_utc": session.started.isoformat() if session.started else None,
        "duration_s": round(session.duration_s, 1) if session.duration_s else None,
        "wifi_rssi_dbm": kpi_stats(rssi, n),
        "bounds": bounds,
        "speedtests": len(speedtests),
    }

    ssids = {s.wifi_ssid for s in session.samples if s.wifi_ssid}
    bssids = {s.wifi_bssid for s in session.samples if s.wifi_bssid}
    out["ssids_observed"] = sorted(ssids)
    out["serving_bssids_observed"] = sorted(bssids)

    accs = [s.gps_accuracy_m for s in session.samples if s.gps_accuracy_m is not None]
    if accs:
        out["gps_accuracy_m"] = kpi_stats(accs, n)

    # Surfaced because the app's own distance figure is unreliable when the receiver stops
    # reporting Doppler velocity; a consumer should know how much of the track had it.
    with_vel = sum(1 for s in session.samples if s.speed_mps is not None)
    if fixes:
        out["fixes_with_velocity_pct"] = round(100.0 * with_vel / len(fixes), 1)
    return out


def coverage_analysis(
    session: Session,
    rssi_threshold_dbm: int = -75,
    min_hole_samples: int = 3,
) -> dict[str, Any]:
    """
    Threshold compliance plus contiguous failing runs.

    A compliance percentage alone is not actionable: 8% failing scattered evenly across a venue is
    a different problem from 8% concentrated in one stairwell. The runs are what someone walks back
    to and investigates.
    """
    measured = [s for s in session.samples if s.wifi_rssi is not None]
    if not measured:
        return {"session": session.name, "note": "no RSSI measurements in this session"}

    failing = [s for s in measured if s.wifi_rssi < rssi_threshold_dbm]

    holes: list[dict[str, Any]] = []
    run: list[Sample] = []

    def close_run() -> None:
        if len(run) >= min_hole_samples:
            worst = min(run, key=lambda s: s.wifi_rssi)
            located = [s for s in run if s.lat is not None]
            hole = {
                "samples": len(run),
                "start_seq": run[0].seq,
                "end_seq": run[-1].seq,
                "worst_rssi_dbm": worst.wifi_rssi,
                "duration_s": (
                    round((run[-1].timestamp - run[0].timestamp).total_seconds(), 1)
                    if run[0].timestamp and run[-1].timestamp
                    else None
                ),
            }
            if located:
                hole["worst_position"] = (
                    {"lat": worst.lat, "lon": worst.lon} if worst.lat is not None else None
                )
                hole["extent_m"] = round(
                    haversine_m(located[0].lat, located[0].lon, located[-1].lat, located[-1].lon), 1
                )
            holes.append(hole)

    for s in measured:
        if s.wifi_rssi < rssi_threshold_dbm:
            run.append(s)
        else:
            close_run()
            run = []
    close_run()

    distribution: dict[str, int] = {name: 0 for name, _, _ in RSSI_BUCKETS}
    for s in measured:
        b = bucket_of(s.wifi_rssi)
        if b:
            distribution[b] += 1

    return {
        "session": session.name,
        "threshold_dbm": rssi_threshold_dbm,
        "measured_samples": len(measured),
        "samples_without_rssi": len(session.samples) - len(measured),
        "failing_samples": len(failing),
        "compliance_pct": round(100.0 * (len(measured) - len(failing)) / len(measured), 1),
        "rssi_dbm": kpi_stats([float(s.wifi_rssi) for s in measured], len(session.samples)),
        "bucket_distribution": distribution,
        "coverage_holes": sorted(holes, key=lambda h: h["worst_rssi_dbm"])[:20],
        "coverage_hole_count": len(holes),
    }


def filter_samples(
    session: Session,
    rssi_min: Optional[int] = None,
    rssi_max: Optional[int] = None,
    band: Optional[str] = None,
    channel: Optional[int] = None,
    bbox: Optional[tuple[float, float, float, float]] = None,
    with_speedtest_only: bool = False,
) -> list[Sample]:
    out: list[Sample] = []
    for s in session.samples:
        if rssi_min is not None and (s.wifi_rssi is None or s.wifi_rssi < rssi_min):
            continue
        if rssi_max is not None and (s.wifi_rssi is None or s.wifi_rssi > rssi_max):
            continue
        if band and (s.wifi_band or "").lower() != band.lower():
            continue
        if channel is not None and s.wifi_channel != channel:
            continue
        if with_speedtest_only and s.dl_mbps is None and s.ul_mbps is None:
            continue
        if bbox:
            min_lat, min_lon, max_lat, max_lon = bbox
            if s.lat is None or s.lon is None:
                continue
            if not (min_lat <= s.lat <= max_lat and min_lon <= s.lon <= max_lon):
                continue
        out.append(s)
    return out


def compare(a: Session, b: Session, rssi_threshold_dbm: int = -75) -> dict[str, Any]:
    """
    Before/after comparison — the actual DAS acceptance question.

    Reports the delta but deliberately does not declare an improvement. Two walks are rarely the
    same route at the same pace, so a median shift can reflect a different path as easily as a
    different network. The sample counts and durations are included so whoever reads it can judge
    whether the sessions are comparable at all.
    """
    ca, cb = coverage_analysis(a, rssi_threshold_dbm), coverage_analysis(b, rssi_threshold_dbm)
    if "note" in ca or "note" in cb:
        return {"a": ca, "b": cb, "note": "one or both sessions carry no RSSI measurements"}

    def d(x: Optional[float], y: Optional[float]) -> Optional[float]:
        return round(y - x, 2) if x is not None and y is not None else None

    return {
        "session_a": a.name,
        "session_b": b.name,
        "threshold_dbm": rssi_threshold_dbm,
        "comparability": {
            "samples_a": ca["measured_samples"],
            "samples_b": cb["measured_samples"],
            "duration_s_a": round(a.duration_s, 1) if a.duration_s else None,
            "duration_s_b": round(b.duration_s, 1) if b.duration_s else None,
            "caveat": (
                "Deltas reflect whatever differed between the two walks, which includes route and "
                "pace, not only the network. Confirm the sessions cover the same ground before "
                "attributing a change to the radio system."
            ),
        },
        "delta": {
            "median_rssi_dbm": d(ca["rssi_dbm"].get("median"), cb["rssi_dbm"].get("median")),
            "p10_rssi_dbm": d(ca["rssi_dbm"].get("p10"), cb["rssi_dbm"].get("p10")),
            "compliance_pct": d(ca["compliance_pct"], cb["compliance_pct"]),
            "coverage_holes": cb["coverage_hole_count"] - ca["coverage_hole_count"],
        },
        "a": ca,
        "b": cb,
    }


def sample_to_dict(s: Sample) -> dict[str, Any]:
    out = asdict(s)
    out["timestamp"] = s.timestamp.isoformat() if s.timestamp else None
    out["rssi_bucket"] = bucket_of(s.wifi_rssi)
    return {k: v for k, v in out.items() if v is not None}
