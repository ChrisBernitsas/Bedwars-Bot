#!/usr/bin/env python3
"""Audit Bedwars Bot schema-v1 observation JSONL sessions."""

import argparse
import collections
import json
import math
import os
import statistics
import sys


OUTER_SCHEMA_VERSION = 1
OBSERVATION_SCHEMA_VERSION = 1
SNAPSHOT_SCHEMA_VERSION = 1
SNAPSHOT_SECTION_INDICES = set(range(16))
DEFAULT_TOP_COUNT = 10
DEFAULT_MARKER_CONTEXT = 3
DEFAULT_MARKER_POSITION_WINDOW_SECONDS = 5.0
DEFAULT_SNAPSHOT_GENERATION_DISPLAY_LIMIT = 12

OBSERVATION_EVENT_TYPES = {
    "block_state_observed",
    "block_state_unavailable",
    "chunk_loaded_observed",
    "chunk_unloaded_observed",
    "dimension_unloaded_observed",
    "observation_processing_failure",
}

SNAPSHOT_EVENT_TYPES = {
    "chunk_snapshot_scheduled",
    "chunk_snapshot_section_captured",
    "chunk_snapshot_completed",
    "chunk_snapshot_aborted",
    "chunk_snapshot_unloaded",
    "chunk_snapshot_dimension_unloaded",
    "chunk_snapshot_scan_tick",
    "chunk_snapshot_processing_failure",
}


class AuditInputError(Exception):
    """Raised when the supplied file cannot be parsed as a session."""


def load_jsonl(path):
    records = []
    try:
        with open(path, "r", encoding="utf-8") as source:
            for line_number, raw_line in enumerate(source, 1):
                line = raw_line.strip()
                if not line:
                    raise AuditInputError("line {} is blank".format(line_number))
                try:
                    record = json.loads(line)
                except json.JSONDecodeError as error:
                    raise AuditInputError(
                        "line {} is invalid JSON: {}".format(line_number, error)
                    )
                if not isinstance(record, dict):
                    raise AuditInputError(
                        "line {} must contain a JSON object".format(line_number)
                    )
                record["_audit_line_number"] = line_number
                records.append(record)
    except OSError as error:
        raise AuditInputError(str(error))
    if not records:
        raise AuditInputError("session contains no records")
    try:
        file_bytes = os.path.getsize(path)
    except OSError as error:
        raise AuditInputError(str(error))
    return records, file_bytes


def audit_file(
    path,
    top_count=DEFAULT_TOP_COUNT,
    marker_context=DEFAULT_MARKER_CONTEXT,
    marker_position_window_seconds=DEFAULT_MARKER_POSITION_WINDOW_SECONDS,
):
    records, file_bytes = load_jsonl(path)
    return audit_records(
        records,
        file_bytes=file_bytes,
        source_path=os.path.abspath(path),
        top_count=top_count,
        marker_context=marker_context,
        marker_position_window_seconds=marker_position_window_seconds,
    )


def audit_records(
    records,
    file_bytes,
    source_path="<memory>",
    top_count=DEFAULT_TOP_COUNT,
    marker_context=DEFAULT_MARKER_CONTEXT,
    marker_position_window_seconds=DEFAULT_MARKER_POSITION_WINDOW_SECONDS,
):
    errors = []
    warnings = []
    global_sequences = []
    observation_sequences = []
    overlay_sequences = []
    session_ids = set()
    monotonic_values = []
    event_types = collections.Counter()
    observation_records = []
    overlay_records = []
    snapshot_records = []
    snapshot_sequences = []
    block_records_by_sequence = collections.defaultdict(list)
    overlay_records_by_sequence = collections.defaultdict(list)

    for index, record in enumerate(records):
        location = _record_location(record, index)
        schema_version = record.get("schema_version")
        if schema_version != OUTER_SCHEMA_VERSION:
            errors.append(
                "{} outer schema_version is {!r}, expected {}".format(
                    location, schema_version, OUTER_SCHEMA_VERSION
                )
            )

        session_id = record.get("session_id")
        if not isinstance(session_id, str) or not session_id:
            errors.append("{} has no valid session_id".format(location))
        else:
            session_ids.add(session_id)

        sequence = _required_int(record, "sequence", location, errors)
        if sequence is not None:
            global_sequences.append((sequence, location))

        monotonic = _required_int(record, "monotonic_nanos", location, errors)
        if monotonic is not None:
            monotonic_values.append(monotonic)

        component = record.get("component")
        event_type = record.get("event_type")
        details = record.get("details")
        if not isinstance(component, str) or not component:
            errors.append("{} has no valid component".format(location))
        if not isinstance(event_type, str) or not event_type:
            errors.append("{} has no valid event_type".format(location))
            event_type = "<invalid>"
        event_types[event_type] += 1
        if not isinstance(details, dict):
            errors.append("{} details must be an object".format(location))
            details = {}

        is_observation = component == "observation" and event_type in OBSERVATION_EVENT_TYPES
        is_overlay = component == "block_overlay" and event_type.startswith("overlay_")
        is_pipeline_summary = event_type == "observation_pipeline_summary"
        is_snapshot = component == "chunk_snapshot" and event_type in SNAPSHOT_EVENT_TYPES
        is_snapshot_summary = event_type == "chunk_snapshot_pipeline_summary"
        if is_observation or is_overlay or is_pipeline_summary:
            version = _detail_int(details, "observation_schema_version", location, errors)
            if version is not None and version != OBSERVATION_SCHEMA_VERSION:
                errors.append(
                    "{} observation schema version is {}, expected {}".format(
                        location, version, OBSERVATION_SCHEMA_VERSION
                    )
                )

        if is_snapshot or is_snapshot_summary:
            version = _detail_int(details, "snapshot_schema_version", location, errors)
            if version is not None and version != SNAPSHOT_SCHEMA_VERSION:
                errors.append(
                    "{} snapshot schema version is {}, expected {}".format(
                        location, version, SNAPSHOT_SCHEMA_VERSION
                    )
                )
        if is_snapshot:
            snapshot_records.append((index, record))
            if event_type != "chunk_snapshot_processing_failure":
                snapshot_sequence = _detail_int(
                    details, "snapshot_capture_sequence", location, errors
                )
                if snapshot_sequence is not None:
                    snapshot_sequences.append((snapshot_sequence, location))

        if is_observation:
            observation_sequence = _detail_int(
                details, "observation_sequence", location, errors
            )
            if observation_sequence is not None:
                observation_sequences.append((observation_sequence, location))
                observation_records.append((index, observation_sequence, record))
                if event_type == "block_state_observed":
                    block_records_by_sequence[observation_sequence].append((index, record))

        if is_overlay:
            observation_sequence = _detail_int(
                details, "observation_sequence", location, errors
            )
            if observation_sequence is not None:
                overlay_sequences.append((observation_sequence, location))
                overlay_records.append((index, observation_sequence, record))
                overlay_records_by_sequence[observation_sequence].append((index, record))

    if len(session_ids) != 1:
        errors.append("expected exactly one session_id, found {}".format(len(session_ids)))
    _verify_strict_order("global sequence", global_sequences, errors)
    _verify_strict_order("observation sequence", observation_sequences, errors)
    _verify_strict_order("overlay observation sequence", overlay_sequences, errors)
    _verify_strict_order("snapshot capture sequence", snapshot_sequences, errors)

    session_start_records = [
        record for record in records if record.get("event_type") == "session_start"
    ]
    session_end_records = [
        record for record in records if record.get("event_type") == "session_end"
    ]
    if len(session_start_records) != 1:
        errors.append(
            "expected exactly one session_start, found {}".format(len(session_start_records))
        )
    if len(session_end_records) != 1:
        errors.append(
            "expected exactly one session_end, found {}".format(len(session_end_records))
        )
    if len(session_start_records) == 1 and records[0] is not session_start_records[0]:
        errors.append("session_start is not the first record")
    if len(session_end_records) == 1 and records[-1] is not session_end_records[0]:
        errors.append("session_end is not the final record")

    for observation_sequence, block_records in sorted(block_records_by_sequence.items()):
        outcomes = overlay_records_by_sequence.get(observation_sequence, [])
        if len(outcomes) != len(block_records):
            errors.append(
                "observation_sequence {} has {} block_state_observed record(s) but {} overlay outcome(s)".format(
                    observation_sequence, len(block_records), len(outcomes)
                )
            )
            continue
        for (block_index, _), (overlay_index, _) in zip(block_records, outcomes):
            if overlay_index <= block_index:
                errors.append(
                    "observation_sequence {} overlay outcome precedes its block observation".format(
                        observation_sequence
                    )
                )

    replay = _replay_overlay(overlay_records, errors)
    analytics = _analyze_observations(observation_records, top_count, errors)
    chunk_report = _analyze_chunks(
        observation_records,
        replay["loaded_chunks"],
        replay["chunk_cleanup"],
        top_count,
        errors,
    )
    health = _analyze_health(
        records,
        session_end_records,
        observation_records,
        overlay_records,
        errors,
    )
    session = _analyze_session(records, monotonic_values, file_bytes, errors)
    rate = _analyze_event_rate(records, monotonic_values)
    markers = _analyze_markers(
        records,
        marker_context,
        marker_position_window_seconds,
        errors,
    )
    snapshots = _analyze_snapshots(
        records,
        snapshot_records,
        session["duration_seconds"],
        errors,
        warnings,
    )

    if not markers:
        warnings.append("session contains no verification_marker events")
    if not analytics["top_positions"]:
        warnings.append("session contains no block_state_observed events")
    if chunk_report["final_loaded_chunks"] > 0:
        warnings.append(
            "reconstructed final loaded set contains {} chunk(s); load/unload balance is {}".format(
                chunk_report["final_loaded_chunks"], chunk_report["balance"]
            )
        )
    elif chunk_report["balance"] != 0 and not chunk_report["cleanup_through_dimension_unload"]:
        warnings.append(
            "chunk load/unload balance is {} without dimension-unload cleanup".format(
                chunk_report["balance"]
            )
        )

    result = {
        "status": "PASS" if not errors else "FAIL",
        "source": source_path,
        "errors": errors,
        "warnings": warnings,
        "schema": {
            "expected_outer_version": OUTER_SCHEMA_VERSION,
            "expected_observation_version": OBSERVATION_SCHEMA_VERSION,
            "session_ids": sorted(session_ids),
        },
        "session": session,
        "overlay": replay["summary"],
        "health": health,
        "positions": analytics["top_positions"],
        "block_types": analytics["top_block_types"],
        "duplicate_observations": analytics["duplicates"],
        "chunks": chunk_report,
        "snapshots": snapshots,
        "event_rate": rate,
        "markers": markers,
        "event_type_counts": dict(sorted(event_types.items())),
    }
    return result


def _replay_overlay(overlay_records, errors):
    overlay = {}
    loaded_chunks = set()
    outcomes = collections.Counter()
    stale_transitions = 0
    dimension_cleanup_events = 0
    loaded_chunks_cleared_by_dimension_unload = 0

    for _, _, record in overlay_records:
        details = record.get("details", {})
        location = _record_location(record, 0)
        outcome = details.get("outcome")
        if not isinstance(outcome, str) or not outcome:
            errors.append("{} overlay record has no outcome".format(location))
            continue
        expected_event_type = "overlay_" + outcome.lower()
        if record.get("event_type") != expected_event_type:
            errors.append(
                "{} event_type {} does not match outcome {}".format(
                    location, record.get("event_type"), outcome
                )
            )
        outcomes[outcome] += 1
        affected = _detail_int(details, "affected_entries", location, errors)
        if affected is None:
            affected = 0

        if outcome in {"ADDED", "UPDATED", "DUPLICATE", "REFRESHED", "UNAVAILABLE"}:
            position = _position_from_details(details, location, errors)
            if position is None:
                continue
            previous_availability = overlay.get(position, {}).get("availability", "UNKNOWN")
            logged_previous = details.get("previous_availability")
            if logged_previous and previous_availability != logged_previous:
                errors.append(
                    "{} replay previous availability {} does not match logged {}".format(
                        location, previous_availability, logged_previous
                    )
                )
            current_availability = details.get("current_availability")
            if current_availability == "UNKNOWN":
                overlay.pop(position, None)
            elif current_availability in {"KNOWN", "STALE"}:
                state = _current_state_from_details(details)
                if state is None:
                    errors.append("{} current overlay state is missing".format(location))
                    continue
                overlay[position] = {
                    "availability": current_availability,
                    "state": state,
                }
            else:
                errors.append(
                    "{} has invalid current_availability {!r}".format(
                        location, current_availability
                    )
                )
            if outcome == "UNAVAILABLE":
                stale_transitions += affected

        elif outcome == "CHUNK_LOADED":
            chunk = _chunk_from_details(details, location, errors)
            if chunk is not None:
                loaded_chunks.add(chunk)
        elif outcome == "CHUNK_UNLOADED":
            chunk = _chunk_from_details(details, location, errors)
            if chunk is not None:
                loaded_chunks.discard(chunk)
                replay_affected = _stale_chunk(overlay, chunk)
                if replay_affected != affected:
                    errors.append(
                        "{} replay staled {} entries, logged affected_entries={}".format(
                            location, replay_affected, affected
                        )
                    )
                stale_transitions += affected
        elif outcome == "DIMENSION_UNLOADED":
            dimension = _detail_int(details, "dimension", location, errors)
            if dimension is not None:
                cleared_chunks = sum(
                    1 for chunk in loaded_chunks if chunk[0] == dimension
                )
                loaded_chunks = {
                    chunk for chunk in loaded_chunks if chunk[0] != dimension
                }
                dimension_cleanup_events += 1
                loaded_chunks_cleared_by_dimension_unload += cleared_chunks
                replay_affected = _stale_dimension(overlay, dimension)
                if replay_affected != affected:
                    errors.append(
                        "{} replay staled {} entries, logged affected_entries={}".format(
                            location, replay_affected, affected
                        )
                    )
                stale_transitions += affected
        elif outcome == "OUT_OF_ORDER":
            pass
        else:
            errors.append("{} has unsupported overlay outcome {}".format(location, outcome))

    final_known = sum(
        1 for value in overlay.values() if value.get("availability") == "KNOWN"
    )
    final_stale = sum(
        1 for value in overlay.values() if value.get("availability") == "STALE"
    )
    summary = {
        "added": outcomes["ADDED"],
        "updated": outcomes["UPDATED"],
        "refreshed": outcomes["REFRESHED"],
        "duplicates": outcomes["DUPLICATE"],
        "stale": stale_transitions,
        "unloaded": outcomes["CHUNK_UNLOADED"] + outcomes["DIMENSION_UNLOADED"],
        "chunk_unloaded": outcomes["CHUNK_UNLOADED"],
        "dimension_unloaded": outcomes["DIMENSION_UNLOADED"],
        "unavailable": outcomes["UNAVAILABLE"],
        "out_of_order": outcomes["OUT_OF_ORDER"],
        "final_known": final_known,
        "final_stale": final_stale,
        "final_overlay_size": len(overlay),
    }
    return {
        "summary": summary,
        "overlay": overlay,
        "loaded_chunks": loaded_chunks,
        "chunk_cleanup": {
            "dimension_unload_events": dimension_cleanup_events,
            "loaded_chunks_cleared": loaded_chunks_cleared_by_dimension_unload,
        },
    }


def _analyze_observations(observation_records, top_count, errors):
    position_counts = collections.Counter()
    block_type_counts = collections.Counter()
    position_state_counts = collections.Counter()

    for _, _, record in observation_records:
        if record.get("event_type") != "block_state_observed":
            continue
        details = record.get("details", {})
        location = _record_location(record, 0)
        position = _position_from_details(details, location, errors)
        if position is None:
            continue
        registry_name = details.get("block_registry_name")
        metadata = details.get("block_metadata")
        if not isinstance(registry_name, str) or not registry_name:
            errors.append("{} block registry name is missing".format(location))
            continue
        state_name = "{}#{}".format(registry_name, metadata)
        position_counts[position] += 1
        block_type_counts[registry_name] += 1
        position_state_counts[(position, state_name)] += 1

    top_positions = [
        {"position": _format_position(position), "observations": count}
        for position, count in position_counts.most_common(top_count)
    ]
    top_block_types = [
        {"block": block_type, "observations": count}
        for block_type, count in block_type_counts.most_common(top_count)
    ]
    duplicates = []
    repeated = [
        (key, count) for key, count in position_state_counts.items() if count > 1
    ]
    repeated.sort(key=lambda item: (-item[1], _format_position(item[0][0]), item[0][1]))
    for (position, state_name), count in repeated[:top_count]:
        duplicates.append(
            {
                "position": _format_position(position),
                "state": state_name,
                "observations": count,
                "repeats": count - 1,
            }
        )
    return {
        "top_positions": top_positions,
        "top_block_types": top_block_types,
        "duplicates": duplicates,
    }


def _analyze_chunks(
    observation_records,
    loaded_chunks,
    chunk_cleanup,
    top_count,
    errors,
):
    loads = collections.Counter()
    unloads = collections.Counter()
    for _, _, record in observation_records:
        event_type = record.get("event_type")
        if event_type not in {"chunk_loaded_observed", "chunk_unloaded_observed"}:
            continue
        location = _record_location(record, 0)
        chunk = _chunk_from_details(record.get("details", {}), location, errors)
        if chunk is None:
            continue
        if event_type == "chunk_loaded_observed":
            loads[chunk] += 1
        else:
            unloads[chunk] += 1

    chunks = set(loads) | set(unloads)
    imbalances = []
    for chunk in chunks:
        balance = loads[chunk] - unloads[chunk]
        if balance:
            imbalances.append(
                {
                    "chunk": _format_chunk(chunk),
                    "loads": loads[chunk],
                    "unloads": unloads[chunk],
                    "balance": balance,
                }
            )
    imbalances.sort(key=lambda item: (-abs(item["balance"]), item["chunk"]))
    total_loads = sum(loads.values())
    total_unloads = sum(unloads.values())
    return {
        "loads": total_loads,
        "unloads": total_unloads,
        "balance": total_loads - total_unloads,
        "final_loaded_chunks": len(loaded_chunks),
        "cleanup_through_dimension_unload": (
            len(loaded_chunks) == 0 and chunk_cleanup["loaded_chunks_cleared"] > 0
        ),
        "dimension_unload_events": chunk_cleanup["dimension_unload_events"],
        "loaded_chunks_cleared_by_dimension_unload": chunk_cleanup["loaded_chunks_cleared"],
        "unbalanced_chunks": imbalances[:top_count],
    }


def _analyze_health(records, session_end_records, observation_records, overlay_records, errors):
    logger_drops = 0
    logger_failure = ""
    if len(session_end_records) == 1:
        details = session_end_records[0].get("details", {})
        location = _record_location(session_end_records[0], 0)
        parsed_drops = _detail_int(details, "dropped_records", location, errors)
        if parsed_drops is not None:
            logger_drops = parsed_drops
        failure = details.get("failure", "")
        logger_failure = "" if failure is None else str(failure)

    summaries = [
        record
        for record in records
        if record.get("event_type") == "observation_pipeline_summary"
    ]
    observation_health = {
        "accepted_events": None,
        "dropped_events": None,
        "processed_events": None,
        "failure_count": None,
        "failure_message": "",
        "queue_depth": None,
        "queue_capacity": None,
    }
    if len(summaries) != 1:
        errors.append(
            "expected exactly one observation_pipeline_summary, found {}".format(
                len(summaries)
            )
        )
    else:
        details = summaries[0].get("details", {})
        location = _record_location(summaries[0], 0)
        for key in (
            "accepted_events",
            "dropped_events",
            "processed_events",
            "failure_count",
            "queue_depth",
            "queue_capacity",
        ):
            observation_health[key] = _detail_int(details, key, location, errors)
        observation_health["failure_message"] = str(details.get("failure_message", ""))

    if logger_drops:
        errors.append("logger reported {} dropped record(s)".format(logger_drops))
    if logger_failure:
        errors.append("logger reported failure: {}".format(logger_failure))

    observation_drops = observation_health["dropped_events"]
    observation_failures = observation_health["failure_count"]
    if observation_drops:
        errors.append(
            "observation queue reported {} dropped event(s)".format(observation_drops)
        )
    if observation_failures:
        errors.append(
            "observation pipeline reported {} failure(s): {}".format(
                observation_failures, observation_health["failure_message"]
            )
        )
    if observation_health["queue_depth"] not in (None, 0):
        errors.append(
            "observation pipeline closed with queue_depth={}".format(
                observation_health["queue_depth"]
            )
        )
    accepted = observation_health["accepted_events"]
    processed = observation_health["processed_events"]
    if accepted is not None and processed is not None and accepted != processed:
        errors.append(
            "observation accepted_events={} but processed_events={}".format(
                accepted, processed
            )
        )
    processing_failures = sum(
        1
        for _, _, record in observation_records
        if record.get("event_type") == "observation_processing_failure"
    )
    if processed is not None and not logger_drops and not observation_failures:
        if len(observation_records) != processed:
            errors.append(
                "pipeline summary processed_events={} but log contains {} observation record(s)".format(
                    processed, len(observation_records)
                )
            )
        successful = len(observation_records) - processing_failures
        if len(overlay_records) != successful:
            errors.append(
                "log contains {} successful observation record(s) but {} overlay record(s)".format(
                    successful, len(overlay_records)
                )
            )

    failure_event_counts = collections.Counter(
        record.get("event_type", "<missing>")
        for record in records
        if "failure" in str(record.get("event_type", "")).lower()
    )
    return {
        "logger_dropped_records": logger_drops,
        "logger_failure": logger_failure,
        "observation": observation_health,
        "failure_event_counts": dict(sorted(failure_event_counts.items())),
    }


def _analyze_snapshots(records, snapshot_records, duration_seconds, errors, warnings):
    summaries = [
        record
        for record in records
        if record.get("event_type") == "chunk_snapshot_pipeline_summary"
    ]
    empty = {
        "present": False,
        "scheduled_generations": [],
        "generations": [],
        "duplicate_section_captures": [],
        "obsolete_generation_events": [],
        "obsolete_generation_mutations": 0,
        "final_coverage": {"partial": 0, "complete": 0, "stale": 0},
        "reconciliation": {},
        "queue": {
            "accepted": None,
            "processed": None,
            "dropped": None,
            "failures": None,
            "final_depth": None,
            "capacity": None,
        },
        "scan": {
            "ticks": 0,
            "blocks": 0,
            "max_elapsed_nanos": 0,
            "max_reported_nanos": 0,
            "partial_section_resume_count": 0,
            "stop_reasons": {},
        },
        "rate": {
            "records": 0,
            "records_per_second": 0.0,
            "bytes": 0,
            "bytes_per_second": 0.0,
            "estimated_bytes_per_hour": 0.0,
        },
    }
    if not snapshot_records and not summaries:
        warnings.append("session contains no chunk-snapshot records")
        return empty

    result = dict(empty)
    result["present"] = True
    if len(summaries) != 1:
        errors.append(
            "expected exactly one chunk_snapshot_pipeline_summary, found {}".format(
                len(summaries)
            )
        )
        summary_details = None
        summary_location = "snapshot summary"
    else:
        summary_details = summaries[0].get("details", {})
        summary_location = _record_location(summaries[0], 0)

    current_generation = {}
    generations = {}
    scheduled_generations = []
    duplicate_sections = []
    obsolete_events = []
    obsolete_mutations = 0
    counts = collections.Counter()
    scan_stop_reasons = collections.Counter()
    scan_ticks = []
    previous_reported_max = 0
    previous_resume_count = 0
    mutation_types = {
        "chunk_snapshot_section_captured",
        "chunk_snapshot_completed",
        "chunk_snapshot_aborted",
        "chunk_snapshot_unloaded",
    }

    for _, record in snapshot_records:
        event_type = record.get("event_type")
        details = record.get("details", {})
        location = _record_location(record, 0)
        counts[event_type] += 1
        if event_type == "chunk_snapshot_processing_failure":
            errors.append("{} snapshot processing failure event".format(location))
            continue

        dimension = _detail_int(details, "dimension", location, errors)
        if event_type == "chunk_snapshot_dimension_unloaded":
            if dimension is None:
                continue
            for chunk, generation in list(current_generation.items()):
                if chunk[0] != dimension:
                    continue
                state = generations[(chunk, generation)]
                if not state["completed"] and not state["aborted"] and not state["schedule_dropped"]:
                    errors.append(
                        "{} dimension unload staled incomplete {} without an abort".format(
                            location, _snapshot_generation_label(chunk, generation)
                        )
                    )
                state["stale"] = True
                state["dimension_unloaded"] = True
            continue

        chunk_x = _detail_int(details, "chunk_x", location, errors)
        chunk_z = _detail_int(details, "chunk_z", location, errors)
        generation = _detail_int(details, "load_generation", location, errors)
        if None in (dimension, chunk_x, chunk_z, generation):
            continue
        chunk = (dimension, chunk_x, chunk_z)
        key = (chunk, generation)
        capture_outcome = str(details.get("capture_outcome", ""))
        logged_apply_outcome = str(details.get("apply_outcome", ""))
        if logged_apply_outcome == "OLD_GENERATION":
            counts["rejected_old_generations"] += 1
        elif logged_apply_outcome == "OUT_OF_ORDER":
            counts["rejected_out_of_order"] += 1

        if event_type == "chunk_snapshot_scheduled":
            previous = current_generation.get(chunk)
            if capture_outcome == "DUPLICATE":
                counts["duplicate_load_callbacks"] += 1
                if previous != generation or key not in generations:
                    errors.append(
                        "{} duplicate load references unknown/current-mismatched generation {}".format(
                            location, generation
                        )
                    )
                continue
            if capture_outcome not in {"SCHEDULED", "PENDING_CAPACITY_EXCEEDED"}:
                errors.append(
                    "{} unsupported snapshot schedule outcome {!r}".format(
                        location, capture_outcome
                    )
                )
                continue
            if previous is not None and generation <= previous:
                errors.append(
                    "{} reload generation {} does not progress beyond {} for {}".format(
                        location, generation, previous, _format_chunk(chunk)
                    )
                )
                continue
            if key in generations:
                errors.append(
                    "{} schedules generation {} more than once".format(location, generation)
                )
                continue
            current_generation[chunk] = generation
            state = {
                "chunk": chunk,
                "generation": generation,
                "sections": set(),
                "section_events": 0,
                "completed": False,
                "invalid_completion": False,
                "aborted": False,
                "stale": False,
                "unloaded": False,
                "dimension_unloaded": False,
                "schedule_dropped": capture_outcome == "PENDING_CAPACITY_EXCEEDED",
                "partial_recorded": capture_outcome == "PENDING_CAPACITY_EXCEEDED",
            }
            generations[key] = state
            scheduled_generations.append(
                {
                    "chunk": _format_chunk(chunk),
                    "generation": generation,
                    "outcome": capture_outcome,
                }
            )
            counts["scheduled_loads"] += capture_outcome == "SCHEDULED"
            counts["dropped_schedules"] += capture_outcome == "PENDING_CAPACITY_EXCEEDED"
            continue

        current = current_generation.get(chunk)
        if current != generation:
            obsolete = current is not None and generation < current
            event = {
                "event_type": event_type,
                "chunk": _format_chunk(chunk),
                "generation": generation,
                "current_generation": current,
                "obsolete": obsolete,
                "location": location,
            }
            obsolete_events.append(event)
            if event_type in mutation_types:
                obsolete_mutations += 1
                errors.append(
                    "{} obsolete-generation mutation {} generation={} current={}".format(
                        location, event_type, generation, current
                    )
                )
            elif not obsolete:
                errors.append(
                    "{} snapshot event references unscheduled generation {}".format(
                        location, generation
                    )
                )
            continue
        state = generations.get(key)
        if state is None:
            errors.append(
                "{} snapshot event references missing scheduled generation {}".format(
                    location, generation
                )
            )
            continue

        apply_outcome = str(details.get("apply_outcome", ""))
        if apply_outcome in {"OLD_GENERATION", "OUT_OF_ORDER"}:
            errors.append(
                "{} worker rejected snapshot event with apply_outcome={}".format(
                    location, apply_outcome
                )
            )

        if event_type == "chunk_snapshot_section_captured":
            section_index = _detail_int(details, "section_index", location, errors)
            captured_sections = _detail_int(
                details, "captured_sections", location, errors
            )
            if section_index is None or captured_sections is None:
                continue
            if section_index not in SNAPSHOT_SECTION_INDICES:
                errors.append(
                    "{} section_index {} is outside 0-15".format(
                        location, section_index
                    )
                )
                continue
            expected_next = len(state["sections"])
            if section_index in state["sections"]:
                duplicate = {
                    "chunk": _format_chunk(chunk),
                    "generation": generation,
                    "section_index": section_index,
                    "location": location,
                }
                duplicate_sections.append(duplicate)
                errors.append(
                    "{} duplicate section capture {} for {}".format(
                        location,
                        section_index,
                        _snapshot_generation_label(chunk, generation),
                    )
                )
            else:
                if section_index != expected_next:
                    errors.append(
                        "{} section {} is out of capture order; expected {}".format(
                            location, section_index, expected_next
                        )
                    )
                state["sections"].add(section_index)
            state["section_events"] += 1
            if captured_sections != len(state["sections"]):
                errors.append(
                    "{} captured_sections={} but replay has {} unique section(s)".format(
                        location, captured_sections, len(state["sections"])
                    )
                )

        elif event_type == "chunk_snapshot_completed":
            if state["completed"]:
                errors.append(
                    "{} duplicate completion for {}".format(
                        location, _snapshot_generation_label(chunk, generation)
                    )
                )
            missing = sorted(SNAPSHOT_SECTION_INDICES - state["sections"])
            extra_count = state["section_events"] - len(state["sections"])
            valid_completion = not missing and extra_count == 0 and len(state["sections"]) == 16
            if not valid_completion:
                errors.append(
                    "{} completion without exactly sections 0-15 for {}: missing={} duplicate_events={}".format(
                        location,
                        _snapshot_generation_label(chunk, generation),
                        missing,
                        extra_count,
                    )
                )
            captured_sections = _detail_int(
                details, "captured_sections", location, errors
            )
            if captured_sections is not None and captured_sections != 16:
                errors.append(
                    "{} completion captured_sections={} instead of 16".format(
                        location, captured_sections
                    )
                )
            state["completed"] = valid_completion
            state["invalid_completion"] = not valid_completion
            state["partial_recorded"] = not valid_completion
            counts["valid_completions"] += valid_completion

        elif event_type == "chunk_snapshot_aborted":
            captured_sections = _detail_int(
                details, "captured_sections", location, errors
            )
            if state["completed"]:
                errors.append(
                    "{} abort follows completion for {}".format(
                        location, _snapshot_generation_label(chunk, generation)
                    )
                )
            if state["aborted"]:
                errors.append(
                    "{} duplicate abort for {}".format(
                        location, _snapshot_generation_label(chunk, generation)
                    )
                )
            if captured_sections is not None and captured_sections != len(state["sections"]):
                errors.append(
                    "{} abort captured_sections={} but replay has {}".format(
                        location, captured_sections, len(state["sections"])
                    )
                )
            state["aborted"] = True
            state["partial_recorded"] = True

        elif event_type == "chunk_snapshot_unloaded":
            captured_sections = _detail_int(
                details, "captured_sections", location, errors
            )
            if captured_sections is not None and captured_sections != len(state["sections"]):
                errors.append(
                    "{} unload captured_sections={} but replay has {}".format(
                        location, captured_sections, len(state["sections"])
                    )
                )
            if not state["completed"] and not state["aborted"] and not state["schedule_dropped"]:
                errors.append(
                    "{} incomplete unload for {} was not preceded by abort".format(
                        location, _snapshot_generation_label(chunk, generation)
                    )
                )
            state["unloaded"] = True
            state["stale"] = True

        elif event_type == "chunk_snapshot_scan_tick":
            blocks = _detail_int(details, "blocks_copied_this_tick", location, errors)
            elapsed = _detail_int(
                details, "scan_elapsed_nanos_this_tick", location, errors
            )
            reported_max = _detail_int(
                details, "max_observed_client_scan_nanos", location, errors
            )
            resume_count = _detail_int(
                details, "partial_section_resume_count", location, errors
            )
            stop_reason = str(details.get("scan_stop_reason", ""))
            if stop_reason not in {"NONE", "BLOCK_BUDGET", "TIME_BUDGET"}:
                errors.append(
                    "{} invalid scan_stop_reason {!r}".format(location, stop_reason)
                )
            if blocks is not None and (blocks < 0 or blocks > 4096):
                errors.append(
                    "{} blocks_copied_this_tick={} is outside 0-4096".format(
                        location, blocks
                    )
                )
            if elapsed is not None and elapsed < 0:
                errors.append("{} scan elapsed time is negative".format(location))
            if stop_reason == "BLOCK_BUDGET" and blocks != 4096:
                errors.append(
                    "{} block-budget stop copied {} blocks instead of 4096".format(
                        location, blocks
                    )
                )
            if stop_reason == "TIME_BUDGET" and elapsed is not None and elapsed < 1_500_000:
                errors.append(
                    "{} time-budget stop elapsed {}ns before 1500000ns".format(
                        location, elapsed
                    )
                )
            expected_block_flag = str(stop_reason == "BLOCK_BUDGET").lower()
            expected_time_flag = str(stop_reason == "TIME_BUDGET").lower()
            if str(details.get("block_budget_stopped_work", "")).lower() != expected_block_flag:
                errors.append("{} block-budget stop flag is inconsistent".format(location))
            if str(details.get("time_budget_stopped_work", "")).lower() != expected_time_flag:
                errors.append("{} time-budget stop flag is inconsistent".format(location))
            if reported_max is not None:
                if reported_max < previous_reported_max or (
                    elapsed is not None and reported_max < elapsed
                ):
                    errors.append(
                        "{} maximum observed scan time regressed or is below this tick".format(
                            location
                        )
                    )
                previous_reported_max = reported_max
            if resume_count is not None:
                if resume_count < previous_resume_count:
                    errors.append(
                        "{} partial-section resume count regressed".format(location)
                    )
                previous_resume_count = resume_count
            scan_stop_reasons[stop_reason] += 1
            scan_ticks.append(
                {
                    "blocks": blocks or 0,
                    "elapsed_nanos": elapsed or 0,
                    "reported_max_nanos": reported_max or 0,
                    "resume_count": resume_count or 0,
                    "stop_reason": stop_reason,
                }
            )

    generation_report = []
    if counts["chunk_snapshot_section_captured"] and not scan_ticks:
        errors.append("section captures are present but no chunk_snapshot_scan_tick records exist")
    if scan_ticks:
        replay_max_scan = max(tick["elapsed_nanos"] for tick in scan_ticks)
        if previous_reported_max != replay_max_scan:
            errors.append(
                "scan reported maximum {}ns but replay maximum is {}ns".format(
                    previous_reported_max, replay_max_scan
                )
            )
    final_counts = collections.Counter()
    covered_sections = 0
    partial_lifetime = 0
    for (chunk, generation), state in sorted(
        generations.items(), key=lambda item: (item[0][0], item[0][1])
    ):
        is_current = current_generation.get(chunk) == generation
        if is_current:
            covered_sections += len(state["sections"])
            if state["stale"]:
                final_status = "STALE"
            elif state["completed"]:
                final_status = "COMPLETE"
            else:
                final_status = "PARTIAL"
                if (
                    not state["aborted"]
                    and not state["schedule_dropped"]
                    and not state["invalid_completion"]
                ):
                    errors.append(
                        "scheduled generation has no completion or abort: {}".format(
                            _snapshot_generation_label(chunk, generation)
                        )
                    )
            final_counts[final_status.lower()] += 1
        else:
            final_status = "SUPERSEDED"
        partial_lifetime += bool(state["partial_recorded"])
        generation_report.append(
            {
                "chunk": _format_chunk(chunk),
                "generation": generation,
                "section_indices": sorted(state["sections"]),
                "section_capture_events": state["section_events"],
                "completed": state["completed"],
                "invalid_completion": state["invalid_completion"],
                "aborted": state["aborted"],
                "unloaded": state["unloaded"],
                "dimension_unloaded": state["dimension_unloaded"],
                "final_status": final_status,
            }
        )

    queue = empty["queue"].copy()
    reconciliation = {
        "scheduled": counts["scheduled_loads"],
        "completed": counts["valid_completions"],
        "completion_records": counts["chunk_snapshot_completed"],
        "aborted": counts["chunk_snapshot_aborted"],
        "schedule_dropped": counts["dropped_schedules"],
        "duplicate_load_callbacks": counts["duplicate_load_callbacks"],
        "section_captures": counts["chunk_snapshot_section_captured"],
    }
    if summary_details is not None:
        numeric_summary = {}
        for name in (
            "pending_chunks",
            "pending_sections",
            "scheduled_loads",
            "duplicate_loads",
            "dropped_schedules",
            "scanned_blocks",
            "scanned_sections",
            "last_scan_nanos",
            "average_scan_nanos",
            "max_scan_nanos",
            "blocks_copied_this_tick",
            "block_budget_stop_count",
            "time_budget_stop_count",
            "partial_section_resume_count",
            "completed_snapshots",
            "aborted_snapshots",
            "partial_snapshots",
            "complete_chunks",
            "partial_chunks",
            "stale_chunks",
            "covered_sections",
            "rejected_old_generations",
            "rejected_out_of_order",
            "accepted_events",
            "processed_events",
            "dropped_events",
            "failure_count",
            "queue_depth",
            "queue_capacity",
        ):
            numeric_summary[name] = _detail_int(
                summary_details, name, summary_location, errors
            )
        queue = {
            "accepted": numeric_summary["accepted_events"],
            "processed": numeric_summary["processed_events"],
            "dropped": numeric_summary["dropped_events"],
            "failures": numeric_summary["failure_count"],
            "final_depth": numeric_summary["queue_depth"],
            "capacity": numeric_summary["queue_capacity"],
        }
        if queue["dropped"]:
            errors.append(
                "snapshot queue reported {} dropped event(s)".format(queue["dropped"])
            )
        if queue["failures"]:
            errors.append(
                "snapshot pipeline reported {} failure(s)".format(queue["failures"])
            )
        if queue["final_depth"] not in (None, 0):
            errors.append(
                "snapshot pipeline closed with queue_depth={}".format(
                    queue["final_depth"]
                )
            )
        if queue["accepted"] is not None and queue["processed"] is not None:
            if queue["accepted"] != queue["processed"]:
                errors.append(
                    "snapshot accepted_events={} but processed_events={}".format(
                        queue["accepted"], queue["processed"]
                    )
                )
            if queue["processed"] < len(snapshot_records):
                errors.append(
                    "snapshot summary processed_events={} but log has {} snapshot event record(s)".format(
                        queue["processed"], len(snapshot_records)
                    )
                )
        if numeric_summary["dropped_schedules"]:
            errors.append(
                "snapshot scheduler reported {} dropped schedule(s)".format(
                    numeric_summary["dropped_schedules"]
                )
            )

        expected_summary = {
            "pending_chunks": 0,
            "pending_sections": 0,
            "scheduled_loads": counts["scheduled_loads"],
            "duplicate_loads": counts["duplicate_load_callbacks"],
            "dropped_schedules": counts["dropped_schedules"],
            "scanned_sections": counts["chunk_snapshot_section_captured"],
            "scanned_blocks": counts["chunk_snapshot_section_captured"] * 4096,
            "completed_snapshots": counts["valid_completions"],
            "aborted_snapshots": counts["chunk_snapshot_aborted"],
            "partial_snapshots": partial_lifetime,
            "complete_chunks": final_counts["complete"],
            "partial_chunks": final_counts["partial"],
            "stale_chunks": final_counts["stale"],
            "covered_sections": covered_sections,
            "rejected_old_generations": counts["rejected_old_generations"],
            "rejected_out_of_order": counts["rejected_out_of_order"],
            "block_budget_stop_count": scan_stop_reasons["BLOCK_BUDGET"],
            "time_budget_stop_count": scan_stop_reasons["TIME_BUDGET"],
            "partial_section_resume_count": previous_resume_count,
            "max_scan_nanos": previous_reported_max,
        }
        if scan_ticks:
            expected_summary["average_scan_nanos"] = (
                sum(tick["elapsed_nanos"] for tick in scan_ticks) // len(scan_ticks)
            )
            summary_stop_reason = str(summary_details.get("scan_stop_reason", ""))
            last_matches = (
                numeric_summary["blocks_copied_this_tick"] == scan_ticks[-1]["blocks"]
                and numeric_summary["last_scan_nanos"] == scan_ticks[-1]["elapsed_nanos"]
                and summary_stop_reason == scan_ticks[-1]["stop_reason"]
            )
            idle_matches = (
                numeric_summary["blocks_copied_this_tick"] == 0
                and numeric_summary["last_scan_nanos"] == 0
                and summary_stop_reason == "NONE"
            )
            if not last_matches and not idle_matches:
                errors.append(
                    "snapshot pipeline summary mismatch for last scan tick/idle state"
                )
        else:
            expected_summary.update(
                {
                    "blocks_copied_this_tick": 0,
                    "last_scan_nanos": 0,
                    "max_scan_nanos": 0,
                    "average_scan_nanos": 0,
                }
            )
            if str(summary_details.get("scan_stop_reason", "")) != "NONE":
                errors.append(
                    "snapshot summary scan_stop_reason must be NONE without scan ticks"
                )
        mismatches = []
        for name, expected in expected_summary.items():
            actual = numeric_summary.get(name)
            if actual is not None and actual != expected:
                mismatches.append(
                    {"field": name, "expected": expected, "actual": actual}
                )
                errors.append(
                    "snapshot pipeline summary mismatch {}: expected {}, got {}".format(
                        name, expected, actual
                    )
                )
        reconciliation["summary_mismatches"] = mismatches

    rate_records = [record for _, record in snapshot_records] + summaries
    serialized_bytes = 0
    for record in rate_records:
        serializable = {
            key: value for key, value in record.items() if key != "_audit_line_number"
        }
        serialized_bytes += len(
            (json.dumps(serializable, separators=(",", ":")) + "\n").encode("utf-8")
        )
    snapshot_record_count = len(rate_records)
    records_per_second = (
        snapshot_record_count / duration_seconds if duration_seconds > 0.0 else 0.0
    )
    bytes_per_second = (
        serialized_bytes / duration_seconds if duration_seconds > 0.0 else 0.0
    )

    result.update(
        {
            "scheduled_generations": scheduled_generations,
            "generations": generation_report,
            "duplicate_section_captures": duplicate_sections,
            "obsolete_generation_events": obsolete_events,
            "obsolete_generation_mutations": obsolete_mutations,
            "final_coverage": {
                "partial": final_counts["partial"],
                "complete": final_counts["complete"],
                "stale": final_counts["stale"],
            },
            "reconciliation": reconciliation,
            "queue": queue,
            "scan": {
                "ticks": len(scan_ticks),
                "blocks": sum(tick["blocks"] for tick in scan_ticks),
                "max_elapsed_nanos": max(
                    (tick["elapsed_nanos"] for tick in scan_ticks), default=0
                ),
                "max_reported_nanos": previous_reported_max,
                "partial_section_resume_count": previous_resume_count,
                "stop_reasons": dict(sorted(scan_stop_reasons.items())),
            },
            "rate": {
                "records": snapshot_record_count,
                "records_per_second": records_per_second,
                "bytes": serialized_bytes,
                "bytes_per_second": bytes_per_second,
                "estimated_bytes_per_hour": bytes_per_second * 3600.0,
            },
        }
    )
    return result


def _snapshot_generation_label(chunk, generation):
    return "{} generation={}".format(_format_chunk(chunk), generation)


def _analyze_session(records, monotonic_values, file_bytes, errors):
    if not monotonic_values:
        duration = 0.0
    else:
        duration = max(0.0, (max(monotonic_values) - min(monotonic_values)) / 1_000_000_000.0)
    records_per_second = len(records) / duration if duration > 0.0 else 0.0
    bytes_per_second = file_bytes / duration if duration > 0.0 else 0.0
    if duration == 0.0:
        errors.append("session duration is zero; rate calculations are unavailable")
    return {
        "records": len(records),
        "bytes": file_bytes,
        "duration_seconds": duration,
        "records_per_second": records_per_second,
        "bytes_per_second": bytes_per_second,
        "estimated_bytes_per_hour": bytes_per_second * 3600.0,
        "wall_time_start": records[0].get("wall_time_utc"),
        "wall_time_end": records[-1].get("wall_time_utc"),
    }


def _analyze_event_rate(records, monotonic_values):
    if not monotonic_values:
        return {
            "bucket_seconds": 1,
            "average": 0.0,
            "peak": 0,
            "burst_threshold": 5,
            "series": [],
            "burst_periods": [],
        }
    start = min(monotonic_values)
    end = max(monotonic_values)
    bucket_count = int((end - start) // 1_000_000_000) + 1
    counts = collections.Counter()
    for record in records:
        monotonic = record.get("monotonic_nanos")
        if isinstance(monotonic, int) and not isinstance(monotonic, bool):
            bucket = int((monotonic - start) // 1_000_000_000)
            if bucket >= 0:
                counts[bucket] += 1
    values = [counts.get(index, 0) for index in range(bucket_count)]
    average = sum(values) / float(bucket_count)
    deviation = statistics.pstdev(values) if len(values) > 1 else 0.0
    burst_threshold = max(5, int(math.ceil(average + 2.0 * deviation)))
    series = [
        {"offset_seconds": index, "events": count}
        for index, count in enumerate(values)
    ]
    bursts = [
        item for item in series if item["events"] >= burst_threshold
    ]
    return {
        "bucket_seconds": 1,
        "average": average,
        "peak": max(values) if values else 0,
        "burst_threshold": burst_threshold,
        "series": series,
        "burst_periods": bursts,
    }


def _analyze_markers(
    records,
    marker_context,
    position_window_seconds,
    errors,
):
    markers = []
    if not records:
        return markers
    valid_monotonic = [
        record.get("monotonic_nanos")
        for record in records
        if isinstance(record.get("monotonic_nanos"), int)
        and not isinstance(record.get("monotonic_nanos"), bool)
    ]
    start = min(valid_monotonic) if valid_monotonic else 0
    for index, record in enumerate(records):
        if record.get("event_type") != "verification_marker":
            continue
        details = record.get("details", {})
        location = _record_location(record, index)
        label = details.get("label")
        if not isinstance(label, str) or not label:
            errors.append("{} marker has no label".format(location))
            label = "<missing>"
        passive_context = _marker_passive_context(details, location, errors)
        lower = max(0, index - marker_context)
        upper = min(len(records), index + marker_context + 1)
        context = []
        for context_index in range(lower, upper):
            context_record = records[context_index]
            context.append(
                {
                    "relation": "marker" if context_index == index else (
                        "before" if context_index < index else "after"
                    ),
                    "summary": _summarize_record(context_record, start),
                }
            )
        markers.append(
            {
                "label": label,
                "sequence": record.get("sequence"),
                "wall_time_utc": record.get("wall_time_utc"),
                "passive_context": passive_context,
                "context": context,
                "target_history_window_seconds": position_window_seconds,
                "target_history": _target_position_history(
                    records,
                    passive_context.get("target_position"),
                    record.get("monotonic_nanos"),
                    position_window_seconds,
                    start,
                ),
            }
        )
    return markers


def _marker_passive_context(details, location, errors):
    player_available = details.get("player_available") == "true"
    context = {
        "player_available": player_available,
        "crosshair_target_type": details.get("crosshair_target_type", "UNKNOWN"),
        "player_block_position": None,
        "player_precise_position": None,
        "player_yaw": None,
        "player_pitch": None,
        "held_item": None,
        "target_position": None,
        "target_block_state": None,
    }
    if player_available:
        dimension = _detail_int(details, "player_dimension", location, errors)
        block_x = _detail_int(details, "player_block_x", location, errors)
        block_y = _detail_int(details, "player_block_y", location, errors)
        block_z = _detail_int(details, "player_block_z", location, errors)
        if None not in (dimension, block_x, block_y, block_z):
            context["player_block_position"] = (dimension, block_x, block_y, block_z)
        precise_x = _detail_float(details, "player_x", location, errors)
        precise_y = _detail_float(details, "player_y", location, errors)
        precise_z = _detail_float(details, "player_z", location, errors)
        if None not in (precise_x, precise_y, precise_z):
            context["player_precise_position"] = {
                "x": precise_x,
                "y": precise_y,
                "z": precise_z,
            }
        context["player_yaw"] = _detail_float(details, "player_yaw", location, errors)
        context["player_pitch"] = _detail_float(details, "player_pitch", location, errors)

    if details.get("held_item_available") == "true":
        registry_name = details.get("held_item_registry_name")
        metadata = _detail_int(details, "held_item_metadata", location, errors)
        if not isinstance(registry_name, str) or not registry_name:
            errors.append("{} held item registry name is missing".format(location))
        else:
            context["held_item"] = {
                "registry_name": registry_name,
                "metadata": metadata,
            }

    if context["crosshair_target_type"] == "BLOCK":
        dimension = _detail_int(details, "target_block_dimension", location, errors)
        target_x = _detail_int(details, "target_block_x", location, errors)
        target_y = _detail_int(details, "target_block_y", location, errors)
        target_z = _detail_int(details, "target_block_z", location, errors)
        if None not in (dimension, target_x, target_y, target_z):
            context["target_position"] = (dimension, target_x, target_y, target_z)
        if details.get("target_block_available") == "true":
            registry_name = details.get("target_block_registry_name")
            block_id = _detail_int(details, "target_block_id", location, errors)
            metadata = _detail_int(details, "target_block_metadata", location, errors)
            if not isinstance(registry_name, str) or not registry_name:
                errors.append("{} targeted block registry name is missing".format(location))
            else:
                context["target_block_state"] = {
                    "registry_name": registry_name,
                    "block_id": block_id,
                    "metadata": metadata,
                }
    return context


def _target_position_history(
    records,
    target_position,
    marker_nanos,
    window_seconds,
    session_start_nanos,
):
    if target_position is None or not isinstance(marker_nanos, int):
        return []
    window_nanos = int(window_seconds * 1_000_000_000.0)
    history = []
    for record in records:
        component = record.get("component")
        if component not in {"observation", "block_overlay"}:
            continue
        monotonic = record.get("monotonic_nanos")
        if not isinstance(monotonic, int) or isinstance(monotonic, bool):
            continue
        delta_nanos = monotonic - marker_nanos
        if abs(delta_nanos) > window_nanos:
            continue
        details = record.get("details", {})
        record_position = _position_if_present(details)
        if record_position != tuple(target_position):
            continue
        history.append(
            {
                "relation": "before" if delta_nanos < 0 else "after",
                "delta_seconds": delta_nanos / 1_000_000_000.0,
                "event_type": record.get("event_type"),
                "observation_sequence": details.get("observation_sequence"),
                "summary": _summarize_record(record, session_start_nanos),
            }
        )
    return history


def format_text_report(report, verbose_snapshots=False):
    session = report["session"]
    overlay = report["overlay"]
    health = report["health"]
    observation_health = health["observation"]
    chunks = report["chunks"]
    snapshots = report["snapshots"]
    rate = report["event_rate"]
    lines = [
        "Bedwars Bot observation log audit",
        "Source: {}".format(report["source"]),
        "Result: {} ({} error(s), {} warning(s))".format(
            report["status"], len(report["errors"]), len(report["warnings"])
        ),
        "",
        "Session",
        "  records={} bytes={} duration={:.3f}s".format(
            session["records"], session["bytes"], session["duration_seconds"]
        ),
        "  records/s={:.3f} bytes/s={:.3f} estimated bytes/hour={:.0f}".format(
            session["records_per_second"],
            session["bytes_per_second"],
            session["estimated_bytes_per_hour"],
        ),
        "  wall time: {} -> {}".format(
            session["wall_time_start"], session["wall_time_end"]
        ),
        "",
        "Sparse overlay replay",
        "  added={added} updated={updated} refreshed={refreshed} duplicates={duplicates}".format(
            **overlay
        ),
        "  stale={stale} unloaded={unloaded} unavailable={unavailable} out_of_order={out_of_order}".format(
            **overlay
        ),
        "  final-known={final_known} final-stale={final_stale} final-size={final_overlay_size}".format(
            **overlay
        ),
        "",
        "Health",
        "  logger drops={} failure={}".format(
            health["logger_dropped_records"], health["logger_failure"] or "none"
        ),
        "  observation accepted={} processed={} dropped={} failures={} queue={}/{}".format(
            _display_none(observation_health["accepted_events"]),
            _display_none(observation_health["processed_events"]),
            _display_none(observation_health["dropped_events"]),
            _display_none(observation_health["failure_count"]),
            _display_none(observation_health["queue_depth"]),
            _display_none(observation_health["queue_capacity"]),
        ),
    ]
    if snapshots["present"]:
        snapshot_queue = snapshots["queue"]
        snapshot_rate = snapshots["rate"]
        snapshot_scan = snapshots["scan"]
        final_coverage = snapshots["final_coverage"]
        reconciliation = snapshots["reconciliation"]
        lines.extend(
            [
                "  snapshot accepted={} processed={} dropped={} failures={} queue={}/{}".format(
                    _display_none(snapshot_queue["accepted"]),
                    _display_none(snapshot_queue["processed"]),
                    _display_none(snapshot_queue["dropped"]),
                    _display_none(snapshot_queue["failures"]),
                    _display_none(snapshot_queue["final_depth"]),
                    _display_none(snapshot_queue["capacity"]),
                ),
                "",
                "Chunk snapshot validation",
                "  scheduled={} completed={} aborted={} schedule-dropped={} duplicate-loads={}".format(
                    reconciliation.get("scheduled", 0),
                    reconciliation.get("completed", 0),
                    reconciliation.get("aborted", 0),
                    reconciliation.get("schedule_dropped", 0),
                    reconciliation.get("duplicate_load_callbacks", 0),
                ),
                "  final coverage: partial={} complete={} stale={}".format(
                    final_coverage["partial"],
                    final_coverage["complete"],
                    final_coverage["stale"],
                ),
                "  obsolete-generation events={} mutations={}".format(
                    len(snapshots["obsolete_generation_events"]),
                    snapshots["obsolete_generation_mutations"],
                ),
                "  scan ticks={} blocks={} max={}ns reported-max={}ns resumes={}".format(
                    snapshot_scan["ticks"],
                    snapshot_scan["blocks"],
                    snapshot_scan["max_elapsed_nanos"],
                    snapshot_scan["max_reported_nanos"],
                    snapshot_scan["partial_section_resume_count"],
                ),
                "  scan budget stops: {}".format(
                    ", ".join(
                        "{}={}".format(reason, count)
                        for reason, count in sorted(snapshot_scan["stop_reasons"].items())
                    ) or "none"
                ),
                "  snapshot records={} records/s={:.3f} bytes/s={:.3f} estimated bytes/hour={:.0f}".format(
                    snapshot_rate["records"],
                    snapshot_rate["records_per_second"],
                    snapshot_rate["bytes_per_second"],
                    snapshot_rate["estimated_bytes_per_hour"],
                ),
            ]
        )
        scheduled_generations = snapshots["scheduled_generations"]
        shown_schedules = _snapshot_display_items(
            scheduled_generations,
            verbose_snapshots,
            lambda item: item["outcome"] not in {"SCHEDULED", "DUPLICATE"},
        )
        lines.append(_snapshot_display_heading(
            "scheduled generations",
            len(shown_schedules),
            len(scheduled_generations),
            verbose_snapshots,
        ))
        if scheduled_generations:
            for scheduled in shown_schedules:
                lines.append(
                    "    {} generation={} outcome={}".format(
                        scheduled["chunk"],
                        scheduled["generation"],
                        scheduled["outcome"],
                    )
                )
        else:
            lines.append("    none")
        generations = snapshots["generations"]
        shown_generations = _snapshot_display_items(
            generations,
            verbose_snapshots,
            lambda item: item["invalid_completion"]
            or item["final_status"] == "PARTIAL",
        )
        lines.append(_snapshot_display_heading(
            "section captures by generation",
            len(shown_generations),
            len(generations),
            verbose_snapshots,
        ))
        if generations:
            for generation in shown_generations:
                lines.append(
                    "    {} generation={} sections={} events={} complete={} abort={} unload={} dimension-unload={} final={}".format(
                        generation["chunk"],
                        generation["generation"],
                        generation["section_indices"],
                        generation["section_capture_events"],
                        str(generation["completed"]).lower(),
                        str(generation["aborted"]).lower(),
                        str(generation["unloaded"]).lower(),
                        str(generation["dimension_unloaded"]).lower(),
                        generation["final_status"],
                    )
                )
        else:
            lines.append("    none")
        if snapshots["duplicate_section_captures"]:
            lines.append("  duplicate section captures:")
            for duplicate in snapshots["duplicate_section_captures"]:
                lines.append(
                    "    {} generation={} section={} at {}".format(
                        duplicate["chunk"],
                        duplicate["generation"],
                        duplicate["section_index"],
                        duplicate["location"],
                    )
                )
        if snapshots["obsolete_generation_events"]:
            lines.append("  obsolete/unscheduled generation events:")
            for event in snapshots["obsolete_generation_events"]:
                lines.append(
                    "    {} {} generation={} current={}".format(
                        event["location"],
                        event["event_type"],
                        event["generation"],
                        event["current_generation"],
                    )
                )

    lines.extend(["", "Most frequently updated positions"])
    lines.extend(_format_ranked(report["positions"], "position", "observations"))
    lines.extend(["", "Most frequently observed block types"])
    lines.extend(_format_ranked(report["block_types"], "block", "observations"))
    lines.extend(["", "Repeated position/state observations"])
    if report["duplicate_observations"]:
        for item in report["duplicate_observations"]:
            lines.append(
                "  {} {}: {} observations ({} repeats)".format(
                    item["position"], item["state"], item["observations"], item["repeats"]
                )
            )
    else:
        lines.append("  none")

    lines.extend(
        [
            "",
            "Chunk load/unload balance",
            "  loads={} unloads={} balance={} final-loaded={}".format(
                chunks["loads"],
                chunks["unloads"],
                chunks["balance"],
                chunks["final_loaded_chunks"],
            ),
        ]
    )
    if chunks["dimension_unload_events"]:
        lines.append(
            "  dimension-unload cleanup: events={} loaded chunks cleared={} cleanup_occurred={}".format(
                chunks["dimension_unload_events"],
                chunks["loaded_chunks_cleared_by_dimension_unload"],
                str(chunks["cleanup_through_dimension_unload"]).lower(),
            )
        )
    if chunks["unbalanced_chunks"]:
        for item in chunks["unbalanced_chunks"]:
            lines.append(
                "  {} loads={} unloads={} balance={:+d}".format(
                    item["chunk"], item["loads"], item["unloads"], item["balance"]
                )
            )
    else:
        lines.append("  all observed chunks balanced")

    lines.extend(
        [
            "",
            "Events per second",
            "  average={:.3f} peak={} burst threshold={} (max(5, mean + 2*population stdev))".format(
                rate["average"], rate["peak"], rate["burst_threshold"]
            ),
            "  " + _format_rate_series(rate["series"]),
            "  bursts: " + (
                ", ".join(
                    "+{}s={} events".format(item["offset_seconds"], item["events"])
                    for item in rate["burst_periods"]
                )
                if rate["burst_periods"]
                else "none"
            ),
            "",
            "Verification markers",
        ]
    )
    if not report["markers"]:
        lines.append("  none")
    for marker in report["markers"]:
        lines.append(
            "  marker sequence={} label={!r} at {}".format(
                marker["sequence"], marker["label"], marker["wall_time_utc"]
            )
        )
        passive = marker["passive_context"]
        if (
            passive["player_available"]
            and passive["player_block_position"] is not None
            and passive["player_precise_position"] is not None
            and passive["player_yaw"] is not None
            and passive["player_pitch"] is not None
        ):
            player_position = passive["player_block_position"]
            precise = passive["player_precise_position"]
            lines.append(
                "    player={} precise=({:.6f},{:.6f},{:.6f}) yaw={:.3f} pitch={:.3f}".format(
                    _format_position(tuple(player_position)),
                    precise["x"],
                    precise["y"],
                    precise["z"],
                    passive["player_yaw"],
                    passive["player_pitch"],
                )
            )
        elif passive["player_available"]:
            lines.append("    player=available (context incomplete)")
        else:
            lines.append("    player=unavailable")
        if passive["held_item"] is not None:
            held_item = passive["held_item"]
            lines.append(
                "    held={}#{}".format(
                    held_item["registry_name"], held_item["metadata"]
                )
            )
        else:
            lines.append("    held=none/unavailable")
        target_text = "    crosshair={}".format(passive["crosshair_target_type"])
        if passive["target_position"] is not None:
            target_text += " target={}".format(
                _format_position(tuple(passive["target_position"]))
            )
        if passive["target_block_state"] is not None:
            target_state = passive["target_block_state"]
            target_text += " state={}#{}".format(
                target_state["registry_name"], target_state["metadata"]
            )
        lines.append(target_text)
        for context in marker["context"]:
            lines.append(
                "    {:>6}: {}".format(context["relation"], context["summary"])
            )
        lines.append(
            "    target history (+/-{:.3f}s):".format(
                marker["target_history_window_seconds"]
            )
        )
        if marker["target_history"]:
            for history in marker["target_history"]:
                lines.append(
                    "      {:>6} {:+.3f}s: {}".format(
                        history["relation"],
                        history["delta_seconds"],
                        history["summary"],
                    )
                )
        else:
            lines.append("      none")

    if report["warnings"]:
        lines.extend(["", "Warnings"])
        lines.extend("  - " + warning for warning in report["warnings"])
    if report["errors"]:
        lines.extend(["", "Invariant failures"])
        lines.extend("  - " + error for error in report["errors"])
    return "\n".join(lines) + "\n"


def write_json_report(path, report):
    try:
        with open(path, "w", encoding="utf-8") as destination:
            json.dump(report, destination, indent=2, sort_keys=True)
            destination.write("\n")
    except OSError as error:
        raise AuditInputError("could not write JSON report: {}".format(error))


def _verify_strict_order(name, values, errors):
    previous = None
    previous_location = None
    for value, location in values:
        if previous is not None and value <= previous:
            errors.append(
                "{} is not strictly increasing: {} at {} follows {} at {}".format(
                    name, value, location, previous, previous_location
                )
            )
        previous = value
        previous_location = location


def _required_int(mapping, key, location, errors):
    value = mapping.get(key)
    if not isinstance(value, int) or isinstance(value, bool):
        errors.append("{} {} must be an integer".format(location, key))
        return None
    return value


def _detail_int(details, key, location, errors):
    value = details.get(key)
    try:
        if isinstance(value, bool) or value is None:
            raise ValueError()
        return int(value)
    except (TypeError, ValueError):
        errors.append("{} details.{} must be an integer".format(location, key))
        return None


def _detail_float(details, key, location, errors):
    value = details.get(key)
    try:
        if isinstance(value, bool) or value is None:
            raise ValueError()
        return float(value)
    except (TypeError, ValueError):
        errors.append("{} details.{} must be a number".format(location, key))
        return None


def _position_from_details(details, location, errors):
    dimension = _detail_int(details, "dimension", location, errors)
    x = _detail_int(details, "x", location, errors)
    y = _detail_int(details, "y", location, errors)
    z = _detail_int(details, "z", location, errors)
    if None in (dimension, x, y, z):
        return None
    return dimension, x, y, z


def _position_if_present(details):
    try:
        return (
            int(details["dimension"]),
            int(details["x"]),
            int(details["y"]),
            int(details["z"]),
        )
    except (KeyError, TypeError, ValueError):
        return None


def _chunk_from_details(details, location, errors):
    dimension = _detail_int(details, "dimension", location, errors)
    chunk_x = _detail_int(details, "chunk_x", location, errors)
    chunk_z = _detail_int(details, "chunk_z", location, errors)
    if None in (dimension, chunk_x, chunk_z):
        return None
    return dimension, chunk_x, chunk_z


def _current_state_from_details(details):
    registry_name = details.get("current_block_registry_name")
    block_id = details.get("current_block_id")
    metadata = details.get("current_block_metadata")
    if registry_name is None:
        registry_name = details.get("block_registry_name")
        block_id = details.get("block_id")
        metadata = details.get("block_metadata")
    if registry_name is None:
        return None
    return {
        "registry_name": str(registry_name),
        "block_id": str(block_id),
        "metadata": str(metadata),
    }


def _stale_chunk(overlay, chunk):
    affected = 0
    for position, value in overlay.items():
        if (
            position[0] == chunk[0]
            and position[1] // 16 == chunk[1]
            and position[3] // 16 == chunk[2]
            and value.get("availability") == "KNOWN"
        ):
            value["availability"] = "STALE"
            affected += 1
    return affected


def _stale_dimension(overlay, dimension):
    affected = 0
    for position, value in overlay.items():
        if position[0] == dimension and value.get("availability") == "KNOWN":
            value["availability"] = "STALE"
            affected += 1
    return affected


def _record_location(record, fallback_index):
    line = record.get("_audit_line_number")
    return "line {}".format(line if line is not None else fallback_index + 1)


def _format_position(position):
    return "d={} {},{},{}".format(position[0], position[1], position[2], position[3])


def _format_chunk(chunk):
    return "d={} chunk={},{}".format(chunk[0], chunk[1], chunk[2])


def _summarize_record(record, start_nanos):
    sequence = record.get("sequence", "?")
    monotonic = record.get("monotonic_nanos")
    offset = 0.0
    if isinstance(monotonic, int) and not isinstance(monotonic, bool):
        offset = (monotonic - start_nanos) / 1_000_000_000.0
    details = record.get("details", {})
    suffix = ""
    if all(key in details for key in ("dimension", "x", "y", "z")):
        suffix = " d={} {},{},{}".format(
            details["dimension"], details["x"], details["y"], details["z"]
        )
    state = details.get("block_registry_name") or details.get("current_block_registry_name")
    metadata = details.get("block_metadata") or details.get("current_block_metadata")
    if state is not None:
        suffix += " state={}#{}".format(state, metadata)
    if details.get("outcome") is not None:
        suffix += " outcome={}".format(details["outcome"])
    return "seq={} +{:.3f}s {}/{}{}".format(
        sequence,
        offset,
        record.get("component", "?"),
        record.get("event_type", "?"),
        suffix,
    )


def _format_ranked(items, name_key, count_key):
    if not items:
        return ["  none"]
    return [
        "  {}: {}".format(item[name_key], item[count_key])
        for item in items
    ]


def _format_rate_series(series):
    limit = 60
    shown = series[:limit]
    text = ", ".join(
        "+{}s={}".format(item["offset_seconds"], item["events"])
        for item in shown
    )
    if len(series) > limit:
        text += ", ... ({} total buckets)".format(len(series))
    return text or "no buckets"


def _snapshot_display_items(items, verbose, is_problematic):
    if verbose or len(items) <= DEFAULT_SNAPSHOT_GENERATION_DISPLAY_LIMIT:
        return items
    limit = DEFAULT_SNAPSHOT_GENERATION_DISPLAY_LIMIT
    selected = {0, len(items) // 2, len(items) - 1}
    for index, item in enumerate(items):
        if is_problematic(item):
            selected.add(index)
            if len(selected) >= limit:
                break
    if len(selected) < limit:
        for index in range(len(items)):
            selected.add(index)
            if len(selected) >= limit:
                break
    return [items[index] for index in sorted(selected)[:limit]]


def _snapshot_display_heading(label, shown, total, verbose):
    suffix = ""
    if not verbose and shown < total:
        suffix = " (use --verbose-snapshots for all)"
    return "  {}: showing {} of {}{}".format(label, shown, total, suffix)


def _display_none(value):
    return "unknown" if value is None else str(value)


def _positive_int(value):
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def _nonnegative_int(value):
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must not be negative")
    return parsed


def _positive_float(value):
    parsed = float(value)
    if not math.isfinite(parsed) or parsed <= 0.0:
        raise argparse.ArgumentTypeError("must be a positive finite number")
    return parsed


def build_argument_parser():
    parser = argparse.ArgumentParser(
        description="Audit a Bedwars Bot schema-v1 observation JSONL session."
    )
    parser.add_argument("session", help="path to the JSONL session")
    parser.add_argument(
        "--json-report",
        metavar="PATH",
        help="also write the machine-readable report to PATH",
    )
    parser.add_argument(
        "--top",
        type=_positive_int,
        default=DEFAULT_TOP_COUNT,
        help="number of top positions, blocks, duplicates, and chunks to show",
    )
    parser.add_argument(
        "--marker-context",
        type=_nonnegative_int,
        default=DEFAULT_MARKER_CONTEXT,
        help="records shown before and after each verification marker",
    )
    parser.add_argument(
        "--marker-position-window",
        type=_positive_float,
        default=DEFAULT_MARKER_POSITION_WINDOW_SECONDS,
        metavar="SECONDS",
        help="time before/after a marker used for targeted-position history",
    )
    parser.add_argument(
        "--verbose-snapshots",
        action="store_true",
        help="show every scheduled generation and per-generation section record",
    )
    return parser


def main(arguments=None):
    parser = build_argument_parser()
    options = parser.parse_args(arguments)
    try:
        report = audit_file(
            options.session,
            top_count=options.top,
            marker_context=options.marker_context,
            marker_position_window_seconds=options.marker_position_window,
        )
        sys.stdout.write(format_text_report(
            report,
            verbose_snapshots=options.verbose_snapshots,
        ))
        if options.json_report:
            write_json_report(options.json_report, report)
    except AuditInputError as error:
        sys.stderr.write("audit error: {}\n".format(error))
        return 2
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
