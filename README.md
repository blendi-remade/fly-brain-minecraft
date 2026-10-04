# Fly Brain Minecraft

[![build](https://github.com/blendi-remade/fly-brain-minecraft/actions/workflows/build.yml/badge.svg)](https://github.com/blendi-remade/fly-brain-minecraft/actions/workflows/build.yml)
[![code: MIT](https://img.shields.io/badge/code-MIT-blue.svg)](LICENSE)
[![data: CC BY 4.0](https://img.shields.io/badge/data-CC%20BY%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by/4.0/)

A Fabric mod for Minecraft 26.2 that runs the complete male fruit fly nervous system inside a fly mob.

The connectome is the male *Drosophila melanogaster* central nervous system released by Janelia, Google Research and
the Cambridge connectomics group (neuPrint `male-cns:v1.0`, Berg et al., *Cell*, September 2026): 176,422 neurons and
6.29 million connections of five or more synapses, carrying 90 of the dataset's 125 million synapses. Every neuron is
simulated as a leaky integrate-and-fire unit following Shiu et al. (*Nature* 2024). The Minecraft world drives the fly's
real sensory neurons (photoreceptors, olfactory and gustatory receptor neurons, Johnston's organ, bristles), and the
activity of its real descending and motor neurons is decoded into what the mob does. Each fly runs its own brain on its
own thread, in real time.

![Brain view (B) on the left, neuroscope (H) on the right, a fly in flight with its identity tag](docs/media/hud-brainview-neuroscope.png)

## What you see

Two HUD panels show the brain at work:

- **B, the brain view.** A live map of the whole nervous system (optic lobes top left and right, central brain between
  them, nerve cord below). Neurons light up at their real soma positions as they fire and fade over about 300 ms. Below
  the map: spikes per region for the current tick and a spikes-per-tick history.
- **H, the neuroscope.** The readout: decoded motor channels (forward, yaw, back, stop, land, flight, feed, groom, song),
  firing rates of the key populations (MN9, the giant fibre DNp01, DNa02 left and right, MDN, aDN1/2, LC4, LPLC2, Kenyon
  cells, projection neurons and more), a spike raster, and the retina as the fly sees it.

Every fly gets a persistent number, an identity colour and a matching name tag (`Fly-1 ♂`). The same colour heads both
panels and draws a rotating ring above the fly whose brain you are watching, so with several flies it is always clear
which brain is which.

You can also put the brain into the world: `/flybrain build` places the 141,781 neurons with a reconstructed soma as a
walk-through structure of stained glass coloured by region, and the linked fly's spiking neurons flash as sea lanterns.

![The connectome built out of blocks: brain on the left, nerve cord on the right](docs/media/flybrain-build.png)

## What the fly does

| Behaviour | Trigger in game | Pathway in the connectome | Origin |
|---|---|---|---|
| Feeds (proboscis extension) | Touching cake, honey, berries, fruit, sugar; a player offering food by right-click | sugar GRNs (`LB3b`, `LB3c`, `PhG1a-c`, `LgLG3`) to G2N-1 and Fudog to the MN9 proboscis motor neuron | emergent |
| Rejects bitter food | Spider eye, poisonous potato, pufferfish, rotten flesh | bitter GRNs (`LB1a-d`) to Scapula, MN9 silenced even with sugar present | emergent |
| Escape jump and takeoff | Something approaching fast (a player sprinting at it, a falling block, another mob) | LC4 and LPLC2 looming detectors to the giant fibre DNp01 to the TTMn jump muscle motor neuron | decision emergent, looming drive hand-built |
| Grooms | Rain, dust, collisions | Johnston's organ and head bristles to aDN1/aDN2 and the head grooming descending neurons | emergent |
| Walks, turns, halts | Whatever the brain does with its inputs | DNp09 and the BDN walking neurons, DNa02 right minus left, MDN (backward), bluebell and brake (halt) | readout emergent, gains hand-built |
| Walks toward food | Nearby odor sources | the antennal lobe saturates in this model and gives no reliable steering signal, so a reflex layer takes over while the brain is quiet; the HUD shows `[REFLEX]` when it does | hand-built |
| Flies and lands | Escape jump or takeoff neurons | DNg02 wing power and takeoff neurons enter flight, DNp07/DNp10 land | state machine hand-built |
| Courtship song hooks (males) | Another fly seen, smelled or tapped | pC1/P1 to the pIP10 song neuron, one wing extended | wired, not yet demonstrated |

The point of the project is to be honest about that last column. What comes out of the wiring diagram and what is
scaffolding is spelled out in [docs/REFERENCE.md](docs/REFERENCE.md) and [docs/VALIDATION.md](docs/VALIDATION.md).

## Quick start

Requirements: Minecraft 26.2, Fabric Loader 0.19.5 or newer, Fabric API for 26.2, Java 25 or newer. A machine with
8 or more cores keeps one fly in real time; more flies share the cores (`maxBrains`, default 4).

Install: put `fruitfly-connectome-<version>.jar` and the Fabric API jar into `.minecraft/mods/`. The 23 MB connectome is
inside the jar, nothing is downloaded at runtime. To build from source run `./gradlew build` (Gradle runs on JDK 25 and downloads it if no
JDK 25 is installed, see `gradle/gradle-daemon-jvm.properties`); the jar lands in `build/libs/`.

Then, in a world:

1. `/fruitfly spawn` (or use the Fruit Fly Spawn Egg from the creative menu). `/fruitfly spawn big` gives a 2.5x fly.
2. Press **B** for the brain view and **H** for the neuroscope.
3. Drop an apple next to it, right-click it while holding sugar, sprint at it, or make it rain.
4. `/fruitfly loom`, `/fruitfly feed`, `/fruitfly groom` and `/fruitfly bitter` inject the corresponding sensory input
   directly, which is handy for demos.

### Commands

| Command | Effect |
|---|---|
| `/fruitfly spawn [male\|female] [count]`, `/fruitfly spawn big` | Spawn flies (females use the same male brain for now) |
| `/fruitfly stats` | Brains in use, spikes per tick, active neurons, real-time factor and decoded command per fly |
| `/fruitfly stim <population> <hz> [seconds]` | Drive any population as Poisson spike generators, for example `/fruitfly stim MDN 60 3` walks the fly backward |
| `/fruitfly watch <population>` | Add a population to the neuroscope |
| `/fruitfly feed`, `bitter`, `loom`, `groom`, `odor` | Canned stimuli for the validated pathways |
| `/fruitfly senses` | What each fly currently smells, tastes, sees and feels |
| `/fruitfly pause`, `resume`, `kill` | Freeze or unfreeze the brains, remove the flies |
| `/flybrain build [size]`, `link`, `status`, `clear` | The connectome as a block structure, linked to a fly |
| `/brainview`, `/brainview lock [number]`, `look`, `nearest`, `view dorsal\|frontal\|side`, `size <0.15..0.75>`, `list` | Control the pinned brain map and which fly it follows |

Populations are named with neuPrint types and annotations: `DNp09`, `DNa02/L`, `prefix:ORN_DM1`, `class:gustatory`,
`superclass:vnc_motor`, `subclass:wm`, `body:10783`, and intersections such as `class:mechanosensory_tactile&nerve:ADMN`.
Every neuron keeps its neuPrint body id, so anything on the HUD can be looked up at
https://neuprint.janelia.org/?dataset=male-cns%3Av1.0.

Configuration lives in `config/fruitfly.json` (integration step, gain, thread count, fly scale, speeds, telemetry rate,
HUD populations). All keys are documented in [docs/REFERENCE.md](docs/REFERENCE.md).

## How it works

```
Minecraft world  -> sensors -> SensoryFrame -> encoders -> Poisson drive on real sensory neuron types
                                                              |
                                             LifNetwork, one thread per fly, 50 ms of brain per game tick
                                                              |
FlyEntity movement <- MotorDecoder <- descending and motor neuron population rates
```

Every neuron is the same current-based leaky integrate-and-fire unit with an exponential synapse, as in Shiu et al. 2024:
membrane time constant 20 ms, synaptic time constant 5 ms, rest and reset at -52 mV, threshold -45 mV, refractory
2.2 ms, synaptic delay 1.8 ms, and each synapse adds 0.275 mV times a global gain. Acetylcholine, monoamines and unclear
transmitters are excitatory, GABA, glutamate and histamine are inhibitory. There is no spontaneous activity: every spike
traces back to a sensory neuron. Only neurons that are away from rest or have pending input are integrated, so cost scales
with activity, not network size.

The one calibration choice is the global gain of 0.65. The published weight was fitted to the FlyWire female brain, which
has fewer synapses per neuron than the male dataset; at the literal weight the male network over-excites. The gain was
chosen with the paper's own recipe: the smallest value at which sugar input drives the proboscis motor neuron while the
grooming and escape pathways stay stable and Kenyon cells stay silent. Kenyon cells additionally receive an input gain of
0.25, a stand-in for their known high spike threshold. Both choices, the alternatives that were rejected, and an
independent Brian2 replication that argues for a lower gain are documented in `docs/`.

Sensory encoding, the odor and taste tables, the motor map and the decoder's priority ladder (escape, landing, brake,
halt, backward, forward, groom, song) are described in [docs/REFERENCE.md](docs/REFERENCE.md) and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Validation

Headless benches (`./gradlew brainBench`, `visionBench`, `embodiedBench`), gain 0.65, dt 0.5 ms. Rates are population
means over one 50 ms tick.

| Experiment | Result |
|---|---|
| Silent brain | 0 spikes |
| Sugar GRNs at 120 Hz | G2N-1 20-60 Hz, Fudog 10-40 Hz, MN9 30-90 Hz, Kenyon cells 0 |
| Bitter GRNs plus the sugar stimulus | MN9 suppressed to 0-10 Hz |
| Looming (LC4 and LPLC2 at 150 Hz) | giant fibre 330-380 Hz, DNp04 230-250 Hz, TTMn 60-80 Hz, decoder enters ESCAPE |
| Johnston's organ wind and grooming neurons at 150 Hz | aDN1 180-210 Hz, aDN2 130-150 Hz |
| Scripted apple scenario | odor, approach, tarsal then labellar sugar contact, FEED with MN9 at 60 Hz |
| Speed (32 cores, 8 worker threads) | 25-60 ms of compute per 50 ms tick; the runner falls behind rather than skipping neural time |

56 JUnit tests cover the integrator, the retina geometry, the encoders and the decoder. Full protocol and numbers:
[docs/VALIDATION.md](docs/VALIDATION.md).

## Rebuilding the data

```bash
pip install requests numpy pandas
python tools/fetch_neuprint.py     # anonymous neuPrint access, about two minutes
python tools/build_flyb.py         # writes src/main/resources/connectome/malecns-v1.0.flyb.gz
```

`--min-weight 1` fetches all 25.9 million connections; `build_flyb.py --exclude-superclass ol_intrinsic,visual_projection`
builds a brain-only file without the optic lobes. Point `connectomeFile` in the config at the result. Exact queries,
transformations and file hashes are in [PROVENANCE.md](PROVENANCE.md).

## Limitations

- Every neuron has the same parameters. The antennal lobe saturates under any odor, and the medulla motion pathway stays
  silent, so looming and object signals are painted onto LC4/LPLC2/LC11/LC10a analytically instead of emerging from the
  lamina. Walking toward food is a reflex layer, not the connectome.
- No neuromodulation, neuropeptides, gap junctions or spontaneous activity.
- Connections with fewer than five synapses are omitted.
- Only the male nervous system exists; female flies use the same brain.
- Absolute firing rates should not be trusted, only the pattern of which populations respond.

## Data sources and citations

The mod embeds a thresholded derivative of the male Drosophila CNS connectome, neuPrint dataset `male-cns:v1.0`, produced
by the FlyEM Project Team at HHMI Janelia Research Campus, the Drosophila Connectomics Group (University of Cambridge and
MRC LMB) and Google Research, licensed CC BY 4.0. Source: https://male-cns.janelia.org/ and
https://www.janelia.org/project-team/flyem/male-cns-connectome. Modifications: synapse-count threshold, integer
re-indexing, quantised weights (see PROVENANCE.md).

If you use this in research or teaching, please cite:

- Berg S, Beckett IR, Costa M, Schlegel P, Januszewski M, et al. Sexual dimorphism in the complete Drosophila male
  central nervous system connectome. *Cell* 189(18):5504-5526 (2026). https://doi.org/10.1016/j.cell.2026.08.015
- Shiu PK, Sterne GR, Spiller N, et al. A Drosophila computational brain model reveals sensorimotor processing.
  *Nature* 634:210-219 (2024). https://doi.org/10.1038/s41586-024-07763-9
- Plaza SM, Clements J, Dolafi T, et al. neuPrint: An open access tool for EM connectomics.
  *Front. Neuroinform.* 16:896292 (2022). https://doi.org/10.3389/fninf.2022.896292

The companion papers on the visual system, the nerve cord, the taste system and the sexually dimorphic circuits, and the
descending-neuron literature behind the motor decoding, are listed in [docs/REFERENCE.md](docs/REFERENCE.md).

This project is not affiliated with or endorsed by HHMI, Janelia, Google, the University of Cambridge, the MRC LMB or
Mojang/Microsoft. Minecraft is a trademark of Mojang/Microsoft.

## License

Code: MIT, see [LICENSE](LICENSE). The bundled connectome derivative: CC BY 4.0 with the attribution above.

## Repository layout

```
tools/                                  data pipeline (fetch_neuprint.py, build_flyb.py, texture generators)
src/main/java/com/fruitfly/brain        engine-independent brain: connectome loader, LIF network, encoders, decoder, benches
src/main/java/com/fruitfly/entity       the fly: world senses, body physics, odor and taste tables
src/main/java/com/fruitfly              mod entrypoint, config, brain service, commands, networking
src/client/java/com/fruitfly/client     renderer, model, brain view, neuroscope, in-world overlay
src/main/resources/connectome           the bundled connectome (23 MB)
src/test/java                           JUnit tests
docs/                                   ARCHITECTURE.md, REFERENCE.md, VALIDATION.md, FOLLOWUPS.md, research notes
```
