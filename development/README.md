# SafeLog development

Driving-habit detection, scoring and reward engine for **세이프로그 (Safe+Log)** on Pleos Connect,
plus the simulators and training loop used to prove it. Built 2026-11-06.

> Rule of the product: the driver only ever hears about **good** driving. Every bad event is a
> `MISS` or a risk count that stays inside the engine (reward gate, next practice, opt-in insurer score).

## Folder map

| folder | what it holds |
|---|---|
| `data_spec/` | `signals.py` — the one signal schema (31 channels) every source must emit, each mapped to its Pleos / AAOS API |
| `scoring_engine/` | the product engine: `derive.py` (Δv/1 s acceleration, bicycle-model lateral g), `habits.py` (23 detectors + judges), `risk_monitors.py` (internal only), `scoring.py` (Beta posterior, Skill Index, XP), `engine.py` (one call per trip), `streaming.py` (in-drive recorder + O(1) chime detector), `config/thresholds.yaml` (every number with its source) |
| `simulation/` | `simulator.py` SafeLog-Sim signal-level digital twin, `driver_styles.py` (careful / normal / aggressive / novice / drowsy), `generate_dataset.py`; `adapters/` for **SUMO** (tested), **CARLA** (written, run it first with `--trips 1`), Pleos emulator replay export |
| `model_training/` | dual-key gate: `features.py`, `tcn.py` (25k-parameter TCN), `build_samples.py`, `train.py` (CUDA, calibration, veto thresholds), `gate.py`, `checkpoints/gate.pt` |
| `evaluation/` | `truth.py` (ground truth + matching), `evaluate.py`, `reports/` (latest results) |
| `datasets/` | generated data (not committed) and where to put real datasets |
| `docs/` | `catalogue.py` (84 scenarios, single source of truth), `scenario_catalogue.csv`, `logic_board.html` (logic diagram per scenario + merged system + calculation board) |
| `tools/` | `setup_windows.ps1`, `check_gpu.py`, `benchmark_runtime.py` |

## Run it (Windows, RTX 3060 Ti)

```powershell
cd D:\TF_Hyundai\development
powershell -ExecutionPolicy Bypass -File tools\setup_windows.ps1     # venv, CUDA PyTorch, SUMO, GPU check, smoke test
.\.venv\Scripts\python run_pipeline.py --trips 3000 --workers 8      # full loop, about 15-25 min
```

The pipeline simulates trips, labels them, trains the gate on the GPU, evaluates rules-only vs dual-key on
held-out trips, measures runtime cost and rebuilds `docs/logic_board.html`.

## Data contract (read first)
`data_spec/signals.py` is the contract with the car; `data_spec/DATA_AUDIT.md` checks every channel against the official
Pleos / Android Automotive specs (type, unit, update mode, availability) and lists the open questions for Pleos support.
Every dataset and model carries `DATA_VERSION` (now **3**, checked against the official API reference page by page). A model trained on another version is refused.

## Non-stop training and stress test
- `python -m evaluation.stress_test --gate model_training/checkpoints/gate.pt` — 17 hard conditions + garbage-input fuzz, pass bar 97 %.
- `tools/run_forever.ps1` → `tools/train_forever.py` — endless rounds: new randomized trips (CPU, in the background) → ensemble of TCNs (GPU) → champion vs challenger on a fixed holdout → promote only if better. See INSTALL.md Part 6.
- **Watch it live in 2D:** double-click `LIVE_VIEW.cmd` (or `.\.venv\Scripts\python -m tools.live_view`) → http://localhost:8765. Trips of the *current* round are re-simulated with the trainer's own seeds and played back from above (road, lanes, blinkers, brake lights, rain, night, school zones, cut-ins); every rule judgment pops up with what the champion gate did (kept / blocked, and whether the ground truth agrees), next to the round, champion vs challenger and the precision history. Read-only and CPU-only, so it does not slow the GPU training.

## What to install for simulation

| level | install | GPU | use it for |
|---|---|---|---|
| 1 · SafeLog-Sim (included) | `pip install numpy scipy pyyaml torch` | no (training uses it) | thousands of labelled trips with exactly the Pleos signals, sensor faults, 1 Hz speed, cut-ins |
| 2 · SUMO 1.2x | `pip install eclipse-sumo libsumo` | no | real traffic interaction on Korean road networks from OpenStreetMap (`netconvert --osm-files ...`) |
| 3 · CARLA 0.9.15 / 0.9.16 | release zip (~20 GB) + `pip install carla==<same version>` | **yes — 3060 Ti 8 GB meets the recommended spec** | vehicle physics, rain/night, dense traffic; run with `-quality-level=Low -RenderOffScreen`, `no_rendering_mode` |
| 4 · Pleos Connect Emulator | Pleos Playground | — | end-to-end test of the Kotlin app with `simulation/adapters/pleos_replay_export.py` |

Optional real-data validation: comma2k19 (CAN speed + steering), HCRL / OCSLab driving datasets (Hyundai/KIA OBD-II, Seoul, 1 Hz), UAH-DriveSet (labelled normal/aggressive/drowsy).

## Latest results (600 simulated trips = 298 h; 90 held-out trips)

| | praise precision | deserved praise given | false praises |
|---|---:|---:|---:|
| rules only | 98.99 % | 86.3 % | 44 / 4,338 |
| rules + dual-key gate | **99.30 %** | 82.9 % | 29 / 4,155 |

Not released yet (logged, never shown): `anticipatory_braking` (precision 0.76). `smooth_stop` is at 0.96 and is the next one to improve.
Runtime (Python reference): in-drive ≈ 0.01 % of one CPU core and 0.8 MB/hour, parked judging ≈ 0.1 s per driving hour.
Pleos budget (official "성능 및 리소스 효율을 고려한 설계" page): **non-navigation app while driving < 5 % CPU, any app parked ≤ 25 %, RAM ≤ 400 MB**. (An earlier version of this README said 3 %; the current official page says 5 %.)
