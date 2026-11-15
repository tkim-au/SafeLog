"""Tiny temporal convolutional network (~40k parameters) — fits easily on-device or in the parked phase."""
import torch
import torch.nn as nn

from model_training.features import C, S


class Block(nn.Module):
    def __init__(self, cin, cout, d):
        super().__init__()
        self.conv = nn.Conv1d(cin, cout, 5, padding=2 * d, dilation=d)
        self.bn = nn.BatchNorm1d(cout)
        self.res = nn.Conv1d(cin, cout, 1) if cin != cout else nn.Identity()

    def forward(self, x):
        return torch.relu(self.bn(self.conv(x))) + self.res(x)


class GateTCN(nn.Module):
    def __init__(self, width=32):
        super().__init__()
        self.net = nn.Sequential(Block(C, width, 1), Block(width, width, 2), Block(width, width, 4),
                                 Block(width, width, 8), nn.Conv1d(width, width, 1))
        self.head = nn.Sequential(nn.Linear(2 * width + S, 64), nn.ReLU(), nn.Dropout(0.1), nn.Linear(64, 1))

    def forward(self, x, s):
        h = self.net(x)
        h = torch.cat([h.mean(-1), h.amax(-1), s], 1)
        return self.head(h).squeeze(-1)
