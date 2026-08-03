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

Development is currently in the initial client-foundation phase. Autonomous gameplay and machine-learning systems have not yet been implemented.

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

The distributable mod is written to `build/libs/bedwarsbot-0.1.0.jar`. There
are no unit tests yet because Phase 0 contains no Minecraft-independent logic;
the `test` task is still part of the required build check.

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

### Legacy toolchain risks

This stack is intentionally old. Artifact repositories or TLS behavior can
change, dependency downloads are not fully hermetic, Gradle 2.x cannot run on
current JDK releases, and Minecraft 1.8.9 ships legacy native libraries and
dependencies. Use a dedicated development profile, keep Java 8 isolated from
general browsing/server workloads, and do not treat a successful compile as a
substitute for the real-client smoke test.
