# Bedwars Bot — Codex Project Context and Operating Instructions

> Place this file at the repository root as `AGENTS.md`.
>
> This document is the persistent engineering context for Codex and future contributors. Read it fully before modifying the project. When implementation details conflict with this document, do not silently guess: inspect the current repository, state the conflict, and ask for a decision or update this document after agreement.

---

# GREENFIELD DIRECTIVE — READ BEFORE EVERYTHING ELSE

This repository is a new, clean implementation.

- Do **not** clone, fetch, recover, merge, or check out `ChrisBernitsas/Bedwars-Bot` or any previous Bedwars bot repository.
- Do **not** infer that missing files should be restored from a previous repository.
- Do **not** copy legacy source code merely because this document describes it.
- The old repository is historical reference only. Inspect it only when the project owner explicitly requests comparison with a named file or component.
- Build the new system from first principles according to this document.
- Keep each sprint small and reviewable. Do not recreate historical commands, scripts, dependencies, or packages by default.
- A blank or nearly blank checkout is expected. Missing legacy files are not an error.
- Before implementing a feature, describe the minimum files required, interfaces, tests, risks, and definition of done.
- Do not implement later phases early.
- The first goal is a reproducible, visible, client-only Forge 1.8.9 foundation—not autonomy, PvP, navigation, machine learning, packet collection, or a simulator.

The old project may later be used as a source of lessons or selectively reviewed snippets, but no old code becomes part of this repository without explicit approval and a written reason.

---

## 1. Project mission

Build a high-skill autonomous agent for Hypixel Bedwars on Minecraft 1.8.9.

The long-term research goal is an agent that can:

- Play complete Bedwars matches rather than execute one narrow macro.
- Navigate every supported map while accounting for player-placed and broken blocks.
- Bridge, fight, clutch, shop, bank resources, break beds, use utilities, and recover from unexpected states.
- Reason about survival, economy, generator cycles, map control, relative strength, hidden information, and long-term win probability.
- Learn both known human techniques and strategies that may be discovered through experience.
- Remain observable and auditable: a developer should be able to see what the bot believes, what it is trying to do, and why it selected an action.
- Operate through ordinary Minecraft controls and mechanics under the user’s stated Hypixel-approved constraints.

This is a research and engineering project. Do not pretend the first implementation will exceed elite humans. Build a measurable progression from reliable instrumentation to narrow skills, then tactical and strategic autonomy.

---

## 2. Hard constraints and safety/compliance boundary

The user states that Hypixel has granted development permission under constraints. Treat the exact written permission, once added to the repository, as authoritative. Until then, use the conservative constraints below.

### Allowed design intent

The agent may:

- Read information that a legitimate client receives and that the project is explicitly permitted to inspect.
- Read local client state already present in memory.
- Observe incoming packet-derived state without modifying or delaying packet flow.
- Use ordinary key, mouse, hotbar, and GUI actions.
- Make precise decisions and become highly skilled.
- Use normal Minecraft mechanics such as bridging, sprint jumping, block clutching, fireballs, pearls, potions, ladders, water, shears, and tools.
- Use a dedicated visible Forge 1.8.9 client launched and authenticated normally by the user.
- Log every observation and action for debugging and audit.

### Forbidden behavior

Never implement:

- Reach modification.
- Speed modification or movement outside vanilla/server-valid limits.
- Fast break, fast place, or invalid placement cadence.
- Blink, timer manipulation, packet withholding, packet reordering, or latency manipulation.
- Knockback cancellation or direct velocity modification.
- Direct position spoofing or arbitrary movement-packet construction.
- Impossible rotations, repeated one-tick 180-degree snaps, or other inhuman camera actions.
- Attacks through walls, outside legal reach, or against entities the policy is not permitted to know about.
- ESP-style use of hidden entities or remote information not legitimately exposed to the client.
- Inventory or shop shortcuts that skip normal GUI flows.
- Credential storage in source code.
- Anti-cheat evasion as a project objective.

### Input realism

All gameplay actions should pass through a centralized compliance gate that enforces configurable limits:

- Yaw and pitch angular velocity.
- Angular acceleration.
- Mouse-sensitivity-compatible rotation increments.
- Click-rate and click-spacing bounds.
- Block placement interval.
- Attack reach.
- Interaction reach.
- Hotbar and GUI timing.
- Reaction-delay policy where required by the approved rules.
- Emergency manual override.

Do not scatter compliance checks throughout skills. A single `ActionSafetyGate` or equivalent should validate every proposed action.

### Auditability

Every autonomous deployment must log:

- Build version / Git commit.
- Model version.
- Configuration and compliance profile.
- Observation snapshots.
- Proposed actions.
- Accepted/rejected actions.
- Safety-gate rejection reason.
- Strategic and tactical objectives.
- Manual takeovers.
- Performance timing.
- Session result and notable errors.

The current repository contains `docs/hypixel_compliance.md` and `docs/hypixel_testing_protocol.md`. Audit them against the user’s actual permission terms before live autonomous testing. Some values in those legacy files are provisional examples, not confirmed rules.

---

## 3. Critical architectural conclusions from prior planning

### 3.1 Do not make a perfect simulator a prerequisite

An exact standalone Bedwars simulator would require recreating a large portion of Minecraft 1.8.9 plus Hypixel-specific behavior:

- Movement, collision, jumping, drag, gravity, and fall damage.
- Combat damage, armor, protection randomness, critical hits, knockback, sprint resets, and server corrections.
- Block placement and breaking.
- Projectiles, explosions, TNT, fireballs, pearls, arrows, ladders, water, and special Bedwars items.
- Every map and dynamic block modification.
- Shops, generators, upgrades, traps, beds, respawns, and match phases.
- Network effects and server-specific behavior.

A slightly inaccurate simulator can teach policies that exploit simulator errors and fail in the real game. Therefore:

- Use real Minecraft as the source of truth for mechanics.
- Use a local/private Minecraft environment later for short, controlled scenarios and fast resets.
- Use approximate models only where their fidelity can be measured.
- A coarse strategic simulator may eventually be useful, but it must be learned/calibrated from real trajectories and must not block the initial project.

### 3.2 Use a hierarchical hybrid agent

Do not build one giant network that directly maps the full game state to raw mouse/keyboard actions from the beginning.

Target architecture:

```text
Visible Forge 1.8.9 client
        |
        +-- passive packet/client observation
        +-- input recorder/executor
        +-- debug HUD
        |
        v
Observation and event pipeline
        |
        v
Persistent world and belief model
        |
        +-- canonical map + dynamic overlay
        +-- players/projectiles/events
        +-- inventory/economy
        +-- generator clocks
        +-- opponent beliefs
        +-- uncertainty
        |
        v
Strategic planner (tens of seconds/minutes)
        |
        v
Tactical planner (roughly 1–10 seconds)
        |
        v
Skill library / mechanical policies
        |
        v
ActionSafetyGate
        |
        v
Normal Minecraft key/mouse/GUI pathways
```

The planner should be interruptible. A strategy such as “rotate to middle” must be interrupted by an incoming fireball, bow threat, invisible-player cue, sudden player appearance, broken route, or bed event.

### 3.3 Calculate deterministic facts; learn uncertain outcomes

Do not waste data learning facts available directly from the game.

Calculate or parse:

- Our exact inventory, health, armor, effects, position, rotation, and currently selected slot.
- Known block geometry.
- Known landmarks.
- Generator timers when legitimately visible/derivable.
- Item costs and rules from a versioned rules registry.
- Minimum block count for a candidate bridge.
- Whether a placement or interaction is geometrically legal.
- Known player/entity observations.
- GUI contents while a shop or chest is open.

Learn or estimate:

- Real route completion time under current conditions.
- Bridging failure probability.
- Fight outcome.
- Interception probability.
- Safe potion-use timing.
- Escape probability.
- Opponent inventory and resource beliefs.
- Invisible-player risk.
- Probability of safely banking resources.
- Bed-loss risk.
- Expected resource advantage and eventual win value.

Use explicit uncertainty. Unknown information must remain unknown or probabilistic rather than being filled with guessed exact values.

---

## 4. How the live bot should run

### Initial client target

Use a dedicated Forge 1.8.9 profile for development. Do not require Lunar Client compatibility in the first implementation.

Expected live workflow:

1. User launches Minecraft through the normal launcher.
2. User authenticates normally; the bot never needs the account password.
3. User joins Hypixel or an approved test environment.
4. User enables recording, shadow mode, a narrow skill, or full autonomy.
5. The bot controls the same visible Minecraft window.
6. The developer watches the game and the HUD.
7. A manual hotkey immediately disables automation and releases every key/button.

### Operating modes

Implement these modes explicitly:

- `OBSERVE`: record and maintain the internal state; never control.
- `SHADOW`: calculate and display intended objectives/actions; never execute.
- `ASSIST`: one explicitly selected skill may control while all other behavior remains manual.
- `AUTONOMOUS`: full hierarchy controls the client.
- `DISABLED`: release every synthetic key/button and stop action generation.

Transitions must be safe. Disabling autonomy must release all held keys and mouse buttons in the same tick.

### Process layout

Initial recommendation:

- Java/Forge mod owns observation, input application, HUD, and lightweight deterministic logic.
- An external Python process may later own training and model inference.
- Use a versioned local IPC schema if an external process is introduced.
- Keep the first milestone in Java unless an external process is necessary.
- Do not introduce networking complexity before the local logging/world-state pipeline is stable.

Latency-critical inference can later be exported through ONNX or another local runtime. HUD rendering and disk logging must never block the control loop.

---

## 5. Passive packet and world-data policy

### Important distinction

Reading blocks from `WorldClient`, loaded chunks, or already-received packet events does **not** itself send additional packets.

Never:

- Request extra chunks.
- Increase server-visible loading distance using custom behavior.
- Send probes to discover blocks.
- Repeatedly rescan the entire world on the main thread.
- Treat distant dynamic blocks as known when the server has not sent them.

### Canonical map plus dynamic overlay

Represent the world as:

```text
canonical static map
+ player-placed blocks observed during this match
- blocks observed broken during this match
+ temporary hazards/entities
+ unknown or stale remote regions
```

For a known map:

1. Detect map and mode.
2. Determine team/spawn orientation and world-coordinate transform.
3. Load a locally stored canonical static map.
4. Verify selected loaded chunks or landmarks against fingerprints.
5. Apply passive block/chunk updates to a sparse dynamic overlay.
6. Mark unloaded remote dynamic state as unknown or last-known.
7. Reconcile when chunks naturally load.

Do not serialize every block every tick. Store canonical maps once and log sparse changes:

```json
{
  "tick": 3812,
  "position": [42, 71, -18],
  "old_block": "minecraft:air",
  "new_block": "minecraft:wool",
  "source": "block_change_packet"
}
```

### Threading

On the Minecraft thread:

- Capture a compact immutable event/snapshot.
- Enqueue it into a bounded queue.
- Return quickly.

On background workers:

- Compress or serialize data.
- Update durable map artifacts.
- Calculate expensive graph preprocessing.
- Write logs.

Never call non-thread-safe Minecraft world APIs from a background thread without copying the necessary data first.

---

## 6. World model

The internal state must persist across ticks and include confidence/age.

### Self state

- Position, velocity, yaw, pitch.
- On-ground, sprinting, sneaking, jumping, fall distance.
- Health, absorption, active effects.
- Inventory, armor, hotbar, selected slot.
- Carried and banked resources where known.
- Current action and skill state.
- Recent damage, knockback, placement, and interaction history.
- Ping/server correction estimates.

### Map state

- Map name/version and mode.
- Coordinate transform and spawn/team orientation.
- Static blocks.
- Dynamic block overlay.
- Unknown/stale block regions.
- Void regions.
- Walkable surfaces.
- Bed coordinates and orientations.
- Item Shop and Team Upgrade NPC locations.
- Normal chest and Ender Chest locations.
- Base, diamond, and emerald generator regions.
- Island and route landmarks.
- Place/break restrictions when known.

### Entity state

For every legitimately known entity:

- Stable local identifier.
- Type/team.
- Position and velocity.
- Rotation when available.
- Visible equipment and held item.
- Health when legitimately exposed.
- Visibility/occlusion status.
- Last observation time.
- Confidence and prediction covariance.
- Recent actions/events.

Do not continue treating an unloaded player’s old position as exact. Maintain a belief over likely locations.

### Match state

- Mode and map.
- Match phase and elapsed time.
- Bed states.
- Team alive counts.
- Team upgrades/traps when known.
- Generator tiers.
- Scoreboard and relevant chat/event history.
- Respawn states.
- Known kills, finals, and bed breaks.

### Belief state

Maintain probabilistic estimates for:

- Opponent locations when unseen.
- Opponent carried resources.
- Likely armor/sword/tools/utilities.
- Pearl, fireball, bow, potion, and invisibility likelihood.
- Which generators an opponent may have collected.
- Whether a route is occupied or threatened.
- Probability of an invisible opponent nearby.
- Probability our bed is being approached.

Every belief requires:

- Value/distribution.
- Confidence.
- Last evidence time.
- Evidence source.
- Update rule.

---

## 7. Navigation and time-dependent routing

### Static preprocessing does not mean static routes

Precompute stable connectivity of the original map:

- Walk edges.
- Jump edges.
- Safe drops.
- Island surfaces.
- Common bridge anchor regions.
- Landmark-to-landmark rough distances.
- Void/clutch geometry.
- Potential connection corridors.

At runtime, update paths using the dynamic overlay. Player-placed blocks can create shortcuts, stairs, towers, obstructions, defenses, or broken bridges. Exact routes must be recalculated continuously.

Use A* for the first implementation. Consider D* Lite or Lifelong Planning A* only after correctness and profiling justify incremental planning.

### Movement edge types

The route planner should eventually support:

- Walk.
- Sprint.
- Sprint-jump.
- Normal jump.
- Controlled drop.
- Existing bridge traversal.
- New straight/diagonal/staircase bridge.
- Tower.
- Block break.
- Block placement.
- Ladder/water transition.
- Fireball/TNT jump.
- Pearl transition.

Each edge has:

- Geometric feasibility.
- Estimated time.
- Block/item cost.
- Failure probability.
- Exposure/risk.
- Required skill.
- Recovery surfaces.
- Confidence.

### Route time

Separate:

1. Geometric lower bound.
2. Learned execution correction.

Example features:

- Distance and elevation.
- Number and type of jumps.
- Turns.
- New block placements.
- Bridging style.
- Landing difficulty.
- Hotbar changes.
- Current speed effect.
- Damage/pressure.
- Nearby opponents.
- Projectile exposure.
- Network conditions.

Train route-time models from actual executions rather than hard-coding all travel times.

### Receding-horizon resource routes

A goal such as:

```text
current position -> west emerald -> north emerald -> east diamond -> base
```

must consider:

- Predicted arrival at each generator.
- Whether an item will be present.
- Per-generator spawn phase/desynchronization.
- Waiting time.
- Blocks/items consumed.
- Enemy interception.
- Safe return/banking probability.
- Value carried and lost on death.
- Escape resources reserved.

Plan 10–30 seconds ahead, execute briefly, then replan on every meaningful event. Never blindly follow a one-minute route.

---

## 8. Shops, chests, and GUI interaction

Use deterministic state machines through normal client interactions. Do not train a neural policy to blindly click fixed screen coordinates.

### Item Shop flow

```text
APPROACH_SHOP
-> AIM_AT_SHOPKEEPER
-> CONFIRM_RAYCAST
-> NORMAL_RIGHT_CLICK
-> WAIT_FOR_WINDOW
-> PARSE_CATALOG
-> EXECUTE_PURCHASE
-> VERIFY_INVENTORY_CHANGE
-> NEXT_PURCHASE or CLOSE
-> COMPLETE / FAILED
```

Parse the actual open container:

- Slot index.
- Item identifier.
- Display name.
- Lore.
- Quantity.
- Cost.
- Currency.
- Affordability.
- Page/category state.

Do not assume the user’s Quick Buy layout. The strategic planner asks for semantic purchases; the GUI controller resolves them to the current shop slots.

Example:

```json
{
  "intent": "purchase",
  "items": [
    {"type": "wool", "target_total": 48},
    {"type": "stone_sword", "count": 1},
    {"type": "fireball", "count": 1}
  ]
}
```

After each click, wait for server-confirmed inventory/container changes. On timeout, back off or recover rather than spamming.

### Ender Chest / team chest flow

```text
APPROACH_CHEST
-> IDENTIFY_CHEST_TYPE
-> AIM_AND_RIGHT_CLICK
-> WAIT_FOR_WINDOW
-> PARSE_PLAYER_AND_CHEST_SLOTS
-> EXECUTE_TRANSFER
-> VERIFY_EACH_TRANSACTION
-> CLOSE
```

The planner supplies an intent, for example:

```json
{
  "deposit": {"emerald": "all", "diamond": "all"},
  "keep": {"gold": 3, "iron": 40},
  "preserve_hotbar_layout": true
}
```

Support ordinary shift-click and cursor-based transfers through Minecraft’s normal GUI logic.

Opening a chest has tactical cost. Do not bank automatically when an opponent can reach the bot before the interaction finishes.

---

## 9. Strategic representation: survival and economic power

Raw resource counts are insufficient.

### Permanent power

- Armor tier.
- Protection.
- Sword tier.
- Sharpness.
- Tools.
- Team upgrades.

### Temporary combat power

- Speed, jump, invisibility.
- Golden apples/absorption.
- Active effects and remaining duration.
- TNT, fireballs, bridge eggs, golems, bed bugs, bows/arrows.

### Escape power

- Pearls.
- Fireballs.
- Blocks.
- Ladders.
- Water.
- Potions.
- Nearby clutch surfaces.
- Safe or covered routes.
- Distance/time to shop or chest.

### Economic position

- Resources carried.
- Resources banked.
- Current purchase opportunities.
- Generator control.
- Time to next spawn.
- Safe access to shop/upgrades.
- Expected future income.
- Resources at risk on death.

### Opponent threat

- Known equipment and effects.
- Estimated utilities.
- Contact time.
- Likely routes.
- Ability to bow/fireball/pearl.
- Ability to cut off escape.
- Relative fight probability.
- Bed pressure.
- Missing/invisibility risk.

Four emeralds do not automatically mean the bot is ahead. A stronger opponent may make fighting impossible, while the emeralds may create escape power only after a potion is consumed or a pearl is purchased. The planner must reason about whether resources can be converted before contact.

---

## 10. Learning probabilities and values

### The counterfactual problem

A real trajectory reveals the result of the action taken, not actions that were not taken.

If the bot continues a first rush and loses, the game provides evidence about continuing the rush in that state. It does not reveal what would have happened if the bot rotated to middle.

Therefore combine:

1. Human demonstrations on real Hypixel.
2. Approved bot games on Hypixel with controlled exploration among plausible macro-actions.
3. Controlled short Minecraft scenarios with varied initial states.
4. Bot-versus-bot scenarios where practical.
5. Historical/frozen opponent policies.
6. Human replay annotations and pairwise preferences.
7. Approximate models only after they can be validated against real data.

Do not claim values are literal win probabilities unless the model is trained and calibrated as probabilities.

### Prediction heads

Prefer multiple interpretable predictions:

- `P(win_match)`.
- `P(death_within_5s)`.
- `P(death_within_10s)`.
- `P(death_within_30s)`.
- `P(our_bed_lost_within_30s)`.
- `P(reach_target)`.
- `P(safely_bank_inventory)`.
- `P(win_fight)`.
- `P(intercepted_on_route)`.
- `P(successful_block_clutch)`.
- `P(successful_pearl_escape)`.
- `P(successful_fireball_escape)`.
- Expected emeralds/diamonds after a horizon.
- Expected permanent-power advantage.
- Expected map-control change.

The strategic decision layer may combine these into expected utility, but eventual match wins remain the primary long-term objective.

### Automatic labels

Derive labels from logs whenever possible:

- Survived next N seconds.
- Reached target landmark.
- Completed potion before contact.
- Collected generator item.
- Banked resources.
- Lost resources on death.
- Opponent intercepted route.
- Fight result and damage exchange.
- Bed lost.
- Match result.
- Predicted versus actual route time.
- Predicted versus actual block use.

### Data efficiency

Do not rely only on complete-match win/loss labels.

One match contains many useful event windows:

- Route segments.
- Generator visits.
- Approaches and retreats.
- Fights.
- Item uses.
- Shop/chest operations.
- Bed attacks.
- Clutches.
- Objective transitions.

Adjacent ticks are highly correlated. Build event/decision datasets rather than counting each tick as an independent training example.

### Calibration and uncertainty

For every probability model:

- Use held-out maps/matches.
- Measure calibration, Brier score/log loss, and reliability curves.
- Report confidence intervals.
- Detect out-of-distribution states.
- Fall back to conservative logic when confidence is poor.
- Log prediction and actual outcome for continuous calibration.

---

## 11. Human annotations and strategy supervision

The user’s Bedwars reasoning should become training data.

Support live or replay annotations such as:

- Survive.
- Avoid stronger player.
- Control emeralds.
- Collect next generator cycle.
- Bank resources.
- Prepare escape.
- Gather diamonds.
- Defend.
- Pressure bed.
- Trade bed.
- Abandon stalled first rush.
- Target strongest/economically dangerous team.

Support pairwise preferences:

```text
drink speed now > wait to drink
rotate through diamond island > exposed direct bridge
bank four emeralds > attempt low-probability fight
continue collecting next emerald cycle > chase weak player
```

Shadow mode should display proposed objectives and allow accept/reject feedback.

Do not assume the user’s current heuristic is universally optimal. Use it as a strong prior and supervision source while still optimizing match wins.

---

## 12. PvP and mechanical learning

### Do not choose between pure hard-coding and pure discovery

Use a hybrid:

- Ordinary bounded action space.
- Demonstrations of known techniques.
- Explicit scenario curricula.
- Learned timing/selection.
- Reusable parameterized skill interfaces.
- Bot-versus-bot refinement with frozen historical policies.

### PvP observations

- Relative positions/velocities/rotations.
- Reach and line-of-sight.
- Nearby blocks/void.
- Our and opponent equipment.
- Health/absorption when available.
- Recent hits, damage, knockback, sprint state.
- Attack and placement history.
- Hotbar/resources.
- Third-party threats.
- Ping/server corrections.

### PvP actions

- W/A/S/D.
- Jump/sneak/sprint.
- Attack/use.
- Hotbar select.
- Bounded yaw/pitch deltas.
- Block/tool/item actions through normal controls.

### Known techniques to include in curriculum

- Hit selecting.
- W-tapping.
- S-tapping.
- Jump resetting.
- Spacing and sprint resets.
- Combat block placements.
- Placing blocks and shearing them.
- Constraining opponents with blocks.
- Low-ground/high-ground fighting.
- Bridge and void fighting.
- Trading versus disengaging.
- Fireball/bow/projectile pressure.
- Preserving an escape route.
- Third-party fights.

The model should learn when to use these techniques rather than follow unconditional rules.

### Controlled scenarios

Use short, resettable Minecraft scenarios later:

- Ground 1v1.
- Narrow bridge 1v1.
- Different armor/sword/protection.
- Health disadvantages.
- Random ping/delay.
- Random nearby blocks.
- Bow/fireball pressure.
- Third player arrival.
- Escape objective instead of kill objective.

Real Minecraft remains the mechanical source of truth.

---

## 13. Clutching and short-horizon recovery

When knocked toward the void, use a short-horizon local predictor rather than a complete match simulator.

Inputs:

- Position/velocity.
- Server velocity and recent corrections.
- Yaw/pitch.
- Nearby collision geometry.
- Void and lower surfaces.
- Blocks/items/hotbar.
- Ping.
- Opponent/projectile state.

Candidate responses:

- Directional movement.
- Jump/sprint changes.
- Turn and block clutch.
- Ladder/water clutch.
- Pearl.
- Fireball.
- Pearl plus fireball recovery.
- Land on lower island.
- Abandon impossible recovery.

Use receding-horizon/model-predictive control:

1. Generate candidate recoveries.
2. Predict only the next several ticks.
3. Execute briefly.
4. Observe the actual server result.
5. Replan immediately.

Any local physics approximation must be calibrated continuously against real logs.

---

## 14. Generator timing and resource-cycle reasoning

Track each generator independently where evidence suggests asynchronous behavior.

Generator state should include:

- Type and location.
- Tier.
- Last observed spawn.
- Last observed collection.
- Estimated next-spawn distribution.
- Current known/estimated item count.
- Confidence.
- Drift/desynchronization.

The bot should reason about what can be accomplished between spawns:

```text
next emerald in ~9s
reach west emerald in ~7.8s
diamond detour adds ~5s
enemy contact estimate 8–12s
```

It may position at the correct generator just before the spawn, use spare time for another resource, or abandon a cycle if safe return is unlikely.

Do not assume all four emerald generators are perfectly synchronized.

---

## 15. HUD and observability

The HUD is a first-class product feature, not a late debugging add-on.

Display, when available:

### Mode and health

- Current operating mode.
- Safety/compliance status.
- Tick/inference timing.
- Logger/queue status.

### Objective hierarchy

- Strategic objective.
- Tactical objective/interrupt.
- Active skill.
- Time since objective selection.
- Reason for replan.

### Beliefs

- Known and predicted player locations.
- Opponent resource/equipment beliefs.
- Invisible-player risk.
- Bed threat.
- Uncertainty/staleness.

### Planning

- Current path.
- ETA.
- Required blocks/items.
- Exposure/risk.
- Generator arrival/spawn timing.
- Alternative objectives and predicted outcomes.

### Recovery

- Clutch/escape candidates.
- Predicted success.
- Item reservation.

### Compliance

- Rotation rate.
- Click rate.
- Placement cadence.
- Any rejected action and reason.

Do not produce fake natural-language explanations. Generate explanations from actual state features, alternatives, predicted values, constraints, and selected actions.

HUD and logging must read immutable snapshots asynchronously. They must not delay action application.

---

## 16. Mechanics research seeds from `Bedwars peak.pdf`

The uploaded research PDF contains useful measurements, but some entries may be old, ambiguous, map-specific, or incorrect. Treat every value as a hypothesis until validated by instrumentation. Convert them into a versioned mechanics registry with source, confidence, map/mode, number of trials, date, and verification status.

Useful seeds include:

- Damage calculation notes using sword base damage, sharpness, armor points, and randomized protection effectiveness.
- A pearl launch-angle hypothesis around 38 degrees.
- Map-specific distance notes for Nebuc and Solace.
- Diamond/emerald timing measurements and a claim that emerald generators may spawn asynchronously or drift.
- Base iron/gold timing measurements and Forge-rate hypotheses.
- TNT fuse measurement around 2.5 seconds.
- Sponge timing/radius observations for TNT/water interactions during invisibility attacks.
- Large hit-to-kill distributions across armor, protection, sharpness, and golden-apple timing.
- Summary claims that Protection IV and repeated golden-apple consumption have nonlinear survival effects.

Suggested registry entry:

```yaml
id: emerald_interval_airshow_emeraldiII
mechanic: generator_spawn_interval
resource: emerald
map: airshow
mode: solo_doubles
phase: emerald_iii
value_seconds: 11.3
source:
  type: user_research_pdf
  file: Bedwars peak.pdf
  page: 9
status: unverified
confidence: medium
notes: Interpretation and phase naming require validation from packet logs.
```

Do not copy ambiguous spreadsheet values directly into production rules.

---

## 17. Historical legacy repository reference — do not restore or copy

A previous repository, `ChrisBernitsas/Bedwars-Bot`, was inspected during planning. It is not the implementation base for this project.

Do not fetch or restore it. The information below only records concepts and pitfalls that may be useful if the owner later asks for a targeted comparison. Historically, it contained:

### Documentation

- `README.md`
- `bedwars_bot_plan.md`
- `docs/instrumented_client_spec.md`
- `docs/forge_logging_implementation.md`
- `docs/hypixel_compliance.md`
- `docs/hypixel_testing_protocol.md`

### Java/Forge components

- `src/main/java/com/bedwarsbot/BedwarsBotMod.java`
- `.../bot/BotController.java`
- `.../game/HypixelGameDetector.java`
- `.../auto/AutoConnector.java`
- `.../config/ConfigManager.java`
- `.../logging/TickCollector.java`
- `.../logging/InputRecorder.java`
- `.../logging/SessionManager.java`
- `.../logging/SnapshotBuilder.java`
- `.../logging/ComplianceMonitor.java`
- logging model classes
- command classes
- `.../skills/Skill.java`
- `.../skills/SpeedBridgeSkill.java`

### Python tooling

- `tools/convert_logs.py`
- an intended behavior-cloning baseline under `experiments/` may exist; verify rather than assume.

### Known historical behavior

- `BedwarsBotMod` registered logging, game detection, a minimal bot controller, commands, and an auto-connector.
- `SnapshotBuilder` captured local pose, motion, health, inventory resources, loaded players, equipment, scoreboard, and team data.
- `WorldState` was minimal and did not yet store the full block map/dynamic overlay.
- `BotController` set Minecraft `KeyBinding.pressed` states for movement and could issue queue commands.
- `SpeedBridgeSkill` was a no-op placeholder.
- Logging conversion flattened JSONL into CSV, which is not sufficient as the final sequence/event dataset format.
- Some old plans suggested direct ONNX embedding and a tiny compute budget. Preserve useful code, but reassess all architectural assumptions.

### Greenfield audit requirement

Before implementing:

1. Confirm that the checkout is greenfield and contains no recovered legacy source.
2. Inventory the files that actually exist.
3. Propose the minimum Forge 1.8.9 scaffold needed.
4. Explain the Java, Gradle, ForgeGradle, mappings, and run-client constraints.
5. Build and launch the minimal mod before adding architecture.
6. Add tests only for deterministic logic that can run outside Minecraft.
7. Do not create placeholder subsystems for later phases.

---

## 18. Recommended project layout

Adapt to the existing Gradle structure rather than moving everything immediately.

```text
/
├── AGENTS.md
├── README.md
├── CHANGELOG.md
├── build.gradle
├── gradle/
├── docs/
│   ├── architecture.md
│   ├── compliance.md
│   ├── testing_protocol.md
│   ├── data_schema.md
│   ├── map_format.md
│   ├── learning_plan.md
│   └── decisions/
│       └── ADR-*.md
├── research/
│   ├── mechanics/
│   │   ├── items.yaml
│   │   ├── upgrades.yaml
│   │   ├── generators.yaml
│   │   ├── combat.yaml
│   │   └── hypotheses.yaml
│   └── sources.md
├── src/main/java/com/bedwarsbot/
│   ├── BedwarsBotMod.java
│   ├── observation/
│   │   ├── ClientStateCollector.java
│   │   ├── PacketEventCollector.java
│   │   ├── InputRecorder.java
│   │   └── ObservationBus.java
│   ├── world/
│   │   ├── WorldModel.java
│   │   ├── CanonicalMap.java
│   │   ├── DynamicBlockOverlay.java
│   │   ├── MapRegistry.java
│   │   ├── LandmarkRegistry.java
│   │   └── PlayerBeliefTracker.java
│   ├── navigation/
│   │   ├── NavGraph.java
│   │   ├── PathPlanner.java
│   │   ├── RouteEstimator.java
│   │   └── MovementEdge.java
│   ├── interaction/
│   │   ├── ShopController.java
│   │   ├── ShopParser.java
│   │   ├── ChestController.java
│   │   ├── InventoryTransactionTracker.java
│   │   └── HotbarController.java
│   ├── control/
│   │   ├── InputFrame.java
│   │   ├── InputController.java
│   │   ├── RotationController.java
│   │   ├── ActionSafetyGate.java
│   │   └── ManualOverride.java
│   ├── strategy/
│   │   ├── StrategicState.java
│   │   ├── Objective.java
│   │   └── StrategicPlanner.java
│   ├── tactics/
│   │   ├── TacticalPlanner.java
│   │   └── Interrupt.java
│   ├── skills/
│   │   ├── Skill.java
│   │   ├── SkillManager.java
│   │   ├── NavigateSkill.java
│   │   ├── BridgeSkill.java
│   │   ├── ShopSkill.java
│   │   ├── BankSkill.java
│   │   ├── CombatSkill.java
│   │   └── RecoverySkill.java
│   ├── hud/
│   │   ├── DebugHud.java
│   │   └── HudSnapshot.java
│   ├── logging/
│   │   ├── SessionManager.java
│   │   ├── EventLogger.java
│   │   ├── ReplayWriter.java
│   │   └── schema/
│   └── config/
├── tools/
│   ├── validate_logs.py
│   ├── build_events.py
│   ├── replay_viewer.py
│   └── map_tools/
├── training/
│   ├── datasets/
│   ├── models/
│   ├── evaluation/
│   └── experiments/
├── maps/
│   └── <map>/<version>/
└── tests/
```

This is directional. Do not create empty files merely to match the tree.

---

## 19. Data schema principles

### Use event-sourced logs plus periodic snapshots

A full dense world dump every tick is wasteful.

Log:

- Session metadata.
- Periodic compact state snapshots.
- Input frames.
- Packet-derived semantic events.
- Block changes.
- GUI/window events.
- Inventory transactions.
- Damage/knockback/combat events.
- Objective/skill changes.
- Predictions and outcomes.
- Compliance events.

### Time synchronization

Every record should include:

- Session ID.
- Monotonic sequence.
- Client tick.
- World tick when available.
- Monotonic local time in nanoseconds/milliseconds.
- Wall-clock UTC for session metadata.
- Source thread/component.
- Schema version.

### Raw versus derived data

Preserve raw observations necessary to reproduce derived features. Derived datasets may be rebuilt later.

Do not flatten the only copy of sequence data to CSV. Prefer JSONL/CBOR/MessagePack/Parquet depending on maturity. Early JSONL is acceptable for inspectability.

### Privacy/security

- Never log credentials or session tokens.
- Hash or pseudonymize player identifiers in exported training data where appropriate.
- Keep raw logs access-controlled.
- Make retention configurable.
- Record consent/approved testing context in session metadata.

---

## 20. Evaluation

### Mechanical metrics

- Falls per 100 bridged blocks.
- Bridge speed and block efficiency.
- Jump/route completion.
- Clutch success by category and difficulty.
- Pearl/fireball landing error.
- Aim/target tracking error.
- First-hit rate.
- Damage dealt/received.
- Void conversion.
- Shop/chest success and interaction latency.
- Bed-break time by defense.

### Tactical metrics

- Fight selection quality.
- Escape success.
- Safe-bank probability calibration.
- Resource loss per death.
- Projectile avoidance.
- Potion timing.
- Interrupt/replan latency.

### Strategic metrics

- Win rate/Elo against fixed pools.
- Bed breaks and finals.
- First-rush success and abandonment quality.
- Emerald/diamond control.
- Resource conversion efficiency.
- Win rate after losing bed.
- Win rate from disadvantaged equipment.
- Performance on unseen maps/opponent styles.
- Calibration of win/death/bed-loss forecasts.

Keep hidden evaluation matches/scenarios. Do not promote a model solely because it beats its latest training opponent.

---

## 21. Development phases and acceptance criteria

### Phase 1 — Instrumented visible client

Deliver:

- Reproducible Forge 1.8.9 build.
- Robust mode state machine.
- Input recording.
- Local state snapshots.
- Asynchronous logging.
- Manual override.
- Basic HUD.
- No autonomous gameplay beyond controlled smoke tests.

Acceptance:

- One complete human match is recorded with no main-thread stalls.
- Tick/event ordering is reconstructable.
- Disabling control releases all inputs.
- Logs validate and close cleanly.

### Phase 2 — Passive world model

Deliver:

- Chunk/block observation without extra server requests.
- Canonical map format and registry.
- Sparse dynamic overlay.
- Landmark representation.
- Unknown/stale-region semantics.
- Basic map visualization.

Acceptance:

- Base geometry and landmarks reconstruct correctly.
- Placed/broken blocks appear promptly.
- Main-thread processing stays within budget.
- No outgoing packet behavior is added for mapping.

### Phase 3 — Input execution and shadow mode

Deliver:

- `InputFrame`.
- Movement key application.
- Smooth bounded rotation.
- Attack/use/hotbar/GUI pathways.
- Central safety gate.
- Shadow overlay.

Acceptance:

- Scripted movement in offline/private testing follows legal controls.
- Safety violations are blocked/logged.
- User can take over instantly.
- HUD shows proposed versus executed actions.

### Phase 4 — Deterministic navigation baseline

Deliver:

- Static surface graph.
- Dynamic path updates.
- Walk/jump/drop traversal.
- ETA and block requirement estimates.
- Landmark navigation.

Acceptance:

- Navigate between selected landmarks on a changing test map.
- Replan around newly placed/broken blocks.
- Report path, ETA, and failure reason.

### Phase 5 — Shop and storage

Deliver:

- Shopkeeper/chest interaction state machines.
- Shop catalog parser.
- Semantic purchase plan.
- Verified inventory transactions.
- Ender Chest/team chest transfer plans.

Acceptance:

- Open shop normally.
- Buy a requested item without fixed Quick Buy assumptions.
- Detect insufficient funds/changed GUI.
- Deposit and retrieve resources through normal GUI flow.
- Recover cleanly from missed clicks/window closure.

### Phase 6 — Data collection and event extraction

Deliver:

- Event segmentation.
- Human annotation tooling.
- Replay viewer.
- Route/fight/shop/escape datasets.

Acceptance:

- Review a match and jump to major events.
- Add objective/preference labels.
- Rebuild derived datasets from raw logs.

### Phase 7 onward

Only after the foundation is reliable:

- Route-time/risk prediction.
- Narrow mechanical skills.
- Controlled PvP/clutch scenarios.
- Opponent/resource beliefs.
- Tactical planning.
- Strategic planning.
- Approved autonomous evaluation.

---

## 22. Immediate implementation sprint for Codex

Do **not** begin by training a neural network, implementing full PvP, or building a standalone Bedwars simulator.

### Step 1: greenfield bootstrap audit

Inspect and report:

- Confirm that no old repository was cloned, restored, or copied.
- Files currently present in the new checkout.
- Proposed Forge 1.8.9 / ForgeGradle / Gradle / Java 8 versions and why.
- Minimum package and resource files needed to load a client-only mod.
- Proposed build, test, and run-client commands.
- macOS/JDK-specific setup requirements.
- Risks from the legacy 1.8.9 toolchain.
- Definition of done for the bootstrap.

Do not add gameplay control, logging, HUD, world collection, or packet hooks until the minimal mod builds and launches.

### Step 2: Phase 0 — minimal Forge bootstrap

Implement only:

1. Reproducible client-only Forge 1.8.9 project.
2. One mod entry point.
3. Mod metadata and resources.
4. One harmless command or HUD label proving the mod loaded.
5. Build and run-client instructions.
6. A basic build check or CI workflow if practical.
7. No gameplay input, packet handling, block collection, autonomy, or ML.

Definition of done:

- `clean test build` succeeds with the documented JDK.
- The development client launches.
- The mod is visibly loaded.
- The harmless smoke test works.
- No legacy source or dependencies were imported.

After the owner verifies Phase 0 in a real client, use a separate Phase 1 sprint for `BotMode`, `ManualOverride`, `InputFrame`, `InputController`, `ActionSafetyGate`, minimal asynchronous logging, and a minimal HUD.

### Step 3: later sprint — passive block-event prototype

After the control foundation is stable:

- Add a passive dynamic block-event collector.
- Capture loaded chunk identification and block-change events without sending anything.
- Do not scan the whole world every tick.
- Store a sparse overlay in memory.
- Add metrics: queue size, dropped events, processing duration.
- Render nearby changed blocks or counts in the HUD.
- Write a focused test plan for offline and approved live validation.

### Required Codex response before major edits

Before changing many files, provide:

1. Current-state audit.
2. Proposed files to add/modify.
3. Risks.
4. Build/test commands.
5. Definition of done for the sprint.

Then implement in small, reviewable commits or patches.

---

## 23. Coding and review standards

- Prefer explicit types and immutable data crossing thread boundaries.
- Avoid global mutable singletons where practical.
- Keep Minecraft API access on the correct thread.
- Use bounded queues with metrics and a defined overflow policy.
- Do not swallow exceptions.
- Log structured events rather than ad-hoc prose only.
- Version serialized schemas.
- Include unit tests for deterministic logic.
- Include integration test instructions for Minecraft-dependent behavior.
- Avoid premature abstraction, but keep observation, decision, and action layers separate.
- Do not add a dependency without explaining why the JDK/standard library or existing dependency is insufficient.
- Never commit secrets, tokens, account identifiers, or raw private logs.
- Do not silently rewrite legacy files; preserve history and document migrations.
- Keep live-autonomy features off by default.
- Every feature that can act must have a safe stop path.
- Update documentation and acceptance criteria with the code.

---

## 24. Open decisions that must remain explicit

Do not guess these permanently:

- Exact written Hypixel permissions and perception rules.
- Whether all packet-exposed entity data may be used or only human-visible subsets.
- Approved rotation/click/placement limits.
- Approved testing accounts and environments.
- Exact map/mode scope for the first playable milestone.
- Whether the existing repository is reused or a new clean repository is created.
- Initial canonical map acquisition process.
- Exact logging format after JSONL proof of concept.
- Whether model inference remains external or moves in-process.
- Private-server framework for later scenario generation.
- Compute budget; the old repository mentions a strict `$100` target, while the current requirement is generally “not thousands of dollars.”
- Definition of “better than humans”: median player, high-star player, leaderboard player, or specific Elo/win-rate benchmark.

Record decisions in ADRs rather than letting them remain implicit in code.

---

## 25. Source/provenance notes

This document consolidates:

- The current planning conversation with the project owner.
- The previously inspected `ChrisBernitsas/Bedwars-Bot` repository.
- The repository’s legacy planning, logging, compliance, and testing documents.
- The uploaded `Bedwars peak.pdf` mechanics research.
- General architectural lessons from hierarchical game agents, imitation learning, targeted scenario training, league/self-play methods, and model-predictive control.

Repository code and PDF measurements may be outdated or internally inconsistent. Instrumentation and controlled validation are the source of truth.

---

## 26. First prompt to use with Codex

After adding this file to the new repository, use:

> Read `AGENTS.md` completely, especially the GREENFIELD DIRECTIVE. This is a new repository. Do not clone, fetch, recover, or copy the previous `ChrisBernitsas/Bedwars-Bot` repository. Do not implement gameplay control, logging, packet hooks, block collection, navigation, PvP, ML, or a simulator yet. Perform only Section 22 Step 1 and Phase 0: propose the minimal Forge 1.8.9 scaffold, explain the legacy toolchain choices and risks, then implement a client-only mod that builds and can be verified as loaded with one harmless smoke-test command or HUD label. Run all available build/tests, list every file created, and clearly identify what still requires verification in a real client.
