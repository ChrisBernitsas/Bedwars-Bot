import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


TOOLS_DIRECTORY = Path(__file__).resolve().parents[1]
FIXTURE = TOOLS_DIRECTORY / "tests" / "fixtures" / "observation_session.jsonl"
SPEC = importlib.util.spec_from_file_location(
    "audit_observation_log",
    TOOLS_DIRECTORY / "audit_observation_log.py",
)
AUDITOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDITOR)


class AuditObservationLogTest(unittest.TestCase):
    def test_representative_fixture_passes_and_reconstructs_overlay(self):
        report = AUDITOR.audit_file(str(FIXTURE))

        self.assertEqual("PASS", report["status"])
        self.assertEqual([], report["errors"])
        self.assertEqual(1, report["overlay"]["added"])
        self.assertEqual(1, report["overlay"]["updated"])
        self.assertEqual(1, report["overlay"]["refreshed"])
        self.assertEqual(1, report["overlay"]["stale"])
        self.assertEqual(1, report["overlay"]["unloaded"])
        self.assertEqual(1, report["overlay"]["final_known"])
        self.assertEqual(0, report["health"]["logger_dropped_records"])
        self.assertEqual(0, report["health"]["observation"]["dropped_events"])
        self.assertEqual(2, len(report["markers"]))
        self.assertEqual(10, report["event_rate"]["peak"])
        self.assertEqual(1, len(report["event_rate"]["burst_periods"]))
        self.assertEqual(4, report["positions"][0]["observations"])
        self.assertEqual(2, len(report["duplicate_observations"]))
        passive_context = report["markers"][0]["passive_context"]
        self.assertEqual((0, 0, 64, 0), passive_context["player_block_position"])
        self.assertEqual(
            {"x": 0.25, "y": 64.0, "z": 0.75},
            passive_context["player_precise_position"],
        )
        self.assertEqual(90.0, passive_context["player_yaw"])
        self.assertEqual(12.5, passive_context["player_pitch"])
        self.assertEqual(
            {"registry_name": "minecraft:wool", "metadata": 14},
            passive_context["held_item"],
        )
        self.assertEqual("BLOCK", passive_context["crosshair_target_type"])
        self.assertEqual((0, 1, 64, 1), passive_context["target_position"])
        self.assertEqual(
            "minecraft:stone",
            passive_context["target_block_state"]["registry_name"],
        )
        history_event_types = {
            entry["event_type"]
            for entry in report["markers"][0]["target_history"]
        }
        self.assertIn("block_state_observed", history_event_types)
        self.assertTrue(
            any(event_type.startswith("overlay_") for event_type in history_event_types)
        )
        self.assertTrue(
            any("final loaded set contains 1 chunk" in warning
                for warning in report["warnings"])
        )

    def test_marker_target_history_uses_configurable_time_window(self):
        default_report = AUDITOR.audit_file(str(FIXTURE))
        narrow_report = AUDITOR.audit_file(
            str(FIXTURE), marker_position_window_seconds=0.005
        )

        self.assertGreater(len(default_report["markers"][0]["target_history"]), 0)
        self.assertEqual([], narrow_report["markers"][0]["target_history"])
        text = AUDITOR.format_text_report(default_report)
        self.assertIn("crosshair=BLOCK target=d=0 1,64,1 state=minecraft:stone#0", text)
        self.assertIn("target history (+/-5.000s)", text)

    def test_dimension_unload_cleanup_suppresses_missing_chunk_unload_warning(self):
        records, file_bytes = AUDITOR.load_jsonl(str(FIXTURE))
        cleaned = self._append_dimension_unload(records)

        report = AUDITOR.audit_records(cleaned, file_bytes)

        self.assertEqual("PASS", report["status"])
        self.assertEqual(0, report["chunks"]["final_loaded_chunks"])
        self.assertTrue(report["chunks"]["cleanup_through_dimension_unload"])
        self.assertEqual(1, report["chunks"]["loaded_chunks_cleared_by_dimension_unload"])
        self.assertFalse(
            any("chunk load/unload balance" in warning
                or "final loaded set" in warning
                for warning in report["warnings"])
        )
        self.assertIn(
            "dimension-unload cleanup: events=1 loaded chunks cleared=1 cleanup_occurred=true",
            AUDITOR.format_text_report(report),
        )

    def test_cli_writes_optional_machine_readable_report(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            destination = Path(temporary_directory) / "report.json"
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                exit_code = AUDITOR.main(
                    [str(FIXTURE), "--json-report", str(destination)]
                )

            self.assertEqual(0, exit_code)
            self.assertIn("Result: PASS", output.getvalue())
            with destination.open("r", encoding="utf-8") as source:
                machine_report = json.load(source)
            self.assertEqual("PASS", machine_report["status"])
            self.assertEqual(1, machine_report["overlay"]["final_known"])

    def test_outer_schema_violation_fails(self):
        records, file_bytes = AUDITOR.load_jsonl(str(FIXTURE))
        corrupted = copy.deepcopy(records)
        corrupted[0]["schema_version"] = 2

        report = AUDITOR.audit_records(corrupted, file_bytes)

        self.assertEqual("FAIL", report["status"])
        self.assertTrue(
            any("outer schema_version" in error for error in report["errors"])
        )

    def test_observation_sequence_regression_fails(self):
        records, file_bytes = AUDITOR.load_jsonl(str(FIXTURE))
        corrupted = copy.deepcopy(records)
        block_observations = [
            record
            for record in corrupted
            if record.get("event_type") == "block_state_observed"
        ]
        block_observations[2]["details"]["observation_sequence"] = "2"

        report = AUDITOR.audit_records(corrupted, file_bytes)

        self.assertEqual("FAIL", report["status"])
        self.assertTrue(
            any("observation sequence is not strictly increasing" in error
                for error in report["errors"])
        )

    def test_missing_overlay_pair_fails_and_cli_returns_nonzero(self):
        records, file_bytes = AUDITOR.load_jsonl(str(FIXTURE))
        corrupted = [
            record
            for record in copy.deepcopy(records)
            if record.get("event_type") != "overlay_updated"
        ]

        report = AUDITOR.audit_records(corrupted, file_bytes)

        self.assertEqual("FAIL", report["status"])
        self.assertTrue(
            any("block_state_observed" in error and "overlay outcome" in error
                for error in report["errors"])
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            corrupted_path = Path(temporary_directory) / "missing-overlay.jsonl"
            with corrupted_path.open("w", encoding="utf-8") as destination:
                for record in corrupted:
                    serializable = {
                        key: value
                        for key, value in record.items()
                        if key != "_audit_line_number"
                    }
                    destination.write(json.dumps(serializable) + "\n")
            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = AUDITOR.main([str(corrupted_path)])
            self.assertEqual(1, exit_code)

    @staticmethod
    def _append_dimension_unload(records):
        cleaned = copy.deepcopy(records)
        summary_index = next(
            index
            for index, record in enumerate(cleaned)
            if record.get("event_type") == "observation_pipeline_summary"
        )
        summary = cleaned[summary_index]
        session_end = cleaned[summary_index + 1]
        summary["sequence"] = 19
        session_end["sequence"] = 20
        summary["details"]["accepted_events"] = "8"
        summary["details"]["processed_events"] = "8"
        summary["details"]["overlay_known"] = "0"
        summary["details"]["overlay_stale"] = "1"
        observation = {
            "schema_version": 1,
            "session_id": "fixture-session",
            "sequence": 17,
            "client_tick": 9,
            "world_tick": 108,
            "monotonic_nanos": 5500000000,
            "wall_time_utc": "2026-08-03T00:00:04.500Z",
            "source_thread": "bedwarsbot-observation-worker",
            "component": "observation",
            "event_type": "dimension_unloaded_observed",
            "details": {
                "chunk_x": "0",
                "chunk_z": "0",
                "dimension": "0",
                "observation_schema_version": "1",
                "observation_sequence": "8",
                "observation_type": "DIMENSION_UNLOADED",
            },
        }
        overlay = {
            "schema_version": 1,
            "session_id": "fixture-session",
            "sequence": 18,
            "client_tick": 9,
            "world_tick": 108,
            "monotonic_nanos": 5600000000,
            "wall_time_utc": "2026-08-03T00:00:04.600Z",
            "source_thread": "bedwarsbot-observation-worker",
            "component": "block_overlay",
            "event_type": "overlay_dimension_unloaded",
            "details": {
                "affected_entries": "1",
                "chunk_x": "0",
                "chunk_z": "0",
                "current_availability": "UNKNOWN",
                "dimension": "0",
                "observation_schema_version": "1",
                "observation_sequence": "8",
                "outcome": "DIMENSION_UNLOADED",
                "overlay_known": "0",
                "overlay_size": "1",
                "overlay_stale": "1",
                "previous_availability": "UNKNOWN",
            },
        }
        cleaned[summary_index:summary_index] = [observation, overlay]
        return cleaned


if __name__ == "__main__":
    unittest.main()
