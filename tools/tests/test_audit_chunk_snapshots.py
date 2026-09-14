import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


TOOLS_DIRECTORY = Path(__file__).resolve().parents[1]
CASES_PATH = TOOLS_DIRECTORY / "tests" / "fixtures" / "snapshot_audit_cases.json"
SPEC = importlib.util.spec_from_file_location(
    "audit_observation_log_snapshots",
    TOOLS_DIRECTORY / "audit_observation_log.py",
)
AUDITOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDITOR)


class AuditChunkSnapshotsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with CASES_PATH.open("r", encoding="utf-8") as source:
            cls.cases = json.load(source)

    def test_successful_completion_fixture(self):
        report = self._audit_case("successful_completion")

        self.assertEqual("PASS", report["status"])
        self.assertEqual(1, report["snapshots"]["final_coverage"]["complete"])
        self.assertEqual(list(range(16)),
            report["snapshots"]["generations"][0]["section_indices"])
        self.assertEqual(16, report["snapshots"]["scan"]["ticks"])
        self.assertGreater(report["snapshots"]["rate"]["estimated_bytes_per_hour"], 0)

    def test_partial_abort_fixture(self):
        report = self._audit_case("partial_abort")

        self.assertEqual("PASS", report["status"])
        self.assertEqual(1, report["snapshots"]["reconciliation"]["aborted"])
        self.assertEqual(1, report["snapshots"]["final_coverage"]["partial"])
        self.assertEqual([0, 1],
            report["snapshots"]["generations"][0]["section_indices"])

    def test_reload_generation_fixture(self):
        report = self._audit_case("reload_generation")

        self.assertEqual("PASS", report["status"])
        self.assertEqual([1, 2], [
            item["generation"]
            for item in report["snapshots"]["scheduled_generations"]
        ])
        self.assertEqual("SUPERSEDED",
            report["snapshots"]["generations"][0]["final_status"])
        self.assertEqual("COMPLETE",
            report["snapshots"]["generations"][1]["final_status"])

    def test_stale_complete_fixture(self):
        report = self._audit_case("stale_complete")

        self.assertEqual("PASS", report["status"])
        self.assertEqual(1, report["snapshots"]["final_coverage"]["stale"])
        self.assertTrue(report["snapshots"]["generations"][0]["unloaded"])

    def test_invalid_completion_fixture(self):
        self._assert_failing_case("invalid_completion")

    def test_duplicate_sections_fixture(self):
        report = self._assert_failing_case("duplicate_sections")
        self.assertEqual(1, len(report["snapshots"]["duplicate_section_captures"]))

    def test_queue_drop_fixture(self):
        report = self._assert_failing_case("queue_drop")
        self.assertEqual(1, report["snapshots"]["queue"]["dropped"])

    def test_summary_mismatch_fixture(self):
        report = self._assert_failing_case("summary_mismatch")
        self.assertEqual(1,
            len(report["snapshots"]["reconciliation"]["summary_mismatches"]))

    def test_obsolete_generation_mutation_fixture(self):
        report = self._assert_failing_case("obsolete_generation_mutation")
        self.assertEqual(1, report["snapshots"]["obsolete_generation_mutations"])

    def test_text_report_bounds_generation_details_unless_verbose(self):
        report = self._audit_case("successful_completion")
        schedule_template = report["snapshots"]["scheduled_generations"][0]
        generation_template = report["snapshots"]["generations"][0]
        report["snapshots"]["scheduled_generations"] = []
        report["snapshots"]["generations"] = []
        for index in range(30):
            schedule = copy.deepcopy(schedule_template)
            schedule["chunk"] = "d=0 chunk={},0".format(index)
            schedule["generation"] = index + 1
            if index == 17:
                schedule["outcome"] = "PENDING_CAPACITY_EXCEEDED"
            report["snapshots"]["scheduled_generations"].append(schedule)
            generation = copy.deepcopy(generation_template)
            generation["chunk"] = "d=0 chunk={},0".format(index)
            generation["generation"] = index + 1
            report["snapshots"]["generations"].append(generation)

        concise = AUDITOR.format_text_report(report)
        verbose = AUDITOR.format_text_report(report, verbose_snapshots=True)

        self.assertIn("scheduled generations: showing 12 of 30", concise)
        self.assertIn("section captures by generation: showing 12 of 30", concise)
        self.assertIn("use --verbose-snapshots for all", concise)
        self.assertIn("chunk=17,0 generation=18 outcome=PENDING_CAPACITY_EXCEEDED", concise)
        self.assertIn("scheduled generations: showing 30 of 30", verbose)
        self.assertIn("section captures by generation: showing 30 of 30", verbose)
        self.assertNotIn("use --verbose-snapshots for all", verbose)
        self.assertIn("chunk=29,0 generation=30", verbose)

    def test_verbose_snapshot_cli_option_is_explicit(self):
        options = AUDITOR.build_argument_parser().parse_args(
            ["session.jsonl", "--verbose-snapshots"]
        )

        self.assertTrue(options.verbose_snapshots)

    def _assert_failing_case(self, name):
        report = self._audit_case(name)
        case = self.cases[name]
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any(
            case["expected_error"] in error for error in report["errors"]
        ), report["errors"])
        return report

    def _audit_case(self, name):
        case = self.cases[name]
        records = build_session(case)
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture_path = Path(temporary_directory) / (name + ".jsonl")
            with fixture_path.open("w", encoding="utf-8") as destination:
                for record in records:
                    destination.write(json.dumps(record, separators=(",", ":")) + "\n")
            report = AUDITOR.audit_file(str(fixture_path))
        self.assertEqual(case["expected_status"], report["status"], report["errors"])
        return report


def build_session(case):
    records = []
    next_sequence = [1]
    next_snapshot_sequence = [1]
    monotonic = [0]
    snapshot_event_count = 0
    scan_ticks = []
    states = []

    def append(component, event_type, details):
        record = {
            "schema_version": 1,
            "session_id": "snapshot-fixture",
            "sequence": next_sequence[0],
            "client_tick": next_sequence[0],
            "world_tick": next_sequence[0],
            "monotonic_nanos": monotonic[0],
            "wall_time_utc": "2026-08-03T00:00:00Z",
            "source_thread": "fixture",
            "component": component,
            "event_type": event_type,
            "details": details,
        }
        next_sequence[0] += 1
        monotonic[0] += 100_000_000
        records.append(record)

    append("session", "session_start", {"build_version": "fixture"})
    total_section_events = sum(
        len(generation.get("sections", [])) for generation in case["generations"]
    ) + int("obsolete_section" in case)
    emitted_section_events = 0
    for generation_spec in case["generations"]:
        generation = generation_spec["generation"]
        state = {
            "generation": generation,
            "sections": set(),
            "section_events": 0,
            "complete": False,
            "abort": bool(generation_spec.get("abort")),
            "unload": bool(generation_spec.get("unload")),
        }
        states.append(state)
        append("chunk_snapshot", "chunk_snapshot_scheduled", snapshot_details(
            next_snapshot_sequence, generation, "SCHEDULED", 0, "SCHEDULED"
        ))
        snapshot_event_count += 1
        for section_index in generation_spec.get("sections", []):
            if section_index not in state["sections"]:
                state["sections"].add(section_index)
            state["section_events"] += 1
            emitted_section_events += 1
            append("chunk_snapshot", "chunk_snapshot_section_captured", snapshot_details(
                next_snapshot_sequence,
                generation,
                "CAPTURED",
                len(state["sections"]),
                "SECTION_APPLIED",
                section_index=section_index,
            ))
            snapshot_event_count += 1
            is_last_capture = emitted_section_events == total_section_events
            stop_reason = "NONE" if is_last_capture else "BLOCK_BUDGET"
            elapsed = 100_000 + emitted_section_events
            previous_max = scan_ticks[-1]["max"] if scan_ticks else 0
            reported_max = max(previous_max, elapsed)
            scan_ticks.append({
                "blocks": 4096,
                "elapsed": elapsed,
                "max": reported_max,
                "reason": stop_reason,
                "resumes": 0,
            })
            append("chunk_snapshot", "chunk_snapshot_scan_tick", snapshot_details(
                next_snapshot_sequence,
                generation,
                "METRICS",
                0,
                "METRICS",
                scan=scan_ticks[-1],
            ))
            snapshot_event_count += 1

        if generation_spec.get("complete"):
            valid = state["sections"] == set(range(16)) and state["section_events"] == 16
            state["complete"] = valid
            append("chunk_snapshot", "chunk_snapshot_completed", snapshot_details(
                next_snapshot_sequence,
                generation,
                "COMPLETE",
                16,
                "COMPLETED" if valid else "PARTIAL",
            ))
            snapshot_event_count += 1
        if state["abort"]:
            append("chunk_snapshot", "chunk_snapshot_aborted", snapshot_details(
                next_snapshot_sequence,
                generation,
                "ABORTED",
                len(state["sections"]),
                "ABORTED",
                reason="fixture_abort",
            ))
            snapshot_event_count += 1
        if state["unload"]:
            append("chunk_snapshot", "chunk_snapshot_unloaded", snapshot_details(
                next_snapshot_sequence,
                generation,
                "STALE",
                len(state["sections"]),
                "STALE",
                reason="chunk_unloaded",
            ))
            snapshot_event_count += 1

    obsolete_section = case.get("obsolete_section")
    if obsolete_section is not None:
        append("chunk_snapshot", "chunk_snapshot_section_captured", snapshot_details(
            next_snapshot_sequence,
            obsolete_section["generation"],
            "CAPTURED",
            obsolete_section["captured_sections"],
            "OLD_GENERATION",
            section_index=obsolete_section["section"],
        ))
        snapshot_event_count += 1

    append("observation", "observation_pipeline_summary", {
        "observation_schema_version": "1",
        "accepted_events": "0",
        "processed_events": "0",
        "dropped_events": "0",
        "failure_count": "0",
        "failure_message": "",
        "queue_depth": "0",
        "queue_capacity": "4096",
    })

    current = states[-1]
    if current["unload"]:
        final_status = "STALE"
    elif current["complete"]:
        final_status = "COMPLETE"
    else:
        final_status = "PARTIAL"
    valid_completions = sum(state["complete"] for state in states)
    aborts = sum(state["abort"] for state in states)
    invalid_completions = sum(
        bool(spec.get("complete")) and not state["complete"]
        for spec, state in zip(case["generations"], states)
    )
    last_scan = scan_ticks[-1] if scan_ticks else {
        "blocks": 0, "elapsed": 0, "max": 0, "reason": "NONE", "resumes": 0
    }
    summary = {
        "snapshot_schema_version": "1",
        "pending_chunks": "0",
        "pending_sections": "0",
        "scheduled_loads": str(len(states)),
        "duplicate_loads": "0",
        "dropped_schedules": "0",
        "scanned_blocks": str(total_section_events * 4096),
        "scanned_sections": str(total_section_events),
        "blocks_copied_this_tick": str(last_scan["blocks"]),
        "last_scan_nanos": str(last_scan["elapsed"]),
        "average_scan_nanos": str(
            sum(tick["elapsed"] for tick in scan_ticks) // len(scan_ticks)
            if scan_ticks else 0
        ),
        "max_scan_nanos": str(last_scan["max"]),
        "scan_stop_reason": last_scan["reason"],
        "block_budget_stop_count": str(sum(
            tick["reason"] == "BLOCK_BUDGET" for tick in scan_ticks
        )),
        "time_budget_stop_count": str(sum(
            tick["reason"] == "TIME_BUDGET" for tick in scan_ticks
        )),
        "partial_section_resume_count": str(last_scan["resumes"]),
        "completed_snapshots": str(valid_completions),
        "aborted_snapshots": str(aborts),
        "partial_snapshots": str(aborts + invalid_completions),
        "complete_chunks": str(int(final_status == "COMPLETE")),
        "partial_chunks": str(int(final_status == "PARTIAL")),
        "stale_chunks": str(int(final_status == "STALE")),
        "covered_sections": str(len(current["sections"])),
        "rejected_old_generations": str(int(obsolete_section is not None)),
        "rejected_out_of_order": "0",
        "accepted_events": str(snapshot_event_count),
        "processed_events": str(snapshot_event_count),
        "dropped_events": "0",
        "failure_count": "0",
        "failure_message": "",
        "queue_depth": "0",
        "queue_capacity": "512",
        "last_processing_nanos": "1",
        "average_processing_nanos": "1",
        "max_processing_nanos": "1",
    }
    summary.update(case.get("summary_overrides", {}))
    append("chunk_snapshot", "chunk_snapshot_pipeline_summary", summary)
    append("session", "session_end", {"dropped_records": "0", "failure": ""})
    return records


def snapshot_details(
    next_sequence,
    generation,
    capture_outcome,
    captured_sections,
    apply_outcome,
    section_index=None,
    reason=None,
    scan=None,
):
    details = {
        "snapshot_schema_version": "1",
        "snapshot_capture_sequence": str(next_sequence[0]),
        "dimension": "0",
        "chunk_x": "2",
        "chunk_z": "3",
        "load_generation": str(generation),
        "captured_sections": str(captured_sections),
        "capture_outcome": capture_outcome,
        "apply_outcome": apply_outcome,
        "coverage_status": "CAPTURING",
        "covered_sections": str(captured_sections),
    }
    next_sequence[0] += 1
    if section_index is not None:
        details.update({
            "section_index": str(section_index),
            "coverage_blocks": "4096",
            "state_encoding": "uniform_u16_v1",
            "state_data": "0",
        })
    if reason is not None:
        details["reason"] = reason
    if scan is not None:
        details.update({
            "blocks_copied_this_tick": str(scan["blocks"]),
            "scan_elapsed_nanos_this_tick": str(scan["elapsed"]),
            "scan_stop_reason": scan["reason"],
            "block_budget_stopped_work": str(
                scan["reason"] == "BLOCK_BUDGET"
            ).lower(),
            "time_budget_stopped_work": str(
                scan["reason"] == "TIME_BUDGET"
            ).lower(),
            "max_observed_client_scan_nanos": str(scan["max"]),
            "partial_section_resume_count": str(scan["resumes"]),
        })
    return details


if __name__ == "__main__":
    unittest.main()
