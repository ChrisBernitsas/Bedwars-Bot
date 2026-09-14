# Bedwars Bot

A research project for building a high-skill autonomous agent for Minecraft 1.8.9 Hypixel Bedwars.

The project takes inspiration from systems such as AlphaStar, but uses a hierarchical hybrid architecture rather than one end-to-end model. Planned components include:

- A visible Forge 1.8.9 client
- Passive client-state and packet observation
- Dynamic map and world reconstruction
- Human-valid keyboard, mouse, and GUI controls
- Navigation, shopping, inventory management, bridging, PvP, and clutching skills
- Tactical and strategic planning
- Imitation learning, targeted scenario training, and eventual self-play
- A real-time HUD and replay system for explaining and debugging decisions

Development currently includes the Phase 1 client foundation, the Section 22
Step 3 passive block-event prototype, and bounded passive initial snapshots of
naturally loaded client chunks. Autonomous decision-making, gameplay skills,
packet hooks, navigation, and machine-learning systems have not been
implemented.

See [`AGENTS.md`](./AGENTS.md) for the complete architecture, constraints, and development roadmap.

## Phase 0 toolchain

- Minecraft: 1.8.9
- Forge: 11.15.1.2318
- ForgeGradle: 2.1.6
- MCP mappings: stable_22
- Gradle wrapper: 2.14.1
- Java: full JDK 8, compiling Java 8 bytecode

Forge 11.15.1.2318 is the latest and recommended Forge build listed for
Minecraft 1.8.9. The `stable_22` MCP mappings target 1.8.9 directly.
ForgeGradle 2.1.6 is pinned to a release instead of using the
mutable `2.1-SNAPSHOT` from the historical MDK. Gradle 2.14.1 is the final
bug-fix release in the Gradle 2.14 line used by ForgeGradle 2.1. The build must
run on JDK 8; a modern system Gradle or JDK is not a compatible substitute.

### macOS setup

Install a full JDK 8 and point `JAVA_HOME` at that exact JDK. Verify both the
runtime and compiler before invoking Gradle:

```sh
export JAVA_HOME=/path/to/a/full/jdk8/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
./gradlew --version
```

On this development machine, the usable ARM JDK is currently:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

Do not rely blindly on `/usr/libexec/java_home -v 1.8`: an obsolete browser
plug-in JRE can be selected even though it does not include `javac`.

Apple Silicon can compile the project with an ARM JDK 8, but Minecraft 1.8.9's
bundled LWJGL 2 native libraries are x86 binaries. Launching the development
client therefore requires Rosetta 2 and an x86_64 JDK 8. Select that JDK in
`JAVA_HOME` before running `runClient`; an ARM JDK fails while loading
`liblwjgl.dylib`.

### Build and test

With JDK 8 selected:

```sh
./gradlew --no-daemon clean test build
```

The distributable mod is written to `build/libs/bedwarsbot-0.4.0.jar`. JUnit is
used only for deterministic Minecraft-independent state, safety, queue, and
serialization tests.

### Development client smoke test

Launch the Forge development client with:

```sh
./gradlew --no-daemon runClient
```

At the Minecraft main menu, confirm that **Bedwars Bot** appears in the Mods
list. Open a local world, enter `/bedwarsbotsmoke`, and verify that chat shows:

```text
[Bedwars Bot] Phase 0 loaded.
```

The command is handled locally by Forge and has no gameplay side effects.

Phase 0 has been verified in a real Forge 1.8.9 development client: the mod
appeared in the Mods list, the smoke command returned the expected message,
and the client exited cleanly.

## Phase 1 client foundation

The client always starts in `DISABLED`. The available modes are:

- `DISABLED`: clears proposals and releases every bot-owned movement key.
- `OBSERVE`: logging and HUD only; proposed input cannot execute.
- `SHADOW`: displays and logs proposed input; proposed input cannot execute.
- `ASSIST`: permits explicitly requested smoke-test input after safety checks.
- `AUTONOMOUS`: permits gated input, but no autonomous decision-maker exists.

Active input is additionally locked to an open single-player world with no GUI
screen open. Entering any unsafe context—GUI open, missing world/player, or a
non-local-single-player environment—immediately releases bot-owned inputs and
clears the proposal. Returning to a safe context cannot resume it; another
explicit input command is required. Multiplayer input is blocked in this phase.

The emergency-disable binding defaults to F10 and can be changed in Minecraft's
Controls menu under **Bedwars Bot**. It returns to `DISABLED`, clears the
proposal, and releases all bot-owned movement bindings immediately.

### Local verification commands

These client-local commands exist only to exercise the foundation in a local
test world:

```text
/bedwarsbot status
/bedwarsbot mode <disabled|observe|shadow|assist|autonomous>
/bedwarsbot input <clear|forward|backward|left|right|jump|sneak|sprint>
/bedwarsbot input hotbar <1-9>
/bedwarsbot marker <label>
```

Each mode transition clears proposed and active input. Movement proposals are
persistent until cleared, blocked by context, or stopped with F10. No attack,
use-item, rotation, placement, GUI, or packet action exists.

The developer-only `marker` command writes a structured `verification_marker`
record to the current session log. On the Minecraft client thread it copies the
label, current read-only mode/proposed/active values, player dimension and pose,
held item, normal crosshair target type, and the targeted block position/state
when a loaded block is targeted. Only immutable primitives and strings enter
the logger; the command does not change control or gameplay state.

### Debug HUD and session logs

The HUD displays:

- Current mode and safety-gate status.
- Proposed and active input frames.
- Control-loop duration in microseconds.
- Logger queue depth, capacity, dropped-record count, and writer failure.

Session logs are schema-v1 JSONL files under `run/bedwarsbot/logs/`. Producers
use nonblocking queue insertion into a fixed 1,024-record queue. If it fills,
the newest record is dropped and the HUD counter increases. A daemon writer
performs file I/O, and a shutdown hook drains and closes the log on clean exit.

### Phase 1 real-client checklist

Use a local single-player world only:

1. Launch with `./gradlew --no-daemon runClient` and confirm the HUD starts in
   `DISABLED` with `proposed=none` and `active=none`.
2. Run `/bedwarsbot mode shadow`, then `/bedwarsbot input forward`. Confirm the
   HUD shows the proposal but the player does not move and active input is none.
3. Run `/bedwarsbot mode assist`; confirm the mode transition clears the prior
   proposal. Then run `/bedwarsbot input forward` and confirm ordinary forward
   movement and matching proposed/active HUD values.
4. While moving, press F10. Confirm movement stops in the same tick, the mode is
   `DISABLED`, and both HUD input values return to none.
5. Return to `ASSIST`, run `/bedwarsbot input hotbar 2`, and confirm selection
   through the normal hotbar binding. Run `/bedwarsbot input clear` afterward.
6. With forward active, open a GUI. Confirm active and proposed input both
   become none. Close the GUI and confirm movement does not resume. Submit a new
   `/bedwarsbot input forward` command and confirm only that new proposal moves.
7. Exit the client normally. Inspect the newest JSONL file and confirm schema
   version 1, increasing sequence numbers, mode/input/safety/override events,
   an `unsafe_context_cleared` event with its reason, a final `session_end`,
   zero unexpected drops, and no writer failure.

## Section 22 Step 3 passive block-event prototype

The prototype observes only data already present in the visible client:

- Client-side Forge chunk load and unload events identify chunks that naturally
  enter or leave the client.
- A read-only `IWorldAccess` copies specific `markBlockForUpdate` notifications
  into immutable block observations.
- The copied state contains dimension, position, block registry name, numeric
  ID, metadata, client/world ticks, a monotonic sequence, and capture time. No
  Minecraft object crosses into the worker thread.
- A single background worker applies observations to a sparse overlay and
  submits schema-versioned observation and overlay records to the existing
  asynchronous JSONL logger.

The observation queue holds 4,096 events. Producers use a nonblocking offer and
drop the newest event if the queue is full. The HUD shows current loaded chunks,
observed load/unload/block counts, known and stale overlay sizes, duplicates,
queue depth/capacity, dropped/processed counts, processing time, failures, and
the newest observed changed block within 24 blocks of the player.

### Unknown and stale semantics

- A position is `UNKNOWN` until a specific block-state notification is seen.
- A specifically observed position is `KNOWN` while current.
- A chunk or dimension unload retains its sparse entries as last-known values
  but marks them `STALE`.
- Loading the chunk again does not refresh old values. A new specific block
  notification is required before that position becomes `KNOWN` again.
- Duplicate states are retained once and out-of-order events cannot overwrite a
  newer observation.

This is not a chunk snapshot or canonical map. Forge 1.8.9 does not expose a
general client block-change event, so the prototype uses render/world-access
notifications. Those notifications can be duplicated and do not prove packet
provenance. Full chunk-data updates and range-render notifications are not
expanded into blocks; doing so would require a scan. Consequently, loaded
chunks can legitimately contain no overlay entries, and missing positions stay
unknown.

The prototype never sends packets, requests chunks, changes view distance, or
enumerates the world. Observation failures are isolated from the control
foundation and cannot apply inputs or prevent manual release.

### Step 3 real-client checklist

Use a local single-player world and keep control `DISABLED` throughout:

1. Launch with `./gradlew --no-daemon runClient`. Confirm the Mods list reports
   Bedwars Bot `0.4.0`, then enter a local world.
2. Confirm the HUD still shows `mode=DISABLED`, `proposed=none`, and
   `active=none`. Confirm observed chunk loads increase naturally, the current
   loaded-chunk count is nonzero, and the overlay does not suddenly contain an
   entire chunk or world.
3. Manually place one distinctive block within 24 blocks. Confirm the block
   observation count increases, the sparse overlay gains or updates one entry,
   and the nearby line shows its dimension, position, registry name/metadata,
   and `KNOWN` status. Break it manually and confirm a later air observation at
   the same position without an extra overlay entry.
4. Exit to the title screen, then re-enter the world. Confirm old observed
   entries became `STALE`; natural chunk loads alone do not make them `KNOWN`.
   Manually change the same position again and confirm that explicit new event
   refreshes it to `KNOWN`.
5. Confirm the observation queue normally returns to `0/4096`, dropped and
   failure counts remain zero, processed count increases, and timing remains
   nonblocking. Also confirm the existing log queue has no unexpected drops or
   writer failure.
6. Recheck the Phase 1 emergency path: issue an allowed local movement proposal
   in `ASSIST`, press F10, and confirm the mode becomes `DISABLED` and all input
   releases immediately. Observation should continue without affecting this.
7. Exit cleanly. Inspect the newest file in `run/bedwarsbot/logs/` and confirm
   outer `schema_version: 1`, detail `observation_schema_version: "1"`, ordered
   `observation_sequence` values, `chunk_*_observed` and
   `block_state_observed` records, matching `block_overlay` outcomes, and a
   final `session_end`.

### Developer observation-log audit

Place verification markers immediately before and after a manual test action:

```text
/bedwarsbot marker before manual wool placement
/bedwarsbot marker after manual wool removal
```

The labels may contain spaces and are limited to 80 characters. A marker only
queues a log record; it never changes mode, proposals, active input, safety
state, or observation collection.

After exiting the client cleanly, audit the resulting session with Python 3.
The tool uses only the Python standard library:

```sh
python3 tools/audit_observation_log.py run/bedwarsbot/logs/<session>.jsonl
```

Optionally write the same results as machine-readable JSON while retaining the
text report on standard output:

```sh
python3 tools/audit_observation_log.py \
  run/bedwarsbot/logs/<session>.jsonl \
  --json-report build/observation-audit.json
```

Targeted-position history defaults to five seconds before and after each
marker. Change that window when a test needs a tighter or wider view:

```sh
python3 tools/audit_observation_log.py \
  run/bedwarsbot/logs/<session>.jsonl \
  --marker-position-window 2.5
```

The audit validates schema and sequence ordering, pairs every processed block
observation with one overlay outcome, replays the sparse overlay, reports
collection/logger health, rates, burst periods, duplicates, block and position
frequency, chunk balance, copied marker context, surrounding records, and the
observation/overlay history for each marker's targeted position. It exits
with status 1 for an invariant failure and status 2 for an unreadable or
malformed input file.

Event rates use one-second monotonic-time buckets. A burst bucket contains at
least five records and meets or exceeds `mean + 2 * population standard
deviations`. A clean shutdown adds `observation_pipeline_summary` immediately
before `session_end`; missing summaries, nonzero drops/failures, nonempty final
queue depth, or observation/overlay count mismatches fail the audit.

Chunk balance is reconstructed from overlay lifecycle outcomes. If a world or
dimension unload clears the final loaded-chunk set, the report explicitly
shows the number cleared and does not warn merely because individual chunk
unload callbacks were absent. A nonempty reconstructed final loaded set still
produces a warning.

Run the standard-library fixture tests and audit the representative fixture:

```sh
python3 -m unittest discover -s tools/tests -p 'test_*.py'
python3 tools/audit_observation_log.py \
  tools/tests/fixtures/observation_session.jsonl
```

## Phase 2 passive initial chunk snapshots

Each client-side `ChunkEvent.Load` schedules only the exact chunk object supplied
by that natural Forge callback. The collector never calls a chunk provider,
looks up neighboring chunks, enumerates all loaded chunks, requests data, or
changes view distance.

Snapshot work is incremental and globally bounded. The Minecraft client thread
copies at most 4,096 block states across all snapshot work during an END tick,
and checks a 1.5 ms deadline before each copied state. Capture can pause within
a 16×16×16 section and resume on a later tick using a generation, section, and
within-section cursor. A partial section remains private to the client-thread
collector and is discarded if its generation unloads; only a completed 4,096-
state primitive array is published to the worker. Mutable Minecraft objects
never leave the client thread.

The 1.5 ms deadline is cooperative rather than a hard real-time guarantee: a
JVM safepoint, garbage collection, or OS descheduling pause can make the measured
elapsed interval exceed the deadline between clock checks. The measured maximum
is retained in the HUD and structured log so such outliers remain visible.

The pending scan scheduler is FIFO and bounded to 1,024 chunks. Its entries hold
only generation/progress bookkeeping and references to chunks already loaded by
Minecraft; chunk contents are not copied while waiting. Unload, dimension-unload,
completion, and shutdown paths remove pending references. The separate
snapshot worker queue is bounded to 512 events. Both use nonblocking insertion;
the newest work is rejected on overflow and metrics make the resulting partial
or unsnapshotted coverage explicit. The existing 4,096-event block-observation
queue and 1,024-record logger queue remain unchanged.

### Coverage and ordering semantics

- A successfully copied null section is full known-air coverage, represented by
  uniform state ID `0`. It is distinct from a section that has not been copied.
- Captured sections are `KNOWN` while loaded. Uncaptured positions remain
  `UNKNOWN`, including positions in a partial snapshot.
- All 16 sections must reach the worker before a generation can become
  `COMPLETE`. An abort or malformed completion remains `PARTIAL`.
- Unload retains captured values as `STALE`. Reload creates a new generation;
  late sections from an older generation are rejected.
- Section captures and specific block changes share one client-thread capture
  sequence. The chunk store retains per-position block overrides, so the newest
  client-thread observation wins regardless of worker timing.
- This store is separate from the sparse dynamic block-change overlay. Snapshot
  initialization does not manufacture overlay changes or alter its behavior.

The snapshot worker writes schema-v1 `chunk_snapshot_scheduled`,
`chunk_snapshot_section_captured`, `chunk_snapshot_completed`,
`chunk_snapshot_aborted`, unload, per-active-tick scan metrics, and final
pipeline-summary records. Dense section state IDs use background-generated
`u16le_base64_v1`; uniform sections use a single state value. No large
serialization occurs on the client thread.

The HUD adds pending chunks/sections, lifetime completion/abort/partial counts,
current complete/partial/stale store counts, covered and scanned sections,
blocks copied on the latest active tick, scan duration and budget-stop reason,
maximum scan time, partial-section resumes, and snapshot queue
depth/drop/failure metrics.

The observation log auditor also validates snapshot generations, exact section
coverage before completion, unload/reload handling, obsolete-generation
mutations, scan budgets, final queue health, and the pipeline summary. Its text
and optional JSON reports include per-generation section indices, final
partial/complete/stale coverage, scan-stop counts, and snapshot throughput.
Per-generation terminal details are bounded by default; pass
`--verbose-snapshots` to print every generation. The optional JSON report always
contains the complete generation data.

### Initial-snapshot local-client checklist

Use a local single-player world and keep the bot `DISABLED`:

1. Launch `./gradlew --no-daemon runClient`, confirm version `0.4.0`, and enter a
   fresh local world. Confirm `proposed=none` and `active=none` throughout.
2. Watch natural chunk loads populate `snap pending`; confirm scanned sections
   rise by no more than one and scanned blocks by no more than 4,096 per client
   tick. Pending work should drain without enumerating or reloading the world.
3. Confirm completed snapshots rise only after 16 sections, queue depth normally
   returns toward zero, and snapshot drops/failures remain zero.
4. While a backlog exists, leave the world. Confirm unfinished generations
   increment aborted/partial counts and captured coverage becomes stale rather
   than complete.
5. Re-enter the world. Confirm new natural loads get new generations and new
   captures replace old generations; old stale data must not become known merely
   because a callback from the previous world finishes.
6. Place and break a block while its chunk is being or has been captured. Confirm
   the existing nearby-change HUD and sparse overlay still update normally.
   After the queue drains, verify the latest specific block observation is not
   overwritten by an older section result.
7. Press F10 during an allowed local movement smoke test and reconfirm immediate
   `DISABLED` mode and input release; passive snapshot activity must continue
   without delaying or changing control safety.
8. Exit cleanly and inspect the newest JSONL session. Confirm ordered lifecycle
   records, 16 section records before each successful completion, explicit abort
   reasons for interrupted captures, a `chunk_snapshot_pipeline_summary` with
   zero queue depth/drops/failures, the existing observation summary, and final
   `session_end`.

## Phase 2C canonical map foundation

The canonical map package describes normal static map geometry and semantic
landmarks without depending on Forge or Minecraft runtime classes. It is an
offline data foundation only: it is not yet connected to map detection, passive
block observations, or naturally loaded chunk snapshots.

Canonical maps live under `maps/<map-id>/<revision>/`. Each directory contains:

```text
manifest.json
landmarks.json
geometry/chunk_<x>_<z>.json
```

The schema is versioned at the map, geometry, and landmark levels. The manifest
records map identity and revision, Bedwars mode/family, Minecraft version,
dimension, inclusive geometry bounds, provenance, nullable acquisition/source
fields, explicit validation status, and an expected geometry SHA-256.

Geometry is organized by chunk and 16×16×16 section. Each section normalizes its
palette into deterministic `(block_id, metadata)` order and has explicit coverage:

- `UNKNOWN` contains no known positions.
- `PARTIAL` contains between 1 and 4,095 specifically known positions.
- `COMPLETE` contains all 4,096 positions.

A known position containing state `(0,0)` is known air. An unknown position has
no palette index. These states are distinct in both the APIs and serialized
coverage data. Chunk or section presence never implies complete knowledge.
Complete uniform sections use one palette index; other complete sections use
packed palette indices. Partial sections use a compact Base64 bitset plus
packed indices only for covered positions.

Landmarks are stored separately from blocks. The model supports team spawns,
beds, forges, item and upgrade shops, team and Ender Chests, diamond and emerald
generators, and general navigation landmarks. Team and facing are nullable;
confidence, provenance, and validation status remain explicit.

Loading is strict. Unsupported versions, malformed or truncated JSON/Base64,
unsafe referenced paths, invalid coverage/palettes/states, duplicate identities,
out-of-bounds data, and hash mismatches are rejected with structured validation
issues. Filesystem enumeration is never used to discover geometry; the sorted
manifest list is authoritative. Schema-v1 input is bounded to 4 MiB per JSON
file, 64 MiB per map, 4,096 chunk files, 16 sections per chunk, 4,096 palette
entries per section, and 16,384 landmarks. JSON parsing also has explicit nesting,
container, and value-count limits. Referenced paths must be normalized, remain
inside the map directory, and contain no symbolic-link components.

The geometry SHA-256 includes the schema-v1 hash domain, dimension, declared
bounds, and every covered position's sorted chunk/section/index and decoded
block ID/metadata. It excludes map/display metadata, provenance, acquisition
fields, landmarks, source checksum, palette ordering, filenames, and filesystem
order. Consequently, equivalent geometry has the same hash while known air and
unknown coverage hash differently. A supplied source checksum is stored and
validated independently.

Validate the checked-in synthetic fixture using the runtime-independent Java
entry point through the convenience task:

```sh
./gradlew --no-daemon validateCanonicalMap
```

Validate another directory with:

```sh
./gradlew --no-daemon validateCanonicalMap \
  -PcanonicalMapPath=/absolute/path/to/maps/<map-id>/<revision>
```

After compilation, the validator can also run with only project classes on its
classpath—no Minecraft or Forge runtime is loaded:

```sh
java -cp build/classes/main \
  com.bedwarsbot.world.canonical.CanonicalMapValidatorMain \
  maps/testing/synthetic-test/1
```

The fixture is deliberately tiny and labeled testing-only. It contains no real
Hypixel or Speedway geometry. No production canonical maps are included yet.

## Legacy toolchain risks

This stack is intentionally old. Artifact repositories or TLS behavior can
change, dependency downloads are not fully hermetic, Gradle 2.x cannot run on
current JDK releases, and Minecraft 1.8.9 ships legacy native libraries and
dependencies. Use a dedicated development profile, keep Java 8 isolated from
general browsing/server workloads, and do not treat a successful compile as a
substitute for the real-client smoke test.
