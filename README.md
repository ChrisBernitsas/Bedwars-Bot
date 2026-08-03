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

Development is currently in the Phase 1 client-foundation stage. Autonomous
decision-making, gameplay skills, packet observation, world collection, and
machine-learning systems have not been implemented.

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

The distributable mod is written to `build/libs/bedwarsbot-0.2.0.jar`. JUnit is
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
```

Each mode transition clears proposed and active input. Movement proposals are
persistent until cleared, blocked by context, or stopped with F10. No attack,
use-item, rotation, placement, GUI, or packet action exists.

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

### Legacy toolchain risks

This stack is intentionally old. Artifact repositories or TLS behavior can
change, dependency downloads are not fully hermetic, Gradle 2.x cannot run on
current JDK releases, and Minecraft 1.8.9 ships legacy native libraries and
dependencies. Use a dedicated development profile, keep Java 8 isolated from
general browsing/server workloads, and do not treat a successful compile as a
substitute for the real-client smoke test.
