# Fruit Fly Connectome: full reference

[![build](https://github.com/blendi-remade/fly-brain-minecraft/actions/workflows/build.yml/badge.svg)](https://github.com/blendi-remade/fly-brain-minecraft/actions/workflows/build.yml)
[![license: MIT](https://img.shields.io/badge/code-MIT-blue.svg)](LICENSE)
[![data: CC BY 4.0](https://img.shields.io/badge/data-CC%20BY%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by/4.0/)

A Fabric mod for Minecraft 26.2 that puts a whole fruit fly nervous system inside a fly mob. The complete male
*Drosophila melanogaster* central nervous system connectome — **176,422 neurons and 6.29 million connections of five or
more synapses, carrying 90 million of the dataset's 125 million synapses** (neuPrint `male-cns:v1.0`; Berg et al., *Cell*,
3 September 2026) — is simulated in real time as a leaky integrate-and-fire spiking network following Shiu et al. (*Nature*
2024). The Minecraft world drives the fly's *real* sensory neurons (photoreceptors, olfactory and gustatory receptor
neurons, Johnston's organ, bristles), and the activity of its *real* descending and motor neurons (the giant fibre, MN9,
aDN1/aDN2, DNa02, MDN, the wing and leg motor pools ...) is decoded into what the mob does. Some of what the fly does
comes out of the wiring diagram; some of it is hand-built scaffolding. This README says which is which.

Status: 0.1.0, first release. Four sensorimotor pathways are validated end to end (feeding, bitter rejection, looming
escape, grooming); walking to food is mostly a hand-built reflex layer; courtship is wired but not yet demonstrated.

## What the fly can do

| Behaviour | What triggers it in game | Pathway in the connectome | Readout | Origin |
|---|---|---|---|---|
| **Feeds** (extends proboscis, refills hunger) | Standing on or touching cake, honey, berries, melon, fruit, sugar, bread ...; a player offering food by right-click | sugar GRNs (labellar `LB3b`/`LB3c`, pharyngeal `PhG1a-c`, tarsal `LgLG3`/`LgLG4`) -> G2N-1 (`GNG232`) -> Fudog (`DNg67`), Rounddown (`DNge080`) -> **MN9** proboscis motor neuron at 30-90 Hz | `FEED` mode when MN9 > ~20 Hz | **emergent** |
| **Rejects bitter food** | Spider eye, poisonous potato, pufferfish, fermented spider eye, suspicious stew, rotten flesh | bitter GRNs `LB1a-d` -> Scapula (`GNG087`) ~300 Hz -> MN9 silenced, even with sugar present | proboscis stays retracted | **emergent** |
| **Escape jump and takeoff** | An entity approaching or growing in the visual field (a player's swing, a falling block, another mob) | LC4 (expansion velocity) + LPLC2 (angular size) -> **DNp01 giant fibre** 270-370 Hz, DNp04/DNp02/DNp11 -> **TTMn** jump-muscle motor neuron 60-80 Hz | first GF spike -> `ESCAPE`: 30 ms jump away from the threat, then flight | GF decision **emergent**; LC4/LPLC2 drive from the object's angular size is hand-built (see Limitations) |
| **Grooms** (antennae, head, legs, abdomen) | Rain, cobweb/dust, collisions; `/fruitfly groom` | JO wind/gravity + JO-F grooming neurons + head bristles -> **aDN1 (`DNg62`) ~200 Hz, aDN2 (`DNge078`) ~150 Hz**; DNg12/DNg07/DNg08 (head), DNg11 (leg rub), DNp29 (abdomen) | `GROOM` mode with the sub-type from the strongest channel | **emergent** |
| **Walks** forward / backward / turns / halts | Whatever the brain does with its inputs | DNp09 + BDN1-4/oDN1 (forward), DNa02 R-L (+DNg13, DNa01) (yaw), MDN (backward), bluebell `DNg60` / web `DNg74` (halt), Brake `AN19A018` | continuous `forward`, `yaw`, `backward`, `stop` channels | readout **emergent**; speed gains hand-built |
| **Walks toward food, explores** | Nearby odor sources (see the odor table) | ORNs -> antennal lobe -> ... : the LIF antennal lobe saturates and gives no reliable steering DN signal yet | a **hand-built reflex layer** (odor taxis, exploration bouts, collision avoidance) drives walking while the locomotor DN channels are quiet; the HUD shows `[reflex]` when it does | **hand-built** |
| **Flies and lands** | Escape jump, or takeoff/wing-power DN activity | DNp01 spike or DNp11/DNp02/DNp04 (takeoff), `DNg02` wing-power population, wing motor neurons (`subclass:wm`) -> `FLYING`; DNp07/DNp10 -> `LANDING` | state machine with hysteresis and minimum flight time; hover physics | state machine and physics **hand-built**, DN readout emergent |
| **Courtship song hooks** (males) | Another fly seen as a small dark moving object (LC10a), smelled (cVA `DA1`/`DL3` from males, `VA1v`/`VA1d` from females), or tapped (`LgLG5-8` pheromone GRNs) | pC1/P1 (156 cells, a slow arousal state) -> **pIP10** song DN, pMP2 pulse/sine | `SONG` mode with one wing extended | wired, **not yet demonstrated**; two males may court each other if LC10a/P1 activate, which is real biology |

The neuroscope HUD (key **H**) shows the live picture: whole-brain spikes per tick, the watched populations' rates (MN9,
DNp01, aDN1/2, DNa02 L/R, KCs ... configurable), the decoded motor channels, the retina as the fly sees it, a spike raster,
the current mode and whether the brain or the reflex layer is driving.

## Quick start

**Requirements**: Minecraft **26.2**, [Fabric Loader](https://fabricmc.net/use/) **>= 0.19.5**,
[Fabric API](https://modrinth.com/mod/fabric-api) **0.161.0+26.2** (or any 26.2 build), Java **25** or newer.
Give the game a few hundred megabytes of extra heap; 4 GB total is comfortable. The brain uses up to 8 CPU threads per
fly, so a machine with 8+ cores keeps one fly in real time; more flies share the cores (`maxBrains`, default 4).

**Install**: drop `fruitfly-connectome-<version>.jar` and the Fabric API jar into `.minecraft/mods/`. The 23 MB connectome is
inside the jar; nothing is downloaded at runtime. Building from source: `./gradlew build` produces
`build/libs/fruitfly-connectome-0.1.0.jar` (Gradle runs on JDK 25, provisioned automatically if missing; ~1 minute plus
dependency download).

**Play**:

1. Start a world; the connectome loads in the background (~0.5 s) when the server starts.
2. `/fruitfly spawn` (or use the *Fruit Fly Spawn Egg* from the Spawn Eggs creative tab). The chat line tells you how many
   brains are in use.
3. Press **H** for the neuroscope.
4. Drop an apple or place a cake next to the fly, or right-click it while holding food (the food is held to its mouth for
   2 s). Swing at it to make it jump. Make it rain to make it groom.
5. `/fruitfly stats` prints spikes per tick, active neurons, real-time factor and the decoded command for each nearby fly.

### Commands

All commands act on flies near the command source (32 blocks; `stats`, `pause`, `resume`, `kill`: 64 blocks) and need the
usual command permission level.

| Command | Effect |
|---|---|
| `/fruitfly spawn` | Spawn one male fly at your position |
| `/fruitfly spawn male [count]` / `female [count]` | Spawn 1-8 flies of that sex in a ring (females use the same male brain; see Limitations) |
| `/fruitfly spawn big` | Spawn a male at 2.5x scale (easier to watch) |
| `/fruitfly stats` | Connectome and LIF parameters, brains in use, and per fly: sex, scale, hunger, brain tick, spikes/tick, active neurons, real-time factor, decoded command, `[reflex]` flag |
| `/fruitfly stim <population> <hz> [seconds]` | Drive a population as Poisson spike generators at `hz` (0-500) for `seconds` (default 2 s) and add it to the HUD watch list. `population` uses the spec grammar below, quote it if it contains commas |
| `/fruitfly watch <population>` | Add a population to the HUD / telemetry watch list |
| `/fruitfly feed` | Demo: sugar GRNs (`LB3b,LB3c` 120 Hz, `PhG1a-c` 100 Hz, `LgLG3` 80 Hz) for 3 s; watches `MN9`, `GNG232` |
| `/fruitfly bitter` | Demo: bitter GRNs `LB1a-d` 120 Hz for 3 s; watches Scapula `GNG087` |
| `/fruitfly loom` | Demo: right-eye `LC4/R,LPLC2/R` 150 Hz for 0.4 s; watches `DNp01` (the fly jumps) |
| `/fruitfly groom` | Demo: `subclass:wind_gravity,subclass:grooming` 120 Hz for 2 s; watches `DNg62`, `DNge078` |
| `/fruitfly odor` | Demo: `ORN_DM1`, `ORN_VA2` 40 Hz for 3 s; watches `DM1_lPN` |
| `/fruitfly pause` / `resume` | Freeze / unfreeze the brain threads (the body keeps its last command) |
| `/fruitfly senses` | Print what each fly currently senses: mode, odor glomeruli and bearing, taste, wind L/R, visual objects, damage |
| `/fruitfly kill` | Remove all flies within 64 blocks |
| `/flybrain build [size]` | Put the connectome into the world: the 141,781 neurons with a reconstructed soma are binned into a `size`-block structure (default 64) of stained glass coloured by superclass (cyan optic lobe, white central brain, lime nerve cord, yellow sensory, red descending, orange motor, light-blue ascending, magenta visual projection); placed ~2,500 blocks per tick, auto-linked to the nearest fly so its spiking neurons flash as sea lanterns |
| `/flybrain link` | Link the structure(s) to the nearest fly with a running brain |
| `/flybrain status` | Cells placed / queued and which fly is linked |
| `/flybrain clear` | Remove the structure(s) (restores air) |

**Brain view (pinned live map, key `B` or `/brainview`)** — a projection of the whole CNS in the top-left corner
(dorsal by default: optic lobes left/right at the top, nerve cord below). Every telemetry tick the sampled spiking
neurons light up at their soma positions and fade over ~300 ms; below the map are per-region spike counts with
peak-hold and a spikes-per-tick history. Every fly gets a persistent number, an identity colour and a matching
coloured name tag (`Fly-12 ♂`); the same colour heads the brain view and neuroscope panels and draws a rotating ring
above the fly whose brain you are looking at, so with several flies it is never ambiguous.

| Client command | Effect |
|---|---|
| `/brainview` / `/brainview on\|off` | Toggle the pinned brain map |
| `/brainview lock` | Lock onto the fly currently shown (otherwise the nearest fly with telemetry is followed) |
| `/brainview lock <number>` | Lock onto `Fly-<number>` (the number on its name tag; falls back to the network id if no fly has that number) |
| `/brainview look` | Lock onto the fly under your crosshair |
| `/brainview nearest` | Back to automatic nearest-fly selection |
| `/brainview view dorsal\|frontal\|side` | Projection axis |
| `/brainview size <0.15..0.75>` | Maximum panel height as a fraction of the screen (default 0.60) |
| `/brainview list` | Nearby flies with number, colour, distance, telemetry state and focus |
| `/brainview neuroscope` | Toggle the neuroscope panel (same as `H`) |

**Population specs** (used by `stim`, `watch`, `hudPopulations`, and the benches): a comma-separated union of terms, each
optionally restricted to a side with `/L`, `/R` or `/M`, terms may be intersected with `&`.

| Term | Meaning | Example |
|---|---|---|
| `DNp09` | exact neuPrint type | `MN9`, `DNa02/L`, `LC4/R` |
| `prefix:ORN_DM1` | type prefix | `prefix:pC1_`, `prefix:KC`, `prefix:DNg02/L` |
| `contains:LC10` | type contains substring | |
| `class:gustatory` | neuPrint class | `class:Kenyon_Cell`, `class:ALPN`, `class:olfactory` |
| `superclass:descending_neuron` | superclass | `superclass:vnc_motor`, `superclass:cb_motor` |
| `subclass:wm` | subclass | `subclass:wind_gravity`, `subclass:auditory`, `subclass:fl` (front-leg MNs) |
| `nt:gaba`, `nerve:ADMN`, `neuromere:T1`, `dimorphism:male-specific`, `frudsx:fru` | other annotations | `class:mechanosensory_tactile&nerve:ADMN` |
| `body:10783` | one neuron by bodyId | |
| `all` | every neuron | |

### Configuration (`config/fruitfly.json`)

Written with defaults on first start; edit and restart. All keys of `FruitFlyConfig`:

| Key | Default | Meaning |
|---|---:|---|
| **brain** | | |
| `brainDtMs` | 0.5 | Integration step in ms. 0.5 runs in real time; 0.1 is Brian2's default and ~4x slower (results are equivalent: the integrator is exact) |
| `brainMsPerTick` | 50 | Milliseconds of neural time per game tick. 50 = real time; 25 = half-speed "bullet time" |
| `synapticGain` | 0.65 | Global multiplier on 0.275 mV/synapse (see How it works) |
| `brainThreads` | 0 | Worker threads per brain; 0 = min(8, cores - 2) |
| `maxBrains` | 4 | Flies simulated at once; extra flies get a reflex-only body until a brain frees up |
| `kenyonCellInputGain` | 0.25 | Postsynaptic input scaling of Kenyon cells (sparse-coding correction; 1 = literal) |
| `projectionNeuronInputGain` | 1.0 | Same for antennal-lobe projection neurons |
| `connectomeFile` | "" | Path to an alternative FLYB file (empty = bundled male-cns v1.0) |
| **senses** | | |
| `vision` | true | Cast retina rays and drive photoreceptors / lamina |
| `objectVision` | true | Track entities as visual objects (looming, small-object, fly-like channels) |
| `raysPerEye` | 150 | Coarse rays per eye the columns are grouped into (~130 after grouping; half are cast each tick) |
| `visionRayLength` | 24 | Ray length in blocks |
| `odorRadius` | 16 | Item odor sources are sensed within this many blocks |
| `odorFalloffBlocks` | 6 | Odor concentration falls as exp(-distance / this) |
| `maxOrnRateHz` | 120 | ORN firing rate at saturating odor |
| `laminaTonicMvPerMs` | 0.5 | Tonic depolarising drive of L1/L2/L3 that photoreceptor input suppresses (~40 Hz in darkness) |
| **body** | | |
| `flyScale` | 1.0 | Visual and hitbox scale; 1.0 = 0.5 x 0.3 blocks (about 170x a real fly). Speeds scale with it |
| `walkSpeedBlocksPerS` | 3.0 | Walking speed at full forward drive |
| `flightSpeedBlocksPerS` | 8.0 | Flight speed at full drive |
| `turnRateDegPerS` | 300 | Yaw rate at full yaw drive while walking |
| `flightTurnRateDegPerS` | 600 | Yaw rate in flight |
| `reflexLayer` | true | Hand-built odor taxis / exploration / collision avoidance when the brain issues no locomotor command |
| `flightEnabled` | true | Allow the flight state; false keeps the fly on the ground |
| `spawnEggInCreativeTab` | true | Add the spawn egg to the Spawn Eggs tab |
| **telemetry / debug** | | |
| `telemetryEveryTicks` | 2 | Send brain telemetry to tracking players every N ticks |
| `telemetryRangeBlocks` | 64 | ... within this range |
| `spikeSampleSize` | 1024 | Spiking neurons sampled per packet (reservoir sample of the whole tick) for the brain view and brain cloud |
| `hudPopulations` | 22 specs | Populations whose rates the neuroscope shows: `DNp09, DNg100, DNa02/L, DNa02/R, MDN, DNg60, DNp01, DNp07, DNp10, DNg62, DNge078, MN9, GNG232, pIP10, prefix:pC1_, LC4, LPLC2, LC10a, class:Kenyon_Cell, class:ALPN, superclass:descending_neuron, superclass:vnc_motor` |

## How it works

```
 Minecraft world ──sensors──▶ SensoryFrame ──encoders──▶ Poisson drives on real sensory neuron types
                                                            │
                                                 LifNetwork (brain thread, 50 ms brain tick = 1 game tick)
                                                            │
 FlyEntity movement ◀──MotorDecoder◀── BrainSnapshot (DN / MN population rates)
```

Each server tick `FlyEntity` samples the world into a `SensoryFrame` (`WorldSenses`), hands it to the fly's brain thread,
where `SensoryEncoders` turns it into firing rates on the real sensory neuron types and `LifNetwork` advances 50 ms of
neural time; `MotorDecoder` then reads descending and motor neuron populations into a `MotorCommand`, and on the next
game tick `FlyBody` turns that command into velocity, rotation, flight state and animation. The brain code
(`com.fruitfly.brain`) has no Minecraft dependency and is also driven by the headless benches.

### The neuron model

Every one of the 176,422 neurons is the same current-based leaky integrate-and-fire unit with an exponential synapse,
exactly as in Shiu et al. 2024 (`philshiu/Drosophila_brain_model`, Brian2):

```
tau_m dv/dt = (v_rest - v) + g           tau_m = 20 ms
tau_s dg/dt = -g                         tau_s = 5 ms
spike of neuron j, after a 1.8 ms delay:  g_i += sign_j * N_ji * 0.275 mV * gain      for every target i
v > -45 mV  ->  spike;  v = -52 mV,  g = 0,  refractory 2.2 ms (v and g frozen, inputs still accumulate)
```

`N_ji` is the synapse count of the connection (from the connectome, minimum 5). `v_rest = v_reset = -52 mV`, so the threshold
is 7 mV above rest and ~160 simultaneous synapses are needed to fire a resting cell from one presynaptic spike. There is no
spontaneous activity: a silent brain stays silent, every spike traces back to a sensory neuron. Integration is the exact
linear solution per step (`dt` 0.5 ms in game, 0.1 ms reproduces Brian2 to 1e-13 mV), and only the *active set* — neurons
away from rest, in refractory, or with pending input — is integrated, so cost scales with activity (typically 15-100 k active
neurons and 2-40 k spikes per tick) rather than network size. Sensory neurons are driven as Poisson spike generators at the
encoder's rate, as in the paper; lamina cells additionally receive a constant injected current.

**Gain 0.65.** Shiu et al.'s single free parameter, 0.275 mV per synapse, was calibrated on the FlyWire v630 female brain
(52.8 M synapses over 127 k neurons, no synapse threshold). The male CNS graph carries more synapses per neuron (125 M over
176 k, of which 90 M survive the >= 5 threshold), and the literal weight over-drives it. The weight was recalibrated with the
paper's own recipe: the smallest global gain at which 100-120 Hz sugar input drives MN9 robustly while grooming and escape
stay stable and Kenyon cells stay silent. That is 0.65 (0.179 mV per synapse); 0.55 is too weak, at 0.75 grooming input
tips the network into runaway excitation (`docs/VALIDATION.md` section 9). Two further hand-built corrections: Kenyon-cell
inputs are scaled by 0.25 (they should be nearly silent and were not), and connections with fewer than 5 synapses are
omitted.

**Sign convention** (whole-neuron sign, Dale's law, |E| = |I| per synapse): acetylcholine, dopamine, octopamine, serotonin
and *unclear* are **excitatory (+)**; GABA, **glutamate** and **histamine** are **inhibitory (-)**. Glutamate is inhibitory in
the adult fly (GluCl); histamine is the photoreceptor transmitter and inverts sign onto the lamina. The transmitter comes from
neuPrint's `consensusNt`, falling back to `predictedNt` (confidence >= 0.5) and then the cell-type prediction. Result: 116,390
excitatory, 60,032 inhibitory neurons. Exact rule and counts in [PROVENANCE.md](PROVENANCE.md).

### From the world to sensory neurons

All encoders use the same saturating law `rate = r_max * S^1.5 / (0.2^1.5 + S^1.5)` on a stimulus strength `S` in 0..1, with
slow adaptation where the biology has it, so that *encounters* rather than steady levels drive the network.

**Olfaction** — every odor source emits a *glomerular drive vector*; drive per glomerulus is summed over sources with
`strength * exp(-distance / 6 blocks)` (items within 16 blocks, blocks within 5), then adapted (tau 2 s, 60 % of the steady
level removed) and split left/right by the bearing of the dominant source (+/-30 %) before driving the `ORN_<glomerulus>`
types on each side (`OdorTable`, from `docs/research/sensory-mapping.md`):

| Source (items / blocks) | Glomeruli driven (weight) | Note |
|---|---|---|
| Apple, golden apple (x1.5), chorus fruit | DM1 1.0, DM2 0.9, VA2 0.6, DM4 0.5, VM2 0.4, DM3 0.3 | fruit esters |
| Sweet / glow berries, berry bush, cave vines | DM1 0.8, DM2 0.7, VA2 0.5, DM4 0.4, VL2a 0.3 | |
| Melon | DM1 0.7, DM2 0.6, VM5d 0.5, VA2 0.4 | |
| Honey bottle / block / comb, bee nest, hive | VL2a 0.9, D 0.6, DM1 0.4, VA2 0.3 | phenylacetaldehyde (Ir84a), courtship-priming |
| Cake, cookie, pumpkin pie, bread, sugar, composter | VA2 0.8, DP1l 0.6, DM1 0.5, VM2 0.4, DL2d 0.3 | yeast / fermentation |
| Fermented spider eye | DP1l 0.9, VA2 0.7, DM5 0.6, DM1 0.5 | DM5 is aversive at high concentration |
| Rotten flesh | VM1 1.0, VM6v/m/l 0.9, VC5 0.9, DA2 0.8, VM4 0.7, DP1l 0.5 | ammonia/amines (attractive) vs geosmin-like (aversive) |
| Spider eye, poisonous potato, pufferfish, beetroot (weak) | DA2 1.0, DL4 0.4 | dedicated aversive lines |
| Mushrooms | DC2 0.8, VA3 0.5, VM5d 0.3 | 1-octen-3-ol |
| Wheat, hay, carrot, potato, leaves, flowers | DL5 0.7, VA3 0.5, D 0.3 (flowers: VL2a 0.7, D 0.6, VA6 0.5) | green-leaf volatiles / floral |
| Meat (raw/cooked), dried kelp | VM1, VC1, DP1l (weak) | |
| Torch, campfire, fire | V 0.9 (CO2), VC2 0.8 (smoke), DP1l 0.4, VP2 0.7 (heat) | aversive |
| Lava, magma | VP2 1.0 (heat), V 0.6, VP4 0.8 (dry) | thermosensory VP glomeruli |
| Ice, snow, water | VP3a/b 1.0 (cold), VP5 (moist) | hygro/thermosensory |
| Another male fly | DA1 (cVA), DL3 | suppresses male-male courtship |
| Another female fly | VA1v 0.9, VA1d 0.8 | volatile female cues |
| Player | V 0.4 (exhaled CO2), VM1 | weak and aversive, deliberately: flies do not seek humans |

**Gustation** — fires only on contact (`TasteTable`; tarsal GRNs when standing on or touching the food, labellar and
pharyngeal GRNs only while the proboscis is extended, i.e. while the `feed` channel is above 0.2):

| Food | Tarsal GRNs | Labellar / pharyngeal GRNs | Nutrition |
|---|---|---|---|
| Sugar, honey, cake, cookie, pie, sweet/glow berries | LgLG3 1.0, LgLG4 0.8, WG2 0.6 | LB3b 1.0, LB3c 1.0, PhG1a-c 0.9 | 1.0 |
| Apple, melon, carrot, bread, beetroot, chorus fruit | LgLG3 0.8, LgLG4 0.6 | LB3b 0.8, LB3c 0.8, LB3a 0.4, PhG1a-c 0.7 | 0.8 |
| Water, rain, potion | - | LB3a 1.0, PhG3 0.8, PhG4 0.8 | 0 |
| Spider eye, poisonous potato, pufferfish, fermented eye, suspicious stew | LgAG1 1.0 | LB1a-d 1.0 | -0.5 |
| Dried kelp, sea pickle | - | LB3d 0.9 (high salt), LB1e 0.4 | 0.1 |
| Meat, fish | - | LB1e 0.9, dorsal_tpGRN 0.9, PhG16 0.6 | 0.5 |
| Rotten flesh | LgAG1 0.7 | LB1a-d 0.6, LB1e 0.8 | 0.1 |
| Tapping a female fly | LgLG5-8 1.0, LgLG1a/b 0.9 | - | pheromone |
| Tapping a male fly | LgAG1 0.9, LgLG2 0.5 | - | pheromone |

**Vision** — the connectome's medulla column coordinates (`assignedOlHex1/2`) give 877 left + 892 right columns, each mapped
to a view direction (`RetinaGeometry`: dorsoventral axis `hex1 + hex2`, anteroposterior axis `hex1 - hex2`; elevation -70..80
deg, azimuth -10..155 deg per eye). Photoreceptors were assigned to the column of their strongest lamina target (5,193 of
6,091). The game casts ~130 coarse rays per eye (half of them each tick, 24 blocks) and reads luminance = light level x
(0.3 + 0.7 x block albedo); entities are painted as dark objects onto the columns they cover. Per column, luminance (after
Weber-like adaptation, tau 1 s) drives R1-R6/R7/R8 at up to 150 Hz; because photoreceptors are inhibitory, L1/L2/L3 get a
tonic 0.5 mV/ms drive that light suppresses (~35 Hz in darkness, ~10 Hz in light). The lamina signal does **not** propagate
through the medulla in this model (Mi1/Tm/T4/T5 stay silent), so feature channels are also driven analytically from the
tracked objects: **LC4** (expansion velocity), **LPLC2** (Gaussian of angular size around 60 deg), **LC11/LC18** (small moving
objects < 15 deg), **LC10a** (fly-like targets in the frontal field), **LC15** (bars), **HS/VS** (self-rotation optic flow), each
on the eye that sees them.

**Mechanosensation and the rest** — own airspeed (and drift) deflects the antennae -> `subclass:wind_gravity` JO neurons,
left and right separately (wind azimuth is left to the network); a conspecific's song -> `subclass:auditory` JO-A/B; rain,
cobweb, dust -> JO-F grooming neurons + `BM_InOm` / `BM_Taste` head bristles; collisions -> head and leg bristles
(`class:mechanosensory_tactile` by nerve: ProLN/MesoLN/MetaLN legs, ADMN wing, PDMN/DMetaN notum); damage -> all
bristles strongly; flight -> haltere campaniform proprioceptors; standing -> leg hair plates; biome temperature and
rain/water -> thermo- (`TRN_VP2` hot, `TRN_VP3a/b` cold) and hygrosensory (`HRN_VP4` dry, `HRN_VP5` moist) neurons.

### From descending and motor neurons to movement

`MotorMap` declares 21 command channels as weighted sums of normalised population rates (rate / r_max, clipped to 1), each
low-pass filtered with its own time constant; `MotorDecoder` arbitrates them with a priority ladder, hysteresis
(a mode is left at half its entry threshold) and minimum dwell times (250 ms). From `docs/research/dn-behavior.md`:

| Channel | Populations (weight, r_max Hz) | tau | Notes |
|---|---|---:|---|
| `forward` | DNp09 0.3, DNg100/BDN2 0.25, DNge053/BDN1 0.15, DNge050/BDN4 0.15, DNg97/oDN1 0.15 (50) | 150 ms | Sapkal 2024 walking DNs; DNg100 is *not* a halting neuron |
| `yaw` | DNa02/R +1, DNa02/L -1 (50); DNg13 +/-0.5 (100); DNa01 +/-0.25 (50) | 100 ms | right minus left, ipsiversive; DN activity leads behaviour by ~150 ms |
| `backward` | MDN 1.0 (16) | 150 ms | 4 cells; baseline 8.5 Hz, active 12-16 Hz |
| `halt` | DNg60 bluebell 0.6, DNg74_a/b web 0.4 (50) | 250 ms | GABAergic stop DNs |
| `brake` | AN19A018 Brake 1.0 (50) | 250 ms | ascending neuron, 12 cells; overrides all walking |
| `jump` | DNp01 giant fibre | event | **any spike in the tick** |
| `takeoff` | DNp11 0.5, DNp02 0.25, DNp04 0.25 (50) | 25 ms | forward vs backward takeoff direction |
| `landing` | DNp07 0.5 (92), DNp10 0.5 (50) | 25 ms | 21-26 ms latency in the fly |
| `flightPower` / `flightYaw` | all 29 `DNg02` cells; DNg02 L-R (contralateral wing) and DNp03 R-L (contraversive) | 100 ms | graded wingbeat amplitude |
| `wingMotor` | `subclass:wm` (30) | 100 ms | 67 wing motor neurons |
| `groomAntenna` / `groomHead` / `groomLeg` / `groomAbdomen` | DNg62 + DNge078; DNg12 (42 cells) + DNg07 + DNg08; DNg11; DNp29 | 300 ms | |
| `feed` | MN9 0.6 (60); DNg67 Fudog, DNge080 Rounddown, DNge173/174 Bract 0.1 each | 100 ms | MN9 is necessary and sufficient for rostrum extension |
| `courtship` | `prefix:pC1_` (156 cells, 20) | 30 s | a persistent arousal state |
| `song` / `songPulse` | pIP10 (50); pMP2 | 500 / 50 ms | pulse vs sine mode |
| `legMotor` / `legMotorAsym` | `subclass:fl,ml,hl` leg motor pools; right minus left (30) | 150 ms | a second, motor-level estimate of gait drive and turning |

Priority ladder (highest first): **escape** (a GF spike; 200 ms lockout) > **landing** (only while flying) > **flying**
(wing power or takeoff above threshold, minimum 400 ms) > **brake** > **halt** > **backward** (gates forward: MDN inhibits
forward walking, so the two are never summed) > **forward** (+ yaw on top) > **feed** > **groom** > **song** > idle.

`FlyBody` (all hand-built) applies absolute gains — 3 blocks/s walking, 8 blocks/s flight, 300 / 600 deg/s turning, scaled by
`flyScale` — the ~30 ms giant-fibre jump away from the largest looming object, hover altitude control, friction and
step-up, proboscis animation from the `feed` channel, and the optional reflex layer. The reflex layer only acts when the
brain issues no locomotor command and no stationary mode is active; the HUD and `/fruitfly stats` show when it does.

### Emergent vs hand-built

The design contract (from `docs/research/embodied-precedents.md` section 11), as implemented:

| Comes from the connectome (emergent) | Hand-built in the body / world |
|---|---|
| Which DNs/MNs light up for a given sensory pattern: taste -> MN9; bitter -> Scapula -> MN9 off; JO -> aDN1/aDN2; looming -> giant fibre / TTMn / DNp03 | Retina rasteriser, hex -> angle map, luminance normalisation and adaptation, painting entities onto columns |
| Left-right steering asymmetry (DNa02), forward vs backward antagonism (DNp09/BDN2 vs MDN), halting (bluebell / web / Brake) | Odor field (item and block tables, exponential fall-off), ORN dose-response and adaptation, bilateral gain from bearing |
| Sequencing among DN clusters (within-cluster excitation, between-cluster inhibition), relative population-coding gains | Absolute gains DN rate -> blocks/s and deg/s; thresholds, hysteresis, dwell times, escape lockout; the priority ladder itself |
| Sensory-specific pathways (bitter suppresses feeding at the premotor level) | Internal state: hunger, flight-state gating, the pC1 courtship time constant; there is no neuromodulation or spontaneous activity in the model |
| The single-spike giant-fibre decision | Jump and flight physics, hover altitude, landing on contact |
| Grooming sub-type from the strongest DN group | Leg / wing / proboscis animation, sounds |
| | **Feature-level visual drive** (LC4/LPLC2/LC11/LC18/LC10a/HS/VS from tracked objects) because the medulla is silent |
| | **Kenyon-cell input gain 0.25**, synapse threshold >= 5, global gain 0.65 |
| | **The reflex layer**: odor taxis, exploration bouts, collision avoidance when the brain is quiet |

## Validation

Full protocol, per-tick tables, gain sweep and rejected alternatives: [docs/VALIDATION.md](docs/VALIDATION.md). Headline
results (headless `gradlew brainBench` / `visionBench`, dt 0.5 ms, gain 0.65, rates are population means over one 50 ms
tick):

| Experiment | Result |
|---|---|
| Silent brain | 0 spikes: no spontaneous activity, every spike below is stimulus-driven |
| Sugar GRNs `LB3b`/`LB3c` 120 Hz + `PhG1a-c` 100 Hz + `LgLG3` 80 Hz | G2N-1 (`GNG232`) 20-60 Hz, Fudog (`DNg67`) 10-40 Hz, Rounddown (`DNge080`) 50-120 Hz, **MN9 30-90 Hz**, Kenyon cells 0 — the paper's sugar -> proboscis-extension pathway |
| Bitter GRNs `LB1a-d` 120 Hz | Scapula (`GNG087`) ~300 Hz, MN9 0 |
| Bitter + the sugar stimulus above | **MN9 0 Hz** throughout (G2N-1 and Fudog still fire): suppression acts at the premotor level |
| Looming `LC4/R` + `LPLC2/R` 150 Hz | **DNp01 giant fibre 330-370 Hz**, DNp04 210-240, DNp02 150-170, DNp11 140-160, **TTMn 60-80 Hz**, KCs 0 -> decoder `ESCAPE` |
| JO `wind_gravity` + `grooming` 150 Hz | **aDN1 (`DNg62`) ~200 Hz, aDN2 (`DNge078`) ~150 Hz**, bluebell 0, KCs 0 |
| Retina flash (dark / light) | L1-L3 tonic **~35 Hz in darkness, ~10 Hz in light**; Mi1/Tm/T4/T5 silent |
| Retina loom (expanding disc, right eye) | LC4 76 -> 180 Hz, LPLC2 49 -> 187 Hz, GF 270-370 Hz, **JUMP within 100 ms of loom onset**; GF back to 50 Hz one tick after the disc vanishes |
| ORN_DM1 + ORN_VA2 at 40 Hz | DM1 PNs saturate at ~410 Hz, all PNs ~134 Hz mean, KCs ~1 Hz (with the 0.25 input gain), DNs a diffuse ~9 Hz: the antennal lobe is the model's weak spot |
| Gain sweep 0.55 / 0.65 / 0.75 | 0.55: MN9 10-60 Hz, intermittent. 0.65: MN9 30-90 Hz sustained, grooming stable. 0.75: MN9 40-110 Hz but under grooming input spikes/tick quintuple, KCs fire at 39 Hz and aDN1/2 collapse to 0 |
| Speed (i9-14900KF, 8 threads) | 60-100 k active neurons and 2-40 k spikes per 50 ms tick cost 23-66 ms of compute: 0.8-1.5x real time for one fly; dt 0.1 ms is ~4x slower |
| Unit tests | `./gradlew test`: 10/10 pass (exact alpha-synapse PSP, threshold arithmetic, Poisson rate, delay/refractory bookkeeping) |

## Performance

- **One brain thread per fly** plus a shared pool of up to 8 worker threads per brain (`brainThreads`), stepping the network in
  50 ms ticks phase-locked to the game tick; sensory input and motor decoding happen on the brain thread, the game thread only
  exchanges immutable snapshots. Server-thread cost per fly is ~130 raycasts plus entity scans per tick (about 1-2 ms).
- **`maxBrains`** (default 4) caps simultaneous brains; further flies keep a reflex-only body and pick up a brain when one
  is released.
- **Real-time factor.** The brain never skips neural time. If a tick's compute exceeds 50 ms the snapshot lags and
  `realTimeFactor` drops below 1 (shown in `/fruitfly stats` and the HUD); the body slows accordingly. Expensive stimuli
  (bitter, strong odor) reach ~0.8x on a fast desktop. Remedies: fewer flies, `brainMsPerTick = 25`, or a smaller brain built
  with `--exclude-superclass ol_intrinsic,visual_projection` (see below).
- Memory: the connectome is loaded once per server (~47 MB raw plus indices); each brain adds per-neuron state (a few tens of
  MB). Telemetry packets go every 2 ticks to players within 64 blocks.

## Rebuilding the data

The bundled file is a derivative of neuPrint `male-cns:v1.0`; rebuild it (or build variants) in about two minutes:

```bash
pip install requests numpy pandas
python tools/fetch_neuprint.py            # anonymous Cypher, weight >= 5, 6 workers, resumable -> data/raw/male-cns_v1.0/
python tools/build_flyb.py                # -> src/main/resources/connectome/malecns-v1.0.flyb.gz + docs/connectome-stats.json
./gradlew brainBench -Pms=600 "-Pstim=LB3b:120;LB3c:120" "-Preport=MN9;GNG232" "-Pcfg=dtMs=0.5"   # smoke test
```

No token is needed (neuPrint allows anonymous read access to this public dataset; a token from
https://neuprint.janelia.org/account can be passed with `--token` or `NEUPRINT_APPLICATION_CREDENTIALS`). Useful variants:
`--min-weight 1` fetches all ~25.9 M connections (slower, ~4x larger file); `build_flyb.py --exclude-superclass
ol_intrinsic,visual_projection` builds a brain-only file without the 99 k optic-lobe neurons; `--nt-signs
"unclear=0,dopamine=0"` changes the sign map; `--drop-orphans` removes unconnected neurons. Point `connectomeFile` in the
config at the result, or pass `-Pflyb=...` to the benches. Every neuron keeps its neuPrint `bodyId`, so anything you see
in the HUD can be looked up at https://neuprint.janelia.org/?dataset=male-cns%3Av1.0 . Exact queries, transformations and
file hashes are in [PROVENANCE.md](PROVENANCE.md).

## Limitations

- **Uniform LIF.** Every neuron has the same membrane, every synapse the same weight per contact. Consequences seen in the
  benches: the **antennal lobe saturates** (projection neurons at ~400 Hz from a modest odor), and the **medulla motion
  pathway is silent** (Mi1/Tm/T4/T5 never fire), so looming, small-object, fly-target and optic-flow signals are painted
  onto LC4/LPLC2/LC11/LC18/LC10a/HS/VS analytically instead of emerging from the lamina. Optomotor behaviour does not exist.
- **No neuromodulation, no neuropeptides, no gap junctions, no spontaneous activity.** Dopamine/octopamine/serotonin
  neurons act as fast excitatory synapses; hunger and arousal states are hand-built; inhibition onto a silent neuron has no
  effect (Shiu et al.'s own stated failure mode).
- **Weight threshold.** Connections with fewer than 5 synapses (76 % of connections, 28 % of synapses) are omitted, and the
  global gain compensates only on average.
- **Retina orientation is approximate.** The hex -> angle map is linear and about +/-25 % off at the periphery; the
  anterior/posterior sense of the horizontal axis is configurable because it has not been verified against the eye-map
  pins. The left optic lobe is less completely reconstructed than the right (3,572 vs 6,047 retina entries).
- **Only the male CNS exists.** Female flies use the same brain with a different tint; sexually dimorphic circuits are male.
- **Walking to food is largely the reflex layer**, not the connectome. The HUD is honest about it.
- **Absolute rates are not to be trusted** (the paper says the same); population means for two-cell types are quantised to
  10 Hz per 50 ms tick; a real-time factor below 1 slows the fly rather than the world.
- Sensory neurons are driven as Poisson spike generators at the encoder's rate (as in the paper) while still integrating
  their own synaptic input, so a stimulated population can fire somewhat above the requested rate.

## Data sources and citations

This mod embeds a compact, thresholded derivative (connections with >= 5 synapses, re-indexed) of the
**male Drosophila central nervous system connectome, neuPrint dataset `male-cns:v1.0`**
(segmentation UUID 4b2087c0fbe046bfaf0d60bc970e3e5d, database edit 2026-06-08), produced by the
FlyEM Project Team at HHMI Janelia Research Campus, the Drosophila Connectomics Group (University of
Cambridge / MRC Laboratory of Molecular Biology) and Google Research. The dataset is licensed under
**CC BY 4.0** (https://creativecommons.org/licenses/by/4.0/). Source: https://male-cns.janelia.org/ and
https://www.janelia.org/project-team/flyem/male-cns-connectome . Modifications: synapse-count threshold,
integer re-indexing, quantised weights; see PROVENANCE.md.

If you use this mod or its data in research or teaching, please cite:

- Berg S, Beckett IR, Costa M, Schlegel P, Januszewski M, Marin EC, Nern A, Preibisch S, Qiu W,
  Takemura S, et al. **Sexual dimorphism in the complete Drosophila male central nervous system
  connectome.** *Cell* 189(18):5504–5526.e15 (2026). https://doi.org/10.1016/j.cell.2026.08.015
  (preprint: bioRxiv 2025, https://doi.org/10.1101/2025.10.09.680999)
- Nern A, Loesche F, Takemura S, et al. **Connectome-driven neural inventory of a complete visual
  system.** *Nature* 641:1225–1237 (2025). https://doi.org/10.1038/s41586-025-08746-0
- Takemura S, Hayworth KJ, Huang GB, et al. **A Connectome of the Male Drosophila Ventral Nerve Cord.**
  *eLife* 13:RP97769 (2024). https://doi.org/10.7554/eLife.97769
- Marin EC, Morris BJ, Stürner T, et al. **Systematic annotation of a complete adult male Drosophila
  nerve cord connectome reveals principles of functional organisation.** *eLife* 13:RP97766 (2024).
  https://doi.org/10.7554/eLife.97766
- Cheong HSJ, Eichler K, Stürner T, et al. **Organization of circuits linking descending input to motor
  output in the Drosophila Male Adult Nerve Cord connectome.** *eLife* 13:RP96084 (2026).
  https://doi.org/10.7554/eLife.96084
- Hoeller J, Zhao A, Nern A, Rogers EM, Romani S, Reiser MB. **The organization of visual pathways in
  the Drosophila brain.** *Cell* 189(18):5552–5570.e10 (2026). https://doi.org/10.1016/j.cell.2026.08.014
- Tastekin I, de Haan Vicente I, Beresford RJ, et al. **The complete gustatory connectome of adult
  Drosophila reveals how taste guides feeding, foraging, and social behavior.** *Cell*
  189(18):5527–5551.e5 (2026). https://doi.org/10.1016/j.cell.2026.08.016
- Rubin GM, Managan CM, Dreher M, et al. **Networks of sexually dimorphic neurons that regulate social
  behaviors in Drosophila.** *Current Biology* (2026). https://doi.org/10.1016/j.cub.2026.08.013
- Plaza SM, Clements J, Dolafi T, et al. **neuPrint: An open access tool for EM connectomics.**
  *Front. Neuroinform.* 16:896292 (2022). https://doi.org/10.3389/fninf.2022.896292

The spiking (leaky integrate-and-fire) approach follows:
- Shiu PK, Sterne GR, Spiller N, et al. **A Drosophila computational brain model reveals sensorimotor
  processing.** *Nature* 634:210–219 (2024). https://doi.org/10.1038/s41586-024-07763-9
  (code: https://github.com/philshiu/Drosophila_brain_model, MIT)

The descending-neuron decoding draws on Sapkal N et al. *Nature* 634:191–200 (2024, walking and halting DNs); Braun J et al.
*Nature* 630:686 (2024, DN clusters); Rayshubskiy A et al. *eLife* 102230 (DNa02 steering); Yang HH et al. *Cell* 187:6290
(2024, DNg13); von Reyn CR et al. *Nat Neurosci* 17:962 (2014) and Ache JM et al. *Curr Biol* 29:1073 (2019, giant fibre and
looming); Ache JM et al. *Nat Neurosci* 22:1132 (2019, landing DNs); Hampel S et al. *eLife* 4:e08758 (2015, aDN1/aDN2);
Engert S et al. (2022, GRN types); Namiki S et al. *eLife* 11 (2022, DNg02). Details and confidence ratings are in
`docs/research/`.

### Optional female-brain comparison data (not bundled)
No female-brain data ships with the mod and no fetch script for it exists yet. If a female mode is built, the candidate
sources are the FlyWire FAFB v783 public release (Princeton University and the FlyWire Consortium), licensed
**CC BY-NC 4.0** (https://flywire.ai/guidelines) and therefore never to be bundled, cite:
- Dorkenwald S, Matsliah A, Sterling AR, et al. **Neuronal wiring diagram of an adult brain.** *Nature*
  634:124–138 (2024). https://doi.org/10.1038/s41586-024-07558-y
- Schlegel P, Yin Y, Bates AS, et al. **Whole-brain annotation and multi-connectome cell typing of
  Drosophila.** *Nature* 634:139–152 (2024). https://doi.org/10.1038/s41586-024-07686-5
- Matsliah A, Yu S, Kruk K, et al. **Neuronal parts list and wiring diagram for a visual system.**
  *Nature* 634:166–180 (2024). https://doi.org/10.1038/s41586-024-07981-1

or BANC v888 (female brain + nerve cord, **CC BY 4.0**, https://doi.org/10.7910/DVN/7WTH1N): Bates AS, Phelps JS, Kim M,
et al. **Distributed control circuits across a brain-and-cord connectome.** *Nature* 656:957–970 (2026).
https://doi.org/10.1038/s41586-026-10735-w

This project is not affiliated with or endorsed by HHMI, Janelia, Google, the University of Cambridge,
the MRC LMB, Princeton University or Mojang/Microsoft.

## License

Code (everything under `src/`, `tools/`, `docs/`): **MIT**, see [LICENSE](LICENSE). The bundled connectome derivative and
`docs/connectome-stats.json`: **CC BY 4.0**, attribution and modifications as stated above and in
[PROVENANCE.md](PROVENANCE.md). Minecraft is a trademark of Mojang/Microsoft; this is an unofficial mod.

## Repository layout

```
build.gradle, gradle.properties        Fabric Loom 1.18, Minecraft 26.2 (unobfuscated), Java 25
tools/fetch_neuprint.py, build_flyb.py Data pipeline (Python 3.9+, requests/numpy/pandas)
src/main/java/com/fruitfly/brain       Engine-independent brain: Connectome, LifNetwork, BrainRunner, encoders, decoder
src/main/java/com/fruitfly/brain/tools BrainBench, VisionBench, EmbodiedBench (headless)
src/main/java/com/fruitfly/entity      FlyEntity, WorldSenses, FlyBody, OdorTable, TasteTable
src/main/java/com/fruitfly             FruitFlyMod, FruitFlyConfig, FlyBrainService, net/, server/
src/client/java/com/fruitfly/client    Renderer, model, neuroscope HUD (client only)
src/main/resources/connectome          Bundled FLYB (23 MB)
src/test/java                          JUnit 5 tests
docs/ARCHITECTURE.md                   Design
docs/VALIDATION.md                     Bench protocol and results
docs/connectome-stats.json             Counts of the bundled derivative
docs/research/                         Verified research notes (API signatures, LIF model, sensory mapping, DN behaviour, data access, precedents, fly model art)
PROVENANCE.md, CHANGELOG.md, LICENSE
```
