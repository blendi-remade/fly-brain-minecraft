# Changelog

All notable changes to the Fruit Fly Connectome mod are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed
- Ported to Minecraft 26.2: Fabric Loader 0.19.5, Fabric API 0.161.0+26.2, Loom 1.18 (unobfuscated game, no mappings),
  Gradle 9.7.1, Java 25. The Gradle daemon JVM is pinned to 25 in `gradle/gradle-daemon-jvm.properties` and downloaded
  automatically when no JDK 25 is installed.
- Fly rendering moved to render states: `FlyRenderState` carries everything the model needs, and the wing, motion-blur
  ghost and glow layers draw their own masked `FlyModel` instances (rendering is deferred, so layers can no longer pose
  the shared model between draws).
- Neuroscope and brain view HUDs register through `HudElementRegistry` (after the last vanilla element, hidden with F1);
  the brain hologram and focus marker are submitted from `LevelRenderEvents.COLLECT_SUBMITS` with an additive pipeline.
- Entity saving uses `ValueInput`/`ValueOutput` (same keys, old saves load); the fly-number counter is now
  `data/fruitfly/fly_ids.dat`.
- Spawn egg has its own item texture (`tools/gen_spawn_egg.py`), since 26.x has no tinted spawn-egg template.

### Fixed
- `gradlew` is committed with its executable bit, so `./gradlew build` works on a fresh clone.

## [0.1.0] - 2026-09-03

Initial release, built on the day the male CNS connectome paper (Berg et al., *Cell* 2026) was published.

### Added

**Data**
- `tools/fetch_neuprint.py`: anonymous, resumable Cypher fetch of neuPrint `male-cns:v1.0` (176,422 neurons; connections
  with >= 5 synapses) into `data/raw/`.
- `tools/build_flyb.py`: FLYB v1 binary builder (CSR adjacency, string tables, soma positions, neurotransmitter signs, retina
  column table). Bundled result `src/main/resources/connectome/malecns-v1.0.flyb.gz` (23 MB gzip, 46.6 MB raw): 176,422
  neurons, 6,287,749 connections carrying 90,296,905 synapses. Provenance in `PROVENANCE.md`, counts in
  `docs/connectome-stats.json`.

**Brain (`com.fruitfly.brain`, engine-independent)**
- `Connectome` FLYB loader; `PopulationIndex` spec language (`DNp09/L`, `prefix:ORN_`, `class:gustatory`, `subclass:wm`,
  `a&b`, `body:<id>`, `all`).
- `LifNetwork`: event-driven, multi-threaded leaky integrate-and-fire network with the Shiu et al. 2024 parameters
  (tau_m 20 ms, tau_syn 5 ms, v_rest/reset -52 mV, threshold -45 mV, refractory 2.2 ms, delay 1.8 ms, 0.275 mV per synapse),
  exact per-step integration, Poisson sensory spike generators, injected currents, per-population postsynaptic gain,
  spike log. Global synaptic gain 0.65 recalibrated for the male-CNS synapse density.
- `BrainRunner`: one daemon thread per fly, 50 ms brain ticks phase-locked to the game tick, immutable snapshots,
  real-time-factor reporting (neural time is never skipped).
- `RetinaGeometry`: medulla hex columns -> view directions (877 left + 892 right columns, ~130 coarse rays per eye).
- `SensoryFrame` / `SensoryEncoders`: vision (photoreceptors + tonic lamina drive, LC4/LPLC2/LC11/LC18/LC10a/LC15 feature
  channels, HS/VS optic flow), olfaction (glomerular drive vectors with adaptation and bilateral gain), gustation
  (tarsal/labellar/pharyngeal GRN types), mechanosensation (JO wind/auditory/grooming, bristles, halteres, hair plates),
  thermo/hygrosensation.
- `MotorMap` / `MotorDecoder`: 21 literature-derived command channels read from descending and motor neurons; priority
  ladder escape > landing > brake > halt > backward > forward/yaw > feed > groom > song with hysteresis and dwell times.
- Headless harnesses: `gradlew brainBench`, `gradlew visionBench`, `gradlew embodiedBench`.
- JUnit 5 tests for the LIF integrator (`LifNetworkTest`, synthetic connectomes).

**Mod (`com.fruitfly`, Fabric 1.21.1, Mojang mappings)**
- `FlyEntity` (no vanilla AI goals): world -> `SensoryFrame` -> brain thread -> `MotorCommand` -> `FlyBody` motion; synched
  mode, flight, proboscis, wing extension, grooming state, activity, sex and scale for the client.
- `OdorTable` / `TasteTable`: Minecraft items and blocks mapped onto real glomeruli and GRN types; other flies emit cVA
  or female volatiles; players emit weak CO2.
- `FlyBody`: hand-built body layer (speed gains, flight/landing physics, giant-fibre jump, optional reflex layer for odor
  taxis, exploration bouts and collision avoidance when the brain issues no locomotor command).
- `FlyBrainService`: connectome loaded once per server; `maxBrains` cap (default 4); Kenyon-cell input gain 0.25.
- `BrainTelemetryPayload` (S2C): mode, spikes, active neurons, real-time factor, decoded channels, watched population
  rates, retina ray luminances, spike sample.
- `/fruitfly spawn|stats|stim|watch|feed|bitter|loom|groom|odor|pause|resume|kill|senses` commands.
- `config/fruitfly.json` (brain, senses, body, telemetry, HUD populations).
- Spawn egg in the Spawn Eggs creative tab.

**Documentation**
- `README.md`, `PROVENANCE.md`, `docs/ARCHITECTURE.md`, `docs/VALIDATION.md`, research notes in `docs/research/`.
- GitHub Actions build workflow.

### Validated (headless, see `docs/VALIDATION.md`)
- Sugar GRNs -> G2N-1, Fudog, Rounddown -> MN9 proboscis motor neuron 30-90 Hz; Kenyon cells silent.
- Bitter GRNs -> Scapula ~300 Hz; MN9 fully suppressed even with sugar present.
- Looming LC4 + LPLC2 -> giant fibre 270-370 Hz, DNp04/DNp02/DNp11, TTMn 60-80 Hz -> decoder ESCAPE within 100 ms.
- JO wind/gravity + grooming -> aDN1 ~200 Hz, aDN2 ~150 Hz.
- Lamina L1-L3 tonic ~35 Hz in darkness, ~10 Hz in light.

### Known limitations
- Uniform LIF: antennal-lobe projection neurons saturate (~400 Hz), the medulla motion pathway (Mi1/Tm/T4/T5) is silent so
  looming and small-object channels are painted analytically onto LC neurons, no neuromodulation, no spontaneous activity.
- Connections with fewer than 5 synapses are omitted (28 % of synapses).
- Retina orientation is a linear hex -> angle map (about +/-25 % at the periphery).
- Only the male CNS has been reconstructed; female flies use the same brain.
- Odor-driven and exploratory walking is mostly the hand-built reflex layer; the HUD shows when it is driving.

[0.1.0]: https://github.com/blendi-remade/fly-brain-minecraft/releases/tag/v0.1.0
