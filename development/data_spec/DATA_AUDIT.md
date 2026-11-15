# Data audit: does our data match what a Pleos Connect car actually delivers?

Audit 2026-11-06 · **data contract v3** (`data_spec/signals.py: DATA_VERSION = 3`)

**Why this matters:** a model trained on data that differs from what the car sends is not usable.

**How it was checked (v3):** every page of the official **Vehicle SDK v2.3.2 API reference** (606 pages indexed,
212 relevant pages read: Brake, SteeringWheel, TurnSignal, Wiper, DrivingMode, Safety, Odometer, Seat, Light,
CarInfo, Tire, all enums, models and listeners), the **NaviHelper v3.0.5** and **FusedLocation v1.1.3** API
references, the Connect developer guide (278 pages indexed; FAQ, SDK/API policy, compatibility, performance,
privacy, driving restrictions, Virtual Console, SDK release notes 2026-10-19), and every page of the competition
site (appdevchallenge.auto-hmg.io: overview, rules, judging, submission, development, FAQ, notices).
Pages were rendered in a real browser, because the docs site is JavaScript-only.

Evidence levels:
- **OFFICIAL**: Pleos API reference or developer guide (document.pleos.ai), or Android docs
- **EMULATOR**: seen on the official emulator by other contest teams (public READMEs), not official
- **ASSUMED**: our assumption, still to confirm with Pleos support

## 1. Channel by channel

| Channel | Official source (API) | What it really is | v1 simulator | v3 (now) |
|---|---|---|---|---|
| speed | AAOS `PERF_VEHICLE_SPEED` via `CarPropertyManager`. **OFFICIAL** Pleos FAQ: "the cluster shows PERF_VEHICLE_SPEED". Vehicle SDK `VehicleSpeedListener` was **removed** (release note 2026-10-19) | Float **m/s**, signed, CONTINUOUS, dangerous permission `CAR_SPEED` (Android docs) | km/h, 1–10 Hz | collector: \|v\| × 3.6; sim keeps 1–10 Hz, 0.1/1 km/h steps, bias 0.99–1.03 |
| **brake** | `Brake.getCurrentBrakePedalStatus(onComplete: (Boolean?) -> Unit)`. **OFFICIAL**: "If true, the brake pedal is being pressed." Permission `CAR_DRIVE` (normal). Virtual Console sets 0–100 %, but the app gets "the pedal-operation state derived from that value" | **Boolean** pressed / not pressed. No position anywhere in the API | 0–100 % | `brake_on` 0/1/−1 everywhere (sim, engine, model) |
| steering bounds | `SteeringWheel.checkSteeringWheelAngleCapabilities(onComplete: (CapabilityConfig, AreaBounds<Float>) -> Unit)` (OFFICIAL) | supported angle range in degrees | – | new channel `steer_bound_deg`; decides the unit (> 90 = steering wheel) |
| **steering** | `SteeringWheel.getCurrentSteeringWheelAngle(onComplete: (Float?) -> Unit)` + `registerSteeringWheelAngle`. **OFFICIAL**: "Gets the current **front-wheel** steering angle in degrees. **Leftward steering angles are negative.** This value does not represent the rotation angle of the steering wheel." Capability returns `AreaBounds<Float>` | **Road-wheel angle**, left negative. ⚠ The official Virtual Console guide describes its slider as "steering-wheel angle −540…540°, 1° steps", so the emulator and the API docs disagree | steering-wheel angle, left positive, 0.1° | engine default = front-wheel angle, sign −1 (official). Auto-detects a steering-wheel unit (range > 45°, else heading fit, else memory). Sign still verified against heading / navigation turns. Resolution check: a road-wheel angle in ≥ 0.5° steps → lateral judgments ABSTAIN. Sim: 85 % front-wheel / 15 % wheel unit, 90 % left-negative, steps 0.01–1° |
| **telltales** (FCA, LKA, DAW, icy, ABS/ESC, LCA) | `Safety.getTelltalesInfo` / `registerTelltales`. **OFFICIAL**: permission **`PRIVATE_CAR_SAFETY` (protection level signature\|privileged)** | **Not readable by any third-party app** | visible collision warnings | all −1, always. following_margin, lane_keeping and fca_reaction are not feasible (held). Forced-stop detection cannot use FCA |
| low tyre pressure | `Tire.getLowTirePressure` (`CAR_SAFETY`, normal) | per wheel | – | `tt_tpms` source corrected |
| turn signal | `TurnSignal.getCurrentTurnSignalState(onComplete: (TurnSignalValue?) -> Unit)`. **OFFICIAL** `TurnSignalValue` = OFF, RIGHT, LEFT | **No hazard value** | had hazard = 3 | sim maps hazard → OFF; hazard_warning not feasible (held) |
| gear | **Not in Vehicle SDK v2.3.2** (no Gear domain). AAOS `GEAR_SELECTION` (the "standard data → AAOS" rule in the official SDK/API policy) | VehicleGear enum | 0..3 | collector maps P=4/R=2/N=1/D=8 |
| cruise / SCC | `DrivingMode.getCruiseControlState(onComplete: (CruiseControl?) -> Unit)`. **OFFICIAL** enum: OTHER, ENABLED, ACTIVATED, USER_OVERRIDE, SUSPENDED, FORCED_DEACTIVATION_WARNING (changed in v2.3.2). Virtual Console instead lists "Not ready, Ready, Longitudinal, Lateral, Full autonomous" | Mapping (ours): ACTIVATED / USER_OVERRIDE = 1, ENABLED / SUSPENDED = 0, OTHER / FORCED / unsupported = −1 | always known | 15 % of sim trips unknown. Engine: steady_cruise and per-km blocks ABSTAIN; smooth_stop needs the driver's own pedal; launches ABSTAIN. **No public lane-centering (HDA/LFA) state** |
| drive mode | `DrivingMode.getCurrentDrivingModeState` → `DrivingModeType` NORMAL/ECO/SPORT | – | – | ok |
| wiper | **OFFICIAL** `Wiper.getCurrentWiperState(WindowArea)` → `WiperState` OTHER/OFF/ON/SERVICE (mirrors AAOS WINDSHIELD_WIPERS_STATE = actual operation). Also `getCurrentWiperSwitch` → `WiperSwitch` OFF/MIST/INTERMITTENT_1-5/CONTINUOUS_1-5/AUTO/SERVICE | wipers operating, also with the switch on AUTO | "is wiping" | `WiperState` ON = 1, OFF = 0, OTHER/SERVICE/unsupported = −1 (10 % of sim trips unknown) |
| low beam | `Light.getCurrentLowBeamLightState` → `LightState` OFF/ON/DAYTIME_RUNNING | – | bool | ON = 1 |
| odometer | `Odometer.getCurrentDistanceDriven(onComplete: (Float?) -> Unit)`. **OFFICIAL**: "in kilometers". Trip distances in metres via `TripStatistics` | resolution unknown (Virtual Console: 1 km steps) | exact | sim 0.1 / 1 km. Engine uses it only if steps ≤ 10 m |
| seats / belts | `Seat.getCurrentSeatOccupancy(SeatArea)` and `getCurrentSeatBeltBuckledState(SeatArea)` → **Boolean?** per seat | – | count | collector counts |
| speed limit | NaviHelper `onCurrentRoadSpeedLimit(speedLimit: Int)`. **OFFICIAL**: "Received every second" | km/h, 1 Hz | dense | held 1 Hz |
| turn-by-turn | `onTBTInfo(List<TBTInfo>)`, `TBTInfo(type: TurnType, distance: Double, description)`. **OFFICIAL**: distance in metres; `TurnType` has 40+ values (LEFT, SHARP_L, KEEP_L, UTURN, IC enter/exit, roundabouts…) | event callback | exact 10 Hz | held, dead-reckoned with a standstill deadband. LEFT/SHARP_L → 1, RIGHT/SHARP_R → 2, UTURN → 3, KEEP/IC/STRAIGHT → 4 |
| caution points | `onCautionPointInfo(List<CautionPoint>)`, `CautionPoint(type, remainDistance: Int m)`. **OFFICIAL** `CautionPointType` (24): SPEED_BUMP, SCHOOL_ZONE, ACCIDENT_PRONE_AREA, RAILROAD_CROSSING, FOG_AREA, FALLING_ROCK_AREA, DOWNHILL_CAUTION_AREA, WILDLIFE_CROSSING, NARROW_ROAD, SLIPPERY_ROAD_ALERT, … | **no sharp-curve, no icy-road type** | sharp curve + icy | sim never delivers them; slow-down habit uses accident-prone / fog / wildlife / downhill / narrow / slippery |
| cameras | `onCameraAlert(List<CameraAlert>)`, `CameraAlert(type, speedLimit Int km/h, remainDistance Int m, isOverSpeed, sectionAvrSpeed)`; types include SPEED_CAMERA_SCHOOL_ZONE | – | – | ok |
| heading | NaviHelper `CurrentLocationInfo.heading: Double` ("direction of travel") via `onCurrentLocationInfo`; FusedLocation `LocationData.heading: Float`. FusedLocation availability "varies by model" (`isSupported()`), dangerous permission. **No speed in LocationData** | compass bearing ASSUMED | counter-clockwise | compass in sim, engine negates; often missing |
| navigation present? | **OFFICIAL FAQ**: "how to check whether a car has navigation is not publicly open" | – | – | engine infers from callbacks arriving (NaN otherwise) |
| accelerometer / gyro | **OFFICIAL**: "some sensors (gyro, proximity…) may be restricted"; no IMU API | – | not used | derived from speed / steering |

### Engine fixes found by the v3 stress test (realistic data, rules-only)
| problem | before | fix |
|---|---|---|
| a steering-WHEEL-unit trip without turns looked like a front-wheel angle (13.5x yaw), producing phantom lane changes | smooth_lane_change 8-38 % | the unit comes from the official capability bounds first (`steer_bound_deg`), then range, heading fit, memory |
| front-wheel steps of 0.1 deg = about 1 deg/s of yaw at 100 km/h, so rounding looked like S-shaped lane changes | phantoms | lane-change detector: 1 s averaging plus a threshold of at least 1.5 steering steps |
| ground truth guessed its own steering unit | truth claimed 1 g lane changes | truth declares its unit and sign explicitly |
| speed-limit praise when exactly at the limit with a 1 % under-reading speedometer | speed_limit 86 % | guard 1 km/h + 1 % below the limit (also rain and school-zone speed); sim speedometer never under-reads by more than 0.5 % |
| rain unknown with the wiper switch on AUTO | – | wiper channel = `WiperState` (actual operation, like AAOS WINDSHIELD_WIPERS_STATE), not the switch |

## 2. Platform rules that affect SafeLog (OFFICIAL)
- **CPU:** non-navigation app **while driving < 5 %**, any app **parked ≤ 25 %**, **RAM ≤ 400 MB** (performance-resource-mgmt). SafeLog in-drive ≈ 0.01 % of one core; the parked batch ≈ 0.1 s per driving hour. (Earlier documents said 3 %; the official figure is 5 %.)
- **Privacy:** anonymise / pseudonymise vehicle and user IDs leaving the car. **Do not use in-drive behaviour data for marketing** (security-privacy).
- **Driving restrictions:** apps without DO cannot be used while driving. Keyboard, settings and long text are blocked (Korea is "other region": keyboard allowed, setup / video / manual blocked). SafeLog shows praise only when parked; in-drive output is the chime only.
- **API policy:** standard data (speed, gear, outside temperature, fuel) → AAOS Car API; the Pleos SDK only covers manufacturer-specific features. Using a property not in the SDK needs a technical-support review.
- **Competition** (appdevchallenge.auto-hmg.io): entries close 2026-11-15. The PT on 12/3–4 needs a working app (emulator or device). Judging: 차량특화성 · 창의성 · 기술 활용성 · 사업성, plus bonus points for a demo video, GitHub repo and APK (`apk/팀명_앱이름_release.apk`). The organiser's own example list includes a "안전운전 점수 앱 (Vehicle SDK data → driving-habit score)", so **differentiation matters**: praise-only, abstain-when-unsure, dual-key and Korean school-zone habits.

## 3. What changed for the model
- v1 and v2 models and datasets are refused automatically (`IncompatibleModel`). `train_forever` retires old rounds.
- Model input "steer" is now the front-wheel angle in degrees (unit-independent). "brake" is 0/1. FCA / LKA inputs are always 0, because those channels are not readable.

## 4. Requirements for the Kotlin SignalCollector
1. Speed `PERF_VEHICLE_SPEED` (m/s) → `abs(v) * 3.6`; request `CAR_SPEED` at runtime.
2. ON_CHANGE channels (steering, brake, turn signal, gear, wiper, lights, cruise): hold the last value; read once with `getCurrent…()` at trip start.
3. Steering: store the raw value (front-wheel degrees, left negative), and the `checkSteeringWheelAngleCapabilities` AreaBounds max as `steer_bound_deg` (the unit hint).
4. Brake `true → 1`, `false → 0`, `null` / no callback → −1.
5. Telltales: do **not** request `PRIVATE_CAR_SAFETY`. Unapproved permissions fail app review.
6. Wiper (`getCurrentWiperState`), cruise, low beam, seats: map the official enums as in §1.
7. Navigation: store `TBTInfo.distance`, `CautionPoint.remainDistance` and `onCurrentRoadSpeedLimit` raw (held).
8. Call `checkXXXCapabilities()` / `isSupported()`. Unsupported → −1 / NaN for the whole trip.
9. Timestamps from `SystemClock.elapsedRealtime()`, sample-and-hold to 10 Hz.

## 5. Questions for Pleos technical support (partnership@pleos.ai)
1. **Steering:** the API says front-wheel angle, the Virtual Console says steering-wheel angle ±540°. Which does a production car deliver? Resolution and update rate?
2. **Collision / lane-departure warning events:** is there any public way (not `PRIVATE_CAR_SAFETY`) to know that FCA or LDW fired? Without it, forced stops (cut-ins) cannot be excluded reliably.
3. **CruiseControl mapping:** how do ACTIVATED / USER_OVERRIDE / ENABLED relate to the console's Longitudinal / Lateral / Full autonomous? Is the lane-centering state readable?
4. `PERF_VEHICLE_SPEED` max sample rate on production cars?
5. `CurrentLocationInfo.heading`: is it a compass bearing (0 = north, clockwise)? Update rate?
6. Odometer resolution; how often `onTBTInfo` / `onCautionPointInfo` are delivered.

## Sources (all read 2026-11-06)
- Vehicle SDK API reference (Brake, SteeringWheel, TurnSignal, Wiper, DrivingMode, Safety, Odometer, Seat, Light, Tire, enums): https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/entry
- e.g. getCurrentSteeringWheelAngle: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/get-current-steering-wheel-angle
- e.g. getCurrentBrakePedalStatus: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/get-current-brake-pedal-status
- e.g. getTelltalesInfo: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/get-telltales-info
- NaviHelper API reference: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/entry
- FusedLocation API reference: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/entry
- FAQ (cluster speed = PERF_VEHICLE_SPEED; navigation presence not public): https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq
- SDK/API policy: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api
- API compatibility & availability: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability
- Performance & resources: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt
- Security & privacy: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy
- Driving restriction policy: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy
- Virtual Console guide: https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide
- SDK release note (2026-10-19): https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note
- Android VehiclePropertyIds: https://developer.android.com/reference/android/car/VehiclePropertyIds
- Competition: https://appdevchallenge.auto-hmg.io/guide/overview, /guide/development, /guide/judging-awards, /guide/rules, /guide/submission, /notice
