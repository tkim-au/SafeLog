# datasets/

Generated data lives here and is **not committed** (see `.gitignore`).

| folder | made by | contents |
|---|---|---|
| `sim_v3/` | `python -m simulation.generate_dataset` | SafeLog-Sim trips (`shard_*.pkl`), `samples.npz` for the gate |
| `sumo_v1/` | `python -m simulation.adapters.sumo_collect` | SUMO traffic trips, same schema |
| `carla_v1/` | `python -m simulation.adapters.carla_collect` | CARLA physics trips, same schema |
| `real/` | you | public real-car data for validation: comma2k19 (CAN speed + steering, highway), HCRL/OCSLab driving datasets (Hyundai/KIA OBD-II 1 Hz, Seoul), UAH-DriveSet (normal/aggressive/drowsy labels) |
