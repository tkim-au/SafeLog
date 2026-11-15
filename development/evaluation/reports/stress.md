### Stress test (60 trips per condition, gate: model_training\checkpoints\gate.pt)

| condition | trips | hours | praises | precision | recall | false / 100 trips | crashes | worst habits | result |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| baseline | 60 | 24.4 | 2319 | 99.53% | 66.4% | 18.3 | 0 | smooth_launch 0.983, right_turn_pause 0.983, rain_speed 0.989 | PASS |
| speed_1hz | 60 | 23.0 | 1697 | 99.82% | 59.6% | 5.0 | 0 | steady_cruise 0.931, speed_limit 0.996, blinker_cancel 1.0 | PASS |
| heavy_dropouts | 60 | 37.9 | 2512 | 99.36% | 62.0% | 26.7 | 0 | lane_change_signal 0.941, smooth_launch 0.965, steady_cruise 0.974 | PASS |
| no_heading_no_nav | 60 | 26.3 | 1364 | 99.12% | 49.0% | 20.0 | 0 | rain_speed 0.923, steady_cruise 0.923, smooth_launch 0.985 | PASS |
| no_nav_data | 60 | 26.0 | 1386 | 99.35% | 41.6% | 15.0 | 0 | smooth_turn 0.913, steady_cruise 0.955, blinker_cancel 1.0 | PASS |
| noisy_steering_x4 | 60 | 25.7 | 1795 | 99.50% | 57.6% | 15.0 | 0 | smooth_turn 0.982, steady_cruise 0.984, speed_limit 0.99 | PASS |
| cluster_bias_6pct | 60 | 29.0 | 2124 | 99.15% | 57.3% | 30.0 | 0 | smooth_turn 0.941, speed_limit 0.975, steady_cruise 0.979 | PASS |
| rain_night | 60 | 30.6 | 3194 | 99.66% | 70.8% | 18.3 | 0 | speed_bump 0.917, steady_cruise 0.963, smooth_launch 0.991 | PASS |
| heavy_rain | 60 | 39.7 | 4341 | 98.66% | 75.1% | 96.7 | 0 | steady_cruise 0.958, speed_limit 0.969, smooth_launch 0.982 | PASS |
| cut_in_storm | 60 | 25.3 | 2034 | 98.53% | 53.8% | 50.0 | 0 | speed_limit 0.965, smooth_launch 0.97, smooth_lane_change 0.991 | PASS |
| expressway | 60 | 27.8 | 2246 | 99.33% | 62.2% | 25.0 | 0 | smooth_turn 0.975, steady_cruise 0.982, smooth_launch 0.984 | PASS |
| rural_curves | 60 | 20.9 | 1428 | 99.02% | 57.1% | 23.3 | 0 | smooth_turn 0.969, speed_limit 0.973, blinker_cancel 0.992 | PASS |
| aggressive_drivers | 60 | 17.9 | 549 | 99.64% | 62.2% | 3.3 | 0 | steady_cruise 0.929, blinker_cancel 1.0, headlights 1.0 | PASS |
| novice_drivers | 60 | 24.4 | 2005 | 99.30% | 63.3% | 23.3 | 0 | steady_cruise 0.963, smooth_turn 0.983, speed_limit 0.985 | PASS |
| drowsy_drivers | 60 | 25.9 | 1676 | 99.16% | 52.5% | 23.3 | 0 | smooth_turn 0.882, steady_cruise 0.956, smooth_launch 0.991 | PASS |
| careful_drivers | 60 | 27.2 | 2783 | 98.96% | 67.3% | 48.3 | 0 | steady_cruise 0.939, speed_bump 0.968, speed_limit 0.977 | PASS |
| steer_official | 60 | 26.2 | 1593 | 99.81% | 61.8% | 5.0 | 0 | speed_limit 0.993, smooth_launch 0.995, blinker_cancel 1.0 | PASS |
| steer_sign_positive | 60 | 22.4 | 1829 | 99.45% | 62.2% | 16.7 | 0 | steady_cruise 0.955, smooth_launch 0.978, speed_limit 0.993 | PASS |
| steer_wheel_unit | 60 | 27.2 | 2352 | 99.32% | 69.3% | 26.7 | 0 | steady_cruise 0.942, turn_signal_turn 0.982, smooth_launch 0.988 | PASS |
| steer_coarse_1deg | 60 | 34.5 | 1441 | 98.82% | 41.8% | 28.3 | 0 | steady_cruise 0.964, right_turn_pause 0.976, smooth_launch 0.977 | PASS |
| car_geometry_+10pct | 60 | 24.4 | 1630 | 99.39% | 55.0% | 16.7 | 0 | smooth_launch 0.98, steady_cruise 0.981, rain_speed 0.985 | PASS |
| car_geometry_-10pct | 60 | 24.3 | 1721 | 99.13% | 56.2% | 25.0 | 0 | steady_cruise 0.932, right_turn_pause 0.976, smooth_launch 0.981 | PASS |
| cruise_state_unknown | 60 | 26.2 | 1880 | 99.52% | 54.8% | 15.0 | 0 | smooth_turn 0.925, speed_limit 0.994, rain_speed 0.997 | PASS |
| nav_updates_1hz | 60 | 30.5 | 2029 | 99.26% | 58.1% | 25.0 | 0 | caution_slowdown 0.952, steady_cruise 0.964, speed_limit 0.978 | PASS |
| wiper_unknown_rain | 60 | 32.5 | 2001 | 99.10% | 48.3% | 30.0 | 0 | steady_cruise 0.949, speed_limit 0.986, smooth_launch 0.995 | PASS |
| slow_callbacks | 60 | 38.0 | 2781 | 99.64% | 57.4% | 16.7 | 0 | steady_cruise 0.969, smooth_launch 0.989, speed_limit 0.996 | PASS |
| worst_case | 60 | 22.9 | 1360 | 99.49% | 59.8% | 11.7 | 0 | steady_cruise 0.733, headlights 0.996, blinker_cancel 1.0 | PASS |
| fuzz: garbage input | 200 cases | | | | | | 0 | praise without speed: 0 | PASS |

Habits held back by the release gate (not shown to drivers) - precision they WOULD have:

| condition | anticipatory_braking | smooth_stop |
|---|---:|---:|
| baseline | 83.3% (6) | 93.9% (132) |
| speed_1hz | 84.6% (13) | 98.0% (151) |
| heavy_dropouts | 87.5% (8) | 99.0% (103) |
| no_heading_no_nav | 100.0% (9) | 97.8% (135) |
| no_nav_data | 75.0% (4) | 94.7% (94) |
| noisy_steering_x4 | 50.0% (6) | 95.8% (143) |
| cluster_bias_6pct | 80.0% (5) | 90.6% (96) |
| rain_night | 50.0% (12) | 95.1% (102) |
| heavy_rain | 60.0% (5) | 95.4% (131) |
| cut_in_storm | 100.0% (7) | 71.7% (92) |
| expressway | 60.0% (10) | 97.2% (107) |
| rural_curves | 88.9% (18) | 97.4% (155) |
| aggressive_drivers |  |  |
| novice_drivers | 95.2% (21) | 95.2% (104) |
| drowsy_drivers | 46.7% (15) | 93.6% (140) |
| careful_drivers | 90.0% (10) | 98.5% (197) |
| steer_official | 100.0% (2) | 96.5% (85) |
| steer_sign_positive | 85.7% (14) | 94.5% (110) |
| steer_wheel_unit | 92.3% (26) | 95.2% (124) |
| steer_coarse_1deg | 92.3% (13) | 93.1% (116) |
| car_geometry_+10pct | 64.3% (14) | 95.2% (125) |
| car_geometry_-10pct | 100.0% (5) | 96.4% (111) |
| cruise_state_unknown |  | 97.3% (75) |
| nav_updates_1hz | 100.0% (10) | 96.7% (121) |
| wiper_unknown_rain | 100.0% (1) | 97.2% (107) |
| slow_callbacks | 100.0% (6) | 93.3% (149) |
| worst_case |  |  |