"""
Scoring, weighting and rewards.

1. Per-habit skill  = Beta-Binomial posterior with exponential forgetting
   (credibility theory in its simplest form: few opportunities -> estimate stays near the prior,
    so one lucky or unlucky trip cannot swing the score; see Denuit et al. 2019 for the UBI version).
2. "Mastered" badge only when the 10th-percentile of the posterior >= target
   (we only claim what the data can support).
3. SafeLog Skill Index (0-100) = evidence-weighted mean of habit skills.
   Weights follow an evidence tier (crash-risk evidence > legal duty > comfort/anticipation)
   and are meant to be re-fitted once fleet outcome data exists (Poisson GLM, like Tesla's PCF).
4. Rewards are paid on successes, never on distance, and are blocked on risky trips.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field

import numpy as np
from scipy.stats import beta as beta_dist

from scoring_engine.habits import ABSTAIN, MISS, SUCCESS, Judgment

# --------------------------------------------------------------------------- weights
TIER = {
    # Tier A — direct crash-risk evidence (hard braking, speeding, headway/FCW, vulnerable users, wet roads)
    "smooth_stop": "A", "speed_limit": "A", "school_zone": "A", "following_margin": "A", "rain_speed": "A",
    # Tier B — legal duty / communicating intent / occupant protection
    "turn_signal_turn": "B", "lane_change_signal": "B", "seat_belts": "B", "railroad_stop": "B", "headlights": "B",
    "smooth_turn": "B", "lane_keeping": "B", "rest_breaks": "B",
    # Tier C — anticipation & comfort (leading indicators, eco)
    "smooth_launch": "C", "anticipatory_braking": "C", "smooth_lane_change": "C", "speed_bump": "C",
    "caution_slowdown": "C", "steady_cruise": "C", "blinker_cancel": "C",
    # Bonus — success-only habits (no denominator, never in the index)
    "right_turn_pause": "X", "hazard_warning": "X", "fca_reaction": "X",
}
TIER_WEIGHT = {"A": 3.0, "B": 2.0, "C": 1.0, "X": 0.0}
TIER_XP = {"A": 3, "B": 2, "C": 1, "X": 5}
POP_PRIOR = 0.7          # prior success rate before any data (to be replaced by fleet average per habit)
PRIOR_STRENGTH = 4.0     # pseudo-opportunities
HALF_LIFE_TRIPS = 15     # recency: a habit learned 15 trips ago counts half
MASTERY_TARGET = 0.80
MIN_OPPS_TO_REPORT = 3
PER_TRIP_CAP = 10        # max successes per habit per trip that earn XP (anti-farming)

KO_LABEL = {
    "smooth_stop": "부드러운 정지", "smooth_launch": "부드러운 출발", "anticipatory_braking": "미리 감속",
    "turn_signal_turn": "회전 전 깜빡이", "smooth_turn": "부드러운 회전", "right_turn_pause": "우회전 전 멈춤",
    "lane_change_signal": "차선 변경 3초 전 깜빡이", "smooth_lane_change": "부드러운 차선 변경",
    "school_zone": "스쿨존 30km/h", "speed_bump": "과속방지턱 서행", "railroad_stop": "철길 앞 일시정지",
    "caution_slowdown": "주의 구간 감속", "hazard_warning": "비상등으로 뒤차 배려", "fca_reaction": "위기 대응",
    "speed_limit": "제한속도 지키기", "rain_speed": "빗길 20% 감속", "headlights": "전조등 켜기",
    "following_margin": "여유 있는 차간거리", "lane_keeping": "차로 안정 유지", "seat_belts": "모두 안전벨트",
    "rest_breaks": "2시간마다 휴식", "steady_cruise": "일정한 속도 유지", "blinker_cancel": "깜빡이 정리",
}


@dataclass
class HabitState:
    a: float = POP_PRIOR * PRIOR_STRENGTH
    b: float = (1 - POP_PRIOR) * PRIOR_STRENGTH
    n_success: int = 0
    n_opps: int = 0
    streak: int = 0
    best_streak: int = 0

    @property
    def mean(self):
        return self.a / (self.a + self.b)

    @property
    def lcb(self):
        return float(beta_dist.ppf(0.10, self.a, self.b))

    @property
    def n_eff(self):
        return self.a + self.b - PRIOR_STRENGTH


@dataclass
class DriverProfile:
    habits: dict[str, HabitState] = field(default_factory=lambda: defaultdict(HabitState))
    xp: int = 0
    points: int = 0
    trips: int = 0

    def update(self, judgments: list[Judgment]):
        lam = 0.5 ** (1 / HALF_LIFE_TRIPS)
        by = defaultdict(list)
        for j in judgments:
            if j.outcome != ABSTAIN:
                by[j.habit].append(j.outcome)
        for h, st in list(self.habits.items()):     # forgetting applies to all habits each trip
            if h not in by:
                continue
        for h, outs in by.items():
            st = self.habits[h]
            prior_a, prior_b = POP_PRIOR * PRIOR_STRENGTH, (1 - POP_PRIOR) * PRIOR_STRENGTH
            # decay evidence (not the prior) toward the prior
            st.a = prior_a + lam * (st.a - prior_a)
            st.b = prior_b + lam * (st.b - prior_b)
            s = sum(1 for o in outs if o == SUCCESS)
            f = len(outs) - s
            if TIER.get(h) == "X":
                f = 0  # success-only habits never accumulate misses
            st.a += s
            st.b += f
            st.n_success += s
            st.n_opps += len(outs)
            for o in outs:
                st.streak = st.streak + 1 if o == SUCCESS else 0
                st.best_streak = max(st.best_streak, st.streak)
        self.trips += 1

    def skill_index(self) -> float:
        num = den = 0.0
        for h, st in self.habits.items():
            w = TIER_WEIGHT.get(TIER.get(h, "C"), 1.0)
            if w == 0 or st.n_opps < 5:
                continue
            num += w * st.mean
            den += w
        return 100 * num / den if den else float("nan")

    def mastered(self) -> list[str]:
        return [h for h, st in self.habits.items() if TIER.get(h) != "X" and st.n_opps >= 5 and st.lcb >= MASTERY_TARGET]


CONTEXT_MULT = {"rain": 1.5, "night": 1.25}


def trip_rewards(judgments: list[Judgment], risk: dict, practice: list[str] | None = None) -> dict:
    """What the driver sees after parking: good points + XP + points. Never a miss."""
    by = defaultdict(lambda: [0, 0])  # habit -> [success, opportunities]
    xp = 0
    capped = defaultdict(int)
    for j in judgments:
        if j.outcome == ABSTAIN:
            if TIER.get(j.habit) == "X":
                continue
            continue
        by[j.habit][1] += 1
        if j.outcome == SUCCESS:
            by[j.habit][0] += 1
            if capped[j.habit] < PER_TRIP_CAP:
                m = 1.0
                for k, mult in CONTEXT_MULT.items():
                    if j.context.get(k):
                        m *= mult
                xp += TIER_XP[TIER.get(j.habit, "C")] * min(m, 2.0)
                capped[j.habit] += 1
    lines = []
    for h, (s, n) in sorted(by.items(), key=lambda kv: -TIER_WEIGHT.get(TIER.get(kv[0], "C"), 1) * kv[1][0]):
        if s == 0:
            continue
        if TIER.get(h) == "X":
            lines.append({"habit": h, "label": KO_LABEL.get(h, h), "text": f"{KO_LABEL.get(h, h)} {s}번"})
        elif n >= MIN_OPPS_TO_REPORT:
            lines.append({"habit": h, "label": KO_LABEL.get(h, h), "text": f"{KO_LABEL.get(h, h)} {n}번 중 {s}번"})
    practice_result = {}
    for p in practice or []:
        s, n = by.get(p, [0, 0])
        practice_result[p] = {"success": s, "opportunities": n,
                              "passed": n >= MIN_OPPS_TO_REPORT and s / max(n, 1) >= 0.8}
        if practice_result[p]["passed"]:
            xp += 10
    blocked = bool(risk.get("reward_blocked"))
    return {
        "good_points": lines[:2],          # the post-trip screen shows 1-2 good points
        "all_good_points": lines,
        "xp": 0 if blocked else int(round(xp)),
        "reward_blocked": blocked,         # UI shows good points + next challenge only, no reason text
        "practice": practice_result,
    }


def best_scenes(judgments: list[Judgment], k: int = 2) -> list[Judgment]:
    """Driving-journal scenes: the successes kept with the widest margin, one per habit."""
    seen, out = set(), []
    succ = [j for j in judgments if j.outcome == SUCCESS and TIER.get(j.habit) != "X"]
    succ.sort(key=lambda j: -(j.margin * TIER_WEIGHT.get(TIER.get(j.habit, "C"), 1)))
    for j in succ:
        if j.habit not in seen:
            out.append(j)
            seen.add(j.habit)
        if len(out) == k:
            break
    return out


def next_challenge(profile: DriverProfile) -> str | None:
    """Positive framing of the weakest habit (only in practice mode)."""
    cand = [(st.mean, h) for h, st in profile.habits.items()
            if TIER.get(h) not in ("X",) and st.n_opps >= MIN_OPPS_TO_REPORT]
    if not cand:
        return None
    _m, h = min(cand)
    return f"다음 도전: '{KO_LABEL.get(h, h)}' 한 번 더 해 봐요"
