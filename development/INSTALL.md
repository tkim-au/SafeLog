# Installing the three simulation environments (Windows 10/11, RTX 3060 Ti)

Order matters: part 1 is required, parts 2 and 3 build on it. All commands run in **PowerShell**.

## Part 0 · Before you start (10 min)

1. **NVIDIA driver.** Update with GeForce Experience or nvidia.com/drivers. Check in PowerShell:
   `nvidia-smi` → the top-right "CUDA Version" must be **12.6 or higher**.
2. **Python 3.12 (64-bit)** from python.org/downloads/windows. In the installer tick
   **"Add python.exe to PATH"** and keep **"py launcher"** ticked. Check: `py -3.12 --version`.
3. **Free disk space on D:** about 5 GB for parts 1–2, plus about 40 GB more for CARLA (part 3).
4. Allow scripts for this session if PowerShell blocks them:
   `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`

## Part 1 · SafeLog-Sim + PyTorch (GPU training) — 15 min

1. `cd D:\SafeLog\development`
2. `powershell -ExecutionPolicy Bypass -File tools\setup_windows.ps1`
   It creates `.venv`, installs PyTorch CUDA 12.6, numpy/scipy/pyyaml and SUMO,
   checks the GPU and runs a smoke test.
3. Confirm the GPU line says `GPU: NVIDIA GeForce RTX 3060 Ti, 8.0 GB VRAM`.
   If it says `CUDA available: False`: update the driver (part 0.1), then
   `.\.venv\Scripts\pip install --force-reinstall torch --index-url https://download.pytorch.org/whl/cu126`
4. Full run (15–25 min): `.\.venv\Scripts\python run_pipeline.py --trips 3000 --workers 8`
   Results: `evaluation\reports\*.md`, diagrams: `docs\logic_board.html`.

## Part 2 · SUMO traffic simulator — 10 min

The Python package from part 1 already contains the SUMO programs used by our adapter.
Install the Windows version as well to get the GUI (`sumo-gui`, `netedit`) and the OpenStreetMap wizard.

1. Download the 64-bit installer `sumo-win64-1.27.1.msi` (or newer) from sumo.dlr.de/docs/Downloads.php
   (or run `winget install --name sumo`). Install with defaults. The installer sets `SUMO_HOME`.
2. Open a **new** PowerShell and check: `echo $env:SUMO_HOME` and `sumo --version`.
3. Test our adapter (CPU only, about 2 min):
   `.\.venv\Scripts\python -m simulation.adapters.sumo_collect --trips 3 --minutes 8 --out datasets\sumo_v3`
4. Korean road network (optional): run `.\.venv\Scripts\python "$env:SUMO_HOME\tools\osmWebWizard.py"`;
   a browser map opens. Zoom to an area (e.g. Gangnam), click Generate Scenario. Then pass the generated `osm.net.xml.gz`:
   `... sumo_collect --net <path>\osm.net.xml.gz --trips 30`

## Part 3 · CARLA 0.9.16 (GPU physics) — 1–2 h, mostly download

1. Download **CARLA_0.9.16.zip** (Windows) from github.com/carla-simulator/carla/releases
   (about 15–20 GB). Optional: AdditionalMaps_0.9.16.zip.
2. Extract to a short path without spaces: `D:\CARLA_0.9.16` (use 7-Zip if Windows Explorer is slow).
   AdditionalMaps: extract into the same folder and allow overwriting.
3. First start, to check the GPU and drivers:
   `D:\CARLA_0.9.16\CarlaUE4.exe -quality-level=Low`
   A window with a city appears. If Windows Firewall asks, allow private networks (ports 2000–2002).
   If it complains about missing DLLs, install the "Visual C++ Redistributable (x64)" and
   "DirectX End-User Runtime" from Microsoft, then retry.
4. For data collection, start it without a visible window (saves the GPU):
   `D:\CARLA_0.9.16\CarlaUE4.exe -quality-level=Low -RenderOffScreen`
5. In a second PowerShell, inside `D:\SafeLog\development`:
   `.\.venv\Scripts\pip install carla==0.9.16`  (must match the server version)
   `.\.venv\Scripts\pip install -r D:\CARLA_0.9.16\PythonAPI\carla\requirements.txt`
6. One test trip:
   `.\.venv\Scripts\python -m simulation.adapters.carla_collect --trips 1 --minutes 3 --agents-path D:\CARLA_0.9.16\PythonAPI\carla`
   Expected: `trip 0: cautious <weather> 3.0 min, ...` and `datasets\carla_v3\shard_0000.pkl`.
7. Then 30 trips (about 1 h): same command with `--trips 30 --minutes 10`.
   Evaluate them with: `.\.venv\Scripts\python -m evaluation.evaluate --data datasets\carla_v3 --split train`

The CARLA adapter has not been run yet on a real CARLA server. If step 6 fails, keep the full error
text: it is usually a version mismatch between `pip install carla` and the server, or the agents path.

## Quick reference

| Tool | Version | GPU | Check command |
|---|---|---|---|
| Python | 3.12 | — | `py -3.12 --version` |
| PyTorch | latest, CUDA 12.6 build | yes | `.\.venv\Scripts\python -m tools.check_gpu` |
| SUMO | 1.27.x | no | `sumo --version` |
| CARLA | 0.9.16 (server zip + `carla==0.9.16` wheel) | yes, 8 GB | `CarlaUE4.exe -quality-level=Low` |

## Part 4 · Full runs (copy-paste, in `D:\SafeLog\development`)

```powershell
# one-time extras for watching and plotting
.\.venv\Scripts\python -m pip install traci matplotlib

# A. SafeLog-Sim: 3,000 trips -> label -> GPU training -> evaluation -> benchmark -> diagrams (25-40 min)
.\.venv\Scripts\python run_pipeline.py --trips 3000 --workers 8
#    (data already generated? skip the simulation step:)
.\.venv\Scripts\python run_pipeline.py --skip-sim --workers 8

# B. SUMO: 60 trips headless (CPU, ~10 min), then evaluate them with the trained gate
.\.venv\Scripts\python -m simulation.adapters.sumo_collect --trips 60 --minutes 10 --out datasets\sumo_v3
.\.venv\Scripts\python -m evaluation.evaluate --data datasets\sumo_v3 --split all --out evaluation\reports\sumo
.\.venv\Scripts\python -m evaluation.evaluate --data datasets\sumo_v3 --split all --gate model_training\checkpoints\gate.pt --out evaluation\reports\sumo

# C. CARLA: start the server first in another window:  D:\CARLA_0.9.16\CarlaUE4.exe -quality-level=Low -RenderOffScreen
.\.venv\Scripts\python -m simulation.adapters.carla_collect --trips 30 --minutes 10 --agents-path D:\CARLA_0.9.16\PythonAPI\carla
.\.venv\Scripts\python -m evaluation.evaluate --data datasets\carla_v3 --split all --gate model_training\checkpoints\gate.pt --out evaluation\reports\carla
```

## Part 5 · Watching the simulations (graphics)

| What | Command | You see |
|---|---|---|
| SUMO 2D, live | `.\.venv\Scripts\python -m simulation.adapters.sumo_collect --trips 1 --minutes 5 --gui --delay 50 --out datasets\sumo_demo` | top-down city traffic; the ego car is red and the camera follows it. `--delay 0` = fastest |
| CARLA 3D, live | start `D:\CARLA_0.9.16\CarlaUE4.exe -quality-level=Epic` (no `-RenderOffScreen`), then `... carla_collect --trips 1 --minutes 5 --render --agents-path D:\CARLA_0.9.16\PythonAPI\carla --out datasets\carla_demo` | chase camera behind the ego car with weather and traffic |
| Any trip, after the fact | `.\.venv\Scripts\python -m tools.trip_viewer --data datasets\sim_v3 --trip 0 --minutes 15 --show` | route from above + speed, brake, steering, blinker, with every praise (green ▲), abstain (grey) and internal miss (red ✕) |

The trip viewer works on all three datasets (`sim_v3`, `sumo_v3`, `carla_v3`); without `--show` it saves a PNG in `evaluation\reports`.

## Part 6 · Non-stop GPU training + stress test

> **2026-11-06: data contract v3. Retrain from scratch.** The official Pleos API reference, read page by page
> (`data_spec\DATA_AUDIT.md`), showed the old simulator differed from the real car: brake is pressed / not pressed
> only; steering is the **front-wheel** angle with left **negative**; warning-light telltales are **not readable** by
> third-party apps; turn signal has no hazard state; navigation distances arrive about once a second. Older datasets
> and older `gate.pt` files are **not usable**. The code refuses them automatically (rules-only until a v3 model
> exists). Nothing needs deleting by hand: `train_forever` moves its own old rounds aside.

**Stress test first** (about 10–15 min with 6 workers). It runs 17 hard conditions with fixed seeds (1 Hz speed, heavy dropouts, no heading/navigation, noisy steering, a cluster that reads 6 % high, rain/night, cut-in storms, expressway, rural curves, every driver type, a worst case), plus 200 garbage-input fuzz cases:

```powershell
cd D:\SafeLog\development
.\.venv\Scripts\Activate.ps1
python -X utf8 -m evaluation.stress_test --gate model_training\checkpoints\gate.pt --trips 60 --workers 6
```
Report: `evaluation\reports\stress.md`. Pass = praise precision ≥ 97 % in each condition and zero crashes.

**Then start non-stop training** (runs until you stop it; restarts itself if Python crashes):

```powershell
powershell -ExecutionPolicy Bypass -File tools\run_forever.ps1
```
- Stop: `Ctrl+C`, or from another window `New-Item datasets\forever\STOP`. Run the same command again to continue.
- Progress: `evaluation\reports\forever_progress.md` (updated every round) and `forever_history.csv`.
- The model in use (`model_training\checkpoints\gate.pt`) is replaced **only** when a new one is at least as precise **and** keeps more true praise on a fixed holdout it never trained on. Every promoted version is kept in `model_training\checkpoints\history\`.
- Stress test of the champion every 5 rounds → `evaluation\reports\stress_latest.md`.
- GPU above 83 °C → it pauses 60 s at a time. Windows sleep is blocked while it runs (the screen can still turn off).
- Disk: ~25 MB per round; only the newest 200 rounds are kept (~5 GB). RAM: ~4 GB with the default pool size (`--max-samples 250000`); lower that if your PC has 16 GB or less.
- Lighter run while you use the PC: `powershell -ExecutionPolicy Bypass -File tools\run_forever.ps1 -Workers 3`
