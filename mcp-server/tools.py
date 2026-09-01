"""
Tool definitions, registered onto an MCPServer.

Split out from the entry points so the stdio server and the HTTP server share **one** definition
of every tool. Two copies would drift, and a tool that behaves differently over HTTP than over
stdio is a bug that only shows up in whichever transport is tested less.

This split is also the evidence for the stateless claim: the transports differ in how they are
constructed and authenticated, and in nothing else. No tool here holds state between calls — each
one reads from disk and returns.
"""

from __future__ import annotations

import json
import os
from typing import Optional

from mcp.server.mcpserver import MCPServer

import session_store as store

SERVER_INSTRUCTIONS = (
    "Session data comes from an Android field-measurement app. Call list_sessions first to see "
    "what exists. Aggregates always report how many samples they were computed from — a figure "
    "derived from 3 samples is not the same claim as one from 400, and a missing measurement is "
    "never treated as a zero. When comparing two sessions, check the comparability block before "
    "attributing any delta to the network rather than to a different route or walking pace."
)

SERVER_DESCRIPTION = (
    "RF field survey session analysis — coverage, thresholds, throughput, comparison."
)


def register(mcp: MCPServer, sessions_dir: str) -> None:
    """Attach all tools to `mcp`, reading sessions from `sessions_dir`."""

    def _resolve(name: str) -> Optional[str]:
        if os.path.isfile(name):
            return name
        for path in store.list_session_files(sessions_dir):
            base = os.path.basename(path)
            if name in (base, os.path.splitext(base)[0]):
                return path
        return None

    def _err(message: str) -> str:
        """Errors are returned as data, not raised.

        An agent can act on "session not found, here are the five that exist"; it cannot act on a
        stack trace, and a tool that throws tends to end the conversation rather than redirect it.
        """
        available = [
            os.path.splitext(os.path.basename(p))[0]
            for p in store.list_session_files(sessions_dir)
        ]
        return json.dumps(
            {"error": message, "sessions_directory": sessions_dir, "available_sessions": available},
            indent=2,
        )

    @mcp.tool()
    def list_sessions() -> str:
        """List recorded measurement sessions with their basic metadata.

        Returns session names, start time, sample counts and geographic bounds. Start here to find
        out what data exists before querying it.
        """
        files = store.list_session_files(sessions_dir)
        if not files:
            return _err("no session CSV files found")
        out = []
        for path in files:
            try:
                s = store.load_session(path)
                out.append(
                    {
                        "session": s.name,
                        "samples": len(s.samples),
                        "started_utc": s.started.isoformat() if s.started else None,
                        "duration_s": round(s.duration_s, 1) if s.duration_s else None,
                        "geotagged_samples": sum(1 for x in s.samples if x.lat is not None),
                    }
                )
            except Exception as exc:  # a malformed file must not hide the readable ones
                out.append({"session": os.path.basename(path), "error": str(exc)})
        return json.dumps({"sessions_directory": sessions_dir, "sessions": out}, indent=2)

    @mcp.tool()
    def get_session_summary(session: str) -> str:
        """Full statistical summary of one session.

        Includes RSSI and cellular RSRP min/p10/median/p90/max, sample counts, how many samples
        lacked a GPS fix, the SSIDs and serving BSSIDs observed, RATs and cellular bands seen,
        geographic bounds, how much of the track carried GPS velocity, and — for indoor walks — the
        floorplans used, how many samples were positioned on them, and the waypoint labels
        recorded. Every aggregate reports how many samples it was computed from — an average over
        3 samples is not the same claim as one over 400.

        Args:
            session: session name, with or without the .csv extension.
        """
        path = _resolve(session)
        if not path:
            return _err(f"session '{session}' not found")
        return json.dumps(store.summarise(store.load_session(path)), indent=2)

    @mcp.tool()
    def analyze_coverage(
        session: str,
        rssi_threshold_dbm: Optional[int] = None,
        min_hole_samples: int = 3,
        kpi: str = "auto",
    ) -> str:
        """Threshold compliance and coverage-hole detection for one session.

        Returns the percentage of samples meeting the threshold, the RSSI distribution across the
        five standard buckets, and the contiguous failing runs — each with its worst reading,
        position, duration and physical extent.

        The runs matter more than the percentage: 8% failing scattered evenly across a venue is a
        different problem from 8% concentrated in one stairwell, and only the second one tells an
        engineer where to walk back to.

        Coverage holes carry a position. Outdoors that is a lat/lon; indoors, where there is no
        GPS, it is a floorplan coordinate plus the nearest waypoint label the operator recorded.

        Args:
            session: session name.
            rssi_threshold_dbm: pass/fail limit. Leave unset to use the default for the KPI:
                -75 dBm for Wi-Fi RSSI, -105 dBm for cellular RSRP. These differ by an order of
                magnitude, so applying a Wi-Fi threshold to cellular data would fail essentially
                every sample ever recorded.
            min_hole_samples: consecutive failing samples before a run counts as a hole. Raise it
                to filter out momentary dips.
            kpi: "auto" (default) uses cellular RSRP where the session has it and Wi-Fi RSSI
                otherwise. Force one with "wifi_rssi" or "cell_rsrp".
        """
        path = _resolve(session)
        if not path:
            return _err(f"session '{session}' not found")
        s = store.load_session(path)
        return json.dumps(
            store.coverage_analysis(s, rssi_threshold_dbm, min_hole_samples, kpi), indent=2
        )

    @mcp.tool()
    def query_samples(
        session: str,
        rssi_min: Optional[int] = None,
        rssi_max: Optional[int] = None,
        band: Optional[str] = None,
        channel: Optional[int] = None,
        bbox: Optional[str] = None,
        with_speedtest_only: bool = False,
        limit: int = 50,
    ) -> str:
        """Filter the samples in a session and return the matching rows.

        Use this to answer specific questions — the samples below -80 dBm, everything on channel
        161, the rows inside a bounding box, or just the rows carrying throughput results.

        Args:
            session: session name.
            rssi_min: keep samples at or above this RSSI. Samples with no RSSI are excluded, since
                a missing measurement cannot satisfy a threshold either way.
            rssi_max: keep samples at or below this RSSI.
            band: "2.4 GHz", "5 GHz" or "6 GHz".
            channel: exact channel number.
            bbox: "min_lat,min_lon,max_lat,max_lon".
            with_speedtest_only: keep only rows carrying a throughput result.
            limit: maximum rows returned. The true match count is always reported, so truncation
                is visible rather than silent.
        """
        path = _resolve(session)
        if not path:
            return _err(f"session '{session}' not found")
        s = store.load_session(path)

        box = None
        if bbox:
            try:
                parts = [float(x) for x in bbox.split(",")]
                if len(parts) != 4:
                    raise ValueError
                box = (parts[0], parts[1], parts[2], parts[3])
            except ValueError:
                return _err("bbox must be 'min_lat,min_lon,max_lat,max_lon'")

        matched = store.filter_samples(
            s,
            rssi_min=rssi_min,
            rssi_max=rssi_max,
            band=band,
            channel=channel,
            bbox=box,
            with_speedtest_only=with_speedtest_only,
        )
        rssi = [float(x.wifi_rssi) for x in matched if x.wifi_rssi is not None]
        return json.dumps(
            {
                "session": s.name,
                "total_samples": len(s.samples),
                "matched": len(matched),
                "returned": min(len(matched), limit),
                "truncated": len(matched) > limit,
                "rssi_dbm_of_matched": store.kpi_stats(rssi, len(matched)),
                "samples": [store.sample_to_dict(x) for x in matched[:limit]],
            },
            indent=2,
        )

    @mcp.tool()
    def compare_sessions(
        session_a: str, session_b: str, rssi_threshold_dbm: Optional[int] = None
    ) -> str:
        """Compare two sessions — the before/after question in DAS and Private 5G acceptance.

        Returns each session's coverage analysis plus the deltas in median RSSI, 10th-percentile
        RSSI, threshold compliance and coverage-hole count.

        It reports the change; it does not declare an improvement. Two walks are rarely the same
        route at the same pace, so a median shift can reflect a different path as easily as a
        different network. Sample counts and durations are included so the reader can judge
        comparability.

        Args:
            session_a: baseline session, e.g. the pre-tuning walk.
            session_b: comparison session, e.g. the post-tuning walk.
            rssi_threshold_dbm: pass/fail limit applied to both. Leave unset for the KPI default.
        """
        pa, pb = _resolve(session_a), _resolve(session_b)
        if not pa:
            return _err(f"session '{session_a}' not found")
        if not pb:
            return _err(f"session '{session_b}' not found")
        a, b = store.load_session(pa), store.load_session(pb)
        return json.dumps(store.compare(a, b, rssi_threshold_dbm), indent=2)
