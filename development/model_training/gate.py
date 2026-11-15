"""
Dual-key praise: a rule SUCCESS is shown only if the learned gate also agrees.
The gate can only VETO (turn SUCCESS into ABSTAIN); it can never create praise or a miss,
so every praise the driver sees is still explainable by a sourced rule.
Works with single-model checkpoints (train.py) and ensembles (train_forever.py).
"""
from __future__ import annotations

import numpy as np
import torch

import warnings

from model_training.ensemble import IncompatibleModel, calibrated_prob, load
from model_training.features import GATE_HABITS, judgment_tensor
from scoring_engine.habits import ABSTAIN, SUCCESS


def load_gate(path: str, strict: bool = False):
    """Returns the gate, or None (rules only) if the checkpoint was trained on another data contract."""
    try:
        models, temp, th, _meta = load(path, "cpu")
    except IncompatibleModel as e:
        if strict:
            raise
        warnings.warn(f"gate disabled, running rules only: {e}")
        return None
    torch.set_num_threads(1)   # parked-phase friendly; one core is plenty

    def gate(trip, D, judgments):
        idx = [k for k, j in enumerate(judgments) if j.habit in GATE_HABITS and j.outcome == SUCCESS and th.get(j.habit, 0) > 0]
        # (the engine applies the release hold AFTER the gate, so held habits are gated too and their
        #  dual-key precision can be measured before they are released)
        if not idx:
            return judgments
        xs, ss = zip(*[judgment_tensor(trip, D, judgments[k]) for k in idx])
        p = calibrated_prob(models, torch.tensor(np.stack(xs)), torch.tensor(np.stack(ss)), temp).numpy()
        for k, pk in zip(idx, p):
            j = judgments[k]
            j.meta["gate_p"] = float(pk)
            if pk < th[j.habit]:
                j.outcome, j.reason, j.level = ABSTAIN, "gate_veto", ""
        return judgments

    return gate
