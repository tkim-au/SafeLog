### Stress test (30 trips per condition, gate: D:\TF_Hyundai\development\model_training\checkpoints\gate.pt)

| condition | trips | hours | praises | precision | recall | false / 100 trips | crashes | worst habits | result |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| baseline | 30 | 13.3 | 1163 | 99.74% | 62.3% | 10.0 | 0 | rain_speed 0.981, smooth_launch 0.986, blinker_cancel 1.0 | PASS |
| speed_1hz | 30 | 10.5 | 711 | 99.86% | 56.7% | 3.3 | 0 | speed_limit 0.992, blinker_cancel 1.0, headlights 1.0 | PASS |
| heavy_dropouts | 30 | 17.8 | 1024 | 99.22% | 54.2% | 26.7 | 0 | smooth_launch 0.945, smooth_turn 0.977, speed_limit 0.994 | PASS |
| no_heading_no_nav | 30 | 12.3 | 620 | 99.52% | 46.3% | 10.0 | 0 | speed_limit 0.983, smooth_launch 0.99, blinker_cancel 1.0 | PASS |
| no_nav_data | 30 | 13.5 | 786 | 100.00% | 45.8% | 0.0 | 0 | blinker_cancel 1.0, headlights 1.0, lane_change_signal 1.0 | PASS |
| noisy_steering_x4 | 30 | 11.2 | 621 | 99.84% | 47.9% | 3.3 | 0 | speed_limit 0.988, blinker_cancel 1.0, headlights 1.0 | PASS |
| cluster_bias_6pct | 30 | 14.8 | 1006 | 99.50% | 51.0% | 16.7 | 0 | smooth_turn 0.946, blinker_cancel 0.992, smooth_launch 0.992 | PASS |
| rain_night | 30 | 18.8 | 1695 | 99.82% | 64.1% | 10.0 | 0 | smooth_launch 0.99, speed_limit 0.994, blinker_cancel 1.0 | PASS |
| heavy_rain | 30 | 16.9 | 1429 | 99.23% | 63.0% | 36.7 | 0 | rain_speed 0.98, smooth_launch 0.98, speed_limit 0.983 | PASS |
| cut_in_storm | 30 | 11.1 | 797 | 98.75% | 50.3% | 33.3 | 0 | speed_limit 0.954, smooth_lane_change 0.982, smooth_launch 0.99 | PASS |
| expressway | 30 | 14.6 | 1055 | 99.53% | 56.1% | 16.7 | 0 | smooth_turn 0.952, speed_limit 0.989, smooth_launch 0.99 | PASS |
| rural_curves | 30 | 10.3 | 630 | 99.52% | 49.5% | 10.0 | 0 | smooth_turn 0.95, blinker_cancel 0.985, speed_limit 0.993 | PASS |
| aggressive_drivers | 30 | 9.9 | 300 | 100.00% | 63.7% | 0.0 | 0 | blinker_cancel 1.0, headlights 1.0, right_turn_pause 1.0 | PASS |
| novice_drivers | 30 | 10.3 | 760 | 99.21% | 59.5% | 20.0 | 0 | smooth_turn 0.97, speed_limit 0.985, smooth_launch 0.988 | PASS |
| drowsy_drivers | 30 | 11.6 | 700 | 99.57% | 51.1% | 10.0 | 0 | smooth_launch 0.985, blinker_cancel 1.0, headlights 1.0 | PASS |
| careful_drivers | 30 | 14.8 | 1282 | 99.14% | 61.7% | 36.7 | 0 | smooth_launch 0.971, speed_limit 0.982, rain_speed 0.987 | PASS |
| steer_official | 30 | 16.6 | 795 | 99.75% | 56.6% | 6.7 | 0 | speed_limit 0.983, blinker_cancel 1.0, headlights 1.0 | PASS |
| steer_sign_positive | 30 | 10.1 | 739 | 99.05% | 58.8% | 23.3 | 0 | smooth_launch 0.966, speed_limit 0.981, blinker_cancel 1.0 | PASS |
| steer_wheel_unit | 30 | 16.4 | 1176 | 99.57% | 63.4% | 16.7 | 0 | speed_limit 0.988, smooth_launch 0.992, blinker_cancel 1.0 | PASS |
| steer_coarse_1deg | 30 | 18.4 | 654 | 99.85% | 41.0% | 3.3 | 0 | blinker_cancel 0.989, headlights 1.0, rain_speed 1.0 | PASS |
| car_geometry_+10pct | 30 | 12.9 | 815 | 99.39% | 54.6% | 16.7 | 0 | smooth_launch 0.979, rain_speed 0.984, speed_limit 0.989 | PASS |
| car_geometry_-10pct | 30 | 14.4 | 866 | 99.08% | 51.6% | 26.7 | 0 | right_turn_pause 0.947, turn_signal_turn 0.969, smooth_launch 0.973 | PASS |
| cruise_state_unknown | 30 | 15.9 | 662 | 99.55% | 32.8% | 10.0 | 0 | smooth_turn 0.917, blinker_cancel 1.0, headlights 1.0 | PASS |
| nav_updates_1hz | 30 | 16.2 | 979 | 99.28% | 57.3% | 23.3 | 0 | caution_slowdown 0.909, speed_limit 0.944, smooth_launch 0.992 | PASS |
| wiper_unknown_rain | 30 | 13.8 | 839 | 99.52% | 45.5% | 13.3 | 0 | speed_limit 0.988, blinker_cancel 1.0, headlights 1.0 | PASS |
| slow_callbacks | 30 | 20.3 | 1374 | 99.78% | 51.9% | 10.0 | 0 | speed_limit 0.99, smooth_launch 0.993, blinker_cancel 1.0 | PASS |
| worst_case | 30 | 12.3 | 535 | 99.44% | 48.6% | 10.0 | 0 | headlights 0.993, blinker_cancel 1.0, seat_belts 1.0 | PASS |
| fuzz: garbage input | 200 cases | | | | | | 0 | praise without speed: 0 | PASS |

Habits held back by the release gate (not shown to drivers) - precision they WOULD have:

| condition | anticipatory_braking | smooth_stop | steady_cruise |
|---|---:|---:|---:|
| baseline | 100.0% (2) | 98.3% (59) | 100.0% (28) |
| speed_1hz | 83.3% (6) | 100.0% (63) | 90.9% (11) |
| heavy_dropouts | 100.0% (4) | 100.0% (62) | 100.0% (9) |
| no_heading_no_nav | 100.0% (1) | 100.0% (49) | 100.0% (5) |
| no_nav_data | 100.0% (1) | 98.0% (49) |  |
| noisy_steering_x4 | 100.0% (3) | 95.2% (63) | 100.0% (4) |
| cluster_bias_6pct | 0.0% (1) | 100.0% (51) | 100.0% (21) |
| rain_night | 100.0% (6) | 96.3% (54) | 100.0% (47) |
| heavy_rain | 75.0% (4) | 96.8% (31) | 98.1% (52) |
| cut_in_storm | 100.0% (8) | 77.1% (35) | 100.0% (2) |
| expressway | 100.0% (2) | 94.5% (55) | 97.2% (36) |
| rural_curves | 100.0% (3) | 96.2% (78) |  |
| aggressive_drivers |  |  | 100.0% (2) |
| novice_drivers | 100.0% (1) | 93.0% (43) | 93.3% (15) |
| drowsy_drivers | 42.9% (14) | 93.3% (90) | 100.0% (5) |
| careful_drivers | 87.5% (8) | 100.0% (110) | 91.9% (37) |
| steer_official | 100.0% (1) | 91.9% (62) | 100.0% (13) |
| steer_sign_positive | 100.0% (11) | 98.2% (56) | 100.0% (5) |
| steer_wheel_unit | 86.4% (22) | 95.7% (93) | 94.2% (69) |
| steer_coarse_1deg | 100.0% (7) | 90.0% (40) | 92.3% (13) |
| car_geometry_+10pct | 71.4% (7) | 96.0% (50) | 100.0% (7) |
| car_geometry_-10pct | 100.0% (1) | 100.0% (50) | 94.4% (36) |
| cruise_state_unknown |  | 100.0% (21) |  |
| nav_updates_1hz | 100.0% (7) | 96.8% (63) | 100.0% (8) |
| wiper_unknown_rain |  | 96.2% (52) | 88.6% (44) |
| slow_callbacks | 100.0% (4) | 96.2% (80) | 100.0% (29) |
| worst_case |  |  |  |