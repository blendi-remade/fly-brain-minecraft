# Fruit Fly Connectome mod — architecture

An embodied *Drosophila melanogaster* in Minecraft. The complete male CNS connectome (neuPrint `male-cns:v1.0`,
176,422 neurons; 6.29 M connections with ≥5 synapses carrying 90.3 M of the 125 M synapses) is simulated as a
leaky integrate-and-fire (LIF) spiking network. Sensory neurons are driven from the Minecraft world; descending and
motor neuron activity is decoded into the fly mob's movement.

```
 Minecraft world ──sensors──▶ SensoryFrame ──encoders──▶ Poisson drives on real sensory neuron types
                                                            │
                                                 LifNetwork (brain thread, 50 ms brain tick = 1 game tick)
                                                            │
 FlyEntity movement ◀──MotorDecoder◀── BrainSnapshot (DN / MN population rates)
```

## 1. Data (tools/, Python)

* `tools/fetch_neuprint.py` pulls neurons + edges from neuPrint via anonymous Cypher (no token needed) into
  `data/raw/male-cns_v1.0/`. ~2 min with 6 workers. `--min-weight 1` fetches all 25.9 M connections.
* `tools/build_flyb.py` writes the compact **FLYB v1** binary (format documented in the script header and mirrored by
  `Connectome.parse`). Default output `src/main/resources/connectome/malecns-v1.0.flyb.gz` (23 MB gzip, 46.6 MB raw)
  is bundled in the jar. Neurotransmitter → sign: ACh +1; GABA, glutamate, histamine −1; dopamine/octopamine/
  serotonin/unclear +1 (Shiu 2024 treats monoaminergic and unclear neurons as excitatory; `tools/build_flyb.py`
  `DEFAULT_NT_SIGNS`). Sign comes from `consensusNt`, falling back to `predictedNt`
  (conf ≥ 0.5) then `celltypePredictedNt`.
* Retina table: each R1-R6 / R7 / R8 photoreceptor is assigned the medulla column (`assignedOlHex1/2`, 1..36 × 1..39)
  of its strongest hex-bearing lamina/medulla target; L1/L2/L3 of every column are listed too (kinds 3–5).
  5,193 of 6,091 photoreceptors map to columns; right eye is more complete than left (6,047 vs 3,572 entries).

## 2. Brain (`com.fruitfly.brain`, no Minecraft dependency)

* `Connectome` — arrays indexed by dense neuron index; CSR adjacency (pre → post, u16 synapse count); string tables;
  helpers `indicesOfType`, `indicesOfSuperclass`, `indexOfBodyId`.
* `LifConfig` — parameters. Defaults are **exactly Shiu et al. 2024** (`philshiu/Drosophila_brain_model`):
  `dv/dt = (v0 − v + g)/20 ms`, `dg/dt = −g/5 ms`, `g += 0.275 mV × synapses × sign` after a 1.8 ms delay,
  v0 = vreset = −52 mV, threshold −45 mV, refractory 2.2 ms (v and g frozen, inputs still accumulate in g), reset
  sets g = 0. Default `gain = 0.65` (see §6 for the recalibration). Optional stabilisers (off by default):
  `inhibitoryGain`, `maxJumpMv`, spike-frequency adaptation (`adaptIncMv`/`adaptTauMs`), in-degree normalisation
  (`inputNormSynapses`), delta synapses (`synTauMs = 0`), per-population postsynaptic gains
  (`LifNetwork.setPostsynapticGain`). Integration is the exact linear solution per step; `dtMs` 0.5 is the default,
  0.1 matches Brian2's. The integration loop is parallelised over the active set (`threads`, auto = min(8, cores−2));
  spike delivery stays on the owner thread.
* `LifNetwork` — event-driven: only "active" neurons (away from rest, refractory, non-zero g, pending input) are
  integrated. Sensory drive: `setStimulusRate(neuron, Hz)` = Poisson spike generator (as in the paper);
  `setInjectedCurrent` = graded drive. Observation: per-tick spike counts, EMA rates, a spike log for visualisation.
* `PopulationIndex` — spec strings (`DNp09/L`, `prefix:ORN_DM1`, `class:gustatory`, `superclass:vnc_motor`,
  `body:10783`, `all`) → sorted index arrays, cached.
* `BrainRunner` — one daemon thread per fly; 50 ms brain ticks phase-locked to wall clock; queued mutations;
  immutable `BrainSnapshot` for the game thread; reports `realTimeFactor` so the body can slow down if the brain
  cannot keep up (never skips neural time).
* `RetinaGeometry` — maps (side, hex1, hex2) → unit view direction in head coordinates (hex axial → 2-D → spherical;
  orientation parameters). Used both to sample the world and to draw the retina in the HUD.
* `SensoryEncoders` — turn a `SensoryFrame` (abstract, engine-independent) into stimulus rates:
  * Vision: per-column luminance → R1-R6 Poisson rate (`rMax · L^γ`, with light adaptation); R7/R8 get the same
    signal (colour ignored for now); L1/L2/L3 receive a tonic injected current so that the histaminergic
    (inhibitory) photoreceptor input can modulate them (the LIF cannot represent graded hyperpolarisation
    otherwise). Columns without mapped photoreceptors drive L1/L2 directly with a rate ∝ (1 − L).
  * Olfaction: odor sources with per-glomerulus affinity → ORN type rate (sum over sources of
    affinity · concentration, saturating; ORN adaptation with τ ≈ 2 s).
  * Gustation: contact with a food block/item → sugar GRNs (`LB3b`,`LB3c` + pharyngeal `PhG1a-c`), bitter (`LB1a-d`), water (`LB3a`), high salt (`LB3d`); leg GRNs when
    standing on it, labellar when proboscis extended / touching.
  * Mechanosensation: wind/airflow ∝ own speed → JO-C/E; touch/collision → bristle types by body part; sound
    (another fly's song, ambient noise) → JO-A/B; damage → all bristles strongly.
* `MotorDecoder` — DN/MN population rates → `MotorCommand {forward, yaw, lift/flight, actions}`. Populations and
  weights are data (`assets/fruitfly/brain/motor_map.json`): forward = DNp09 + DNa01 + BDN2 …; turn = DNa02/L −
  DNa02/R (ipsilateral turning) …; backward = MDN; halt = DNg60 (bluebell) …; takeoff = DNp07/DNp10 + GF (jump);
  feed = MN9 (proboscis); groom = aDN1/aDN2; song = pIP10 + wing MNs (b1/i1/hg); flight = DLMn/DVMn power MNs.
  Rates are read over the last tick and low-pass filtered; discrete actions use hysteresis thresholds.

## 3. Body (`com.fruitfly.entity`, Fabric 26.2)

* `FlyEntity extends PathfinderMob` with **no vanilla goals**. Each server tick: build `SensoryFrame` from the world
  (retina raycasts on a subset of columns per tick, item/block odor sources within 16 blocks, contact sensing,
  motion), submit to `BrainRunner`, read the latest `BrainSnapshot` → `MotorDecoder` → set velocity/rotation, flight
  state, animations. Physics: hover/fly with no gravity when airborne; walk on surfaces (incl. perching on walls and
  ceilings is out of scope for v1); bounded speeds scaled to fly size (configurable `scale`).
* Entity data synced to clients: flight state, proboscis, wing extension, grooming, brain activity summary.
* `FlyBrainService` — loads the connectome once per server (shared, immutable), creates a `BrainRunner` per fly.
  Fly count is capped by config (default 4) since each brain uses a core.
* Male vs female: same connectome for now (only the male CNS is complete); `sex` flag changes model tint and enables
  courtship-song decoding for males. Two males will court each other if LC10a/P1 activate (that is real biology).

## 4. Client (`com.fruitfly.client`)

* Fly model/renderer (voxel model: head with red eyes, thorax, abdomen with dark male tip, 2 translucent wings,
  6 legs, proboscis, aristae) and animations driven by synced state.
* HUD overlay ("neuroscope", toggle key): brain activity summary, watched population rates, retina view, spike raster.
* Optional in-world brain: `/fruitfly brain build` places the 141k somata as blocks (downsampled) near the player;
  active neurons flash via block updates (throttled).

## 5. Commands

`/fruitfly spawn [male|female] [scale]`, `/fruitfly stim <populationSpec> <hz> [seconds]`, `/fruitfly watch <spec>`,
`/fruitfly stats`, `/fruitfly config <key> <value>`, `/fruitfly brain build|clear`, `/fruitfly feed` (debug: force
sugar), `/fruitfly kill`.

## 6. Calibration and validation (headless `gradlew brainBench` / `visionBench`)

**Weight recalibration.** Shiu's 0.275 mV/synapse was fitted to FlyWire (52.8 M synapses over 127 k neurons). The
male CNS ≥5 graph carries 90.3 M synapses over 176 k neurons (1.24× the input per neuron; the full graph 1.7×), and
at gain 1.0 the network over-excites (Kenyon cells at 64 Hz during a sugar meal, grooming DNs shut down by a
runaway). Following the paper's own recipe (sweep only the weight; require 100 Hz sugar-GRN input to drive MN9 while
the other validated pathways stay stable), **gain 0.65** (≈ 0.18 mV/synapse) is the default: 0.55 is too weak
(MN9 silent), ≥ 0.75 makes grooming collapse and KCs fire. Rejected alternatives: delta synapses (runaway even at
10 % gain), divisive in-degree normalisation (MN9 and the giant fibre go silent), spike-frequency adaptation (kills
the projection-neuron response). Sign convention is Shiu's: ACh, monoamines and unclear +, GABA/glutamate/
histamine −. Kenyon cells get a postsynaptic input gain of 0.25 (documented correction for their known high
threshold / sparse coding); everything else is literal.

| Experiment (gain 0.65, dt 0.5 ms, weight ≥5) | Result |
|---|---|
| Silent brain | 0 spikes (the model has no spontaneous activity) |
| Sugar meal: `LB3b`+`LB3c` @120 Hz, `PhG1a-c` @100 Hz, `LgLG3` @80 Hz (2 s) | G2N-1 20–60 Hz, Fudog (DNg67) 10–40 Hz, Rounddown 60–140 Hz, **MN9 30–90 Hz**, all proboscis/pharyngeal MNs 22–30 Hz, KCs 0, DN average 4–6 Hz; stable for 2 s |
| Bitter `LB1a-d` @150 Hz + sugar | Scapula (GNG087) 380 Hz, **MN9 suppressed to 0–10 Hz** (paper's bitter/Ir94e prediction) |
| Water `LB3a` @100 Hz | Phantom 110 Hz, Usnea 120 Hz, Fudog 80 Hz, MN9 0 |
| Looming: `LC4/R`+`LPLC2/R` @150 Hz | **DNp01 (giant fibre) 330–380 Hz**, DNp04 230–250, DNp02 160–190, DNp03 (contraversive) 100–180, **TTMn jump-muscle MN 60–80 Hz**; visionBench `loom` scene → decoder mode ESCAPE |
| Grooming: JO wind/gravity + JO-F @150 Hz | **aDN1 (DNg62) 180–210 Hz, aDN2 (DNge078) 130–150 Hz**, DNg12 head-grooming DNs ~95 Hz |
| Vision flash (lamina tonic 0.5 mV/ms) | L1/L2/L3 ~40/27/33 Hz in the dark, ~10 Hz under full light, rebound at light-off; medulla (Mi1, Tm1-3) and T4/T5 silent — L1 is glutamatergic, so the ON pathway is structurally unreachable in a silent LIF; looming/objects therefore use the analytic LC4/LPLC2/LC11/LC10a channels |
| Odor `ORN_DM1`+`VA2` @60 Hz | antennal lobe saturates (PNs 430 Hz, ALPN mean 135 Hz) at any ORN rate ≥25 Hz — a known limitation of uniform LIF parameters; KC gain 0.25 keeps KCs at ~1 Hz; DNs show diffuse ~9 Hz activity but no walking command → the body's reflex layer performs odor taxis |
| Speed (32-core machine, 8 worker threads) | sugar meal 25–37 ms per 50 ms tick (1.5× real time); worst case (odor, 100 k active neurons, 28 k spikes/tick) 62 ms (0.77×) — the runner then falls behind rather than skipping neural time |

## 7. Repository layout

```
build.gradle, gradle.properties        Fabric Loom 1.18, MC 26.2, Java 25
tools/fetch_neuprint.py, build_flyb.py Data pipeline
src/main/java/com/fruitfly/brain       Engine-independent brain
src/main/java/com/fruitfly/entity      Fly mob, sensors, motor
src/main/java/com/fruitfly/FruitFlyMod Mod entrypoint, registry, commands
src/client/java/com/fruitfly/client    Renderer, model, HUD
src/main/resources/connectome          Bundled FLYB
src/main/resources/assets/fruitfly/brain  Measured eye map (column_directions.csv)
docs/                                  This file, VALIDATION.md, FOLLOWUPS.md, research/ (7 reports + gaps/)
```

## 8. Late-session additions (after the implementation/review pass)

* **Measured eye map.** `RetinaGeometry` now uses `assets/fruitfly/brain/column_directions.csv` (per-column viewing
  directions for all 1,772 medulla columns, derived from the public optic-lobe column pins matched to the µCT
  ommatidium map of Zhao et al. 2025 — docs/research/gaps/gap-4.md). 1,767 of 1,769 columns get measured
  directions; the linear fallback now has the correct sign (+h = hex1 − hex2 = anterior). Eye extents: azimuth
  −173°…+176°, elevation −80°…+87° per eye.
* **ON/OFF transient channels** (`SensoryEncoders.onOffRMax`): a luminance decrement drives the column's L2/L3
  (ACh → Tm1/Tm2 → T5), an increment drives its Mi1 (→ T4). A sweeping dark bar now evokes Mi1 (up to 150 Hz at
  onset), Tm3, weak Tm1/Tm2 (1–5 Hz), T4 (≤ 4 Hz) and HS optic-flow cells (13–70 Hz). Looming does **not** yet
  ignite LC4/DNp01 from the retinotopic pathway alone at gain 0.65 (nor with the optic lobe at Shiu's literal
  weight); escape therefore still relies on the analytic LC4/LPLC2 channel (`objectChannels`). The independent
  Brian2 study in gaps/gap-2.md obtained emergent, loom-selective DNp01 responses with sustained 60–200 Hz Poisson
  drive on L2/L3 — the recipe to try next (docs/FOLLOWUPS.md).
* **Decoder gating.** Flight is entered only via DNg02 wingbeat-power or take-off DNs (the wing motor-neuron pool
  saturates under odor/mechanosensation and used to lock the fly in FLYING); giant-fibre jumps have a 1.5 s
  refractory; backward walking needs MDN > 20 Hz sustained (diffuse ~13 Hz MDN activity under strong sensory input
  is not a command). With these, `gradlew embodiedBench` reaches FEED for the apple scenario (MN9 60 Hz) and GROOM
  for rain (aDN1 120 Hz), ESCAPE → FLYING → landing for looming.
* **In-world connectome.** `/flybrain build [size]` places the 141,781 somata as stained glass coloured by
  superclass and flashes the linked fly's spiking neurons as sea lanterns (`BrainBuilder`).
* **Independent cross-check of the calibration** (gaps/gap-1.md, 300+ Brian2-equivalent runs on the same graph):
  the Shiu 80 %-MN9 criterion lands at ≈ 0.21 mV/synapse but inside the runaway regime for non-sugar pathways;
  it recommends ≈ 0.125 mV (gain ≈ 0.45) plus an antennal-lobe correction. We keep gain 0.65 with the Kenyon-cell
  input gain because it passes the sugar, bitter, looming and grooming targets in Java; `synapticGain` is a config
  key for anyone who wants the more conservative regime.
