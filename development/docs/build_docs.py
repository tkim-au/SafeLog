"""
Build docs/scenario_catalogue.csv and docs/logic_board.html from docs/catalogue.py
plus the latest evaluation reports.

    python -m docs.build_docs
"""
from __future__ import annotations

import csv
import html
import json
import textwrap
from collections import Counter
from pathlib import Path

from docs.catalogue import CATEGORIES, FIELDS, R, rows

ROOT = Path(__file__).resolve().parents[1]
REP = ROOT / "evaluation" / "reports"
E = html.escape


def _load(name):
    p = REP / name
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None


def wrap(t, n):
    return textwrap.wrap(t, n) or [""]


# ----------------------------------------------------------------------------- SVG helpers
def box(x, y, w, lines, cls="b", h=None, rx=6):
    h = h or 14 * len(lines) + 14
    out = [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" class="{cls}"/>']
    for k, ln in enumerate(lines):
        out.append(f'<text x="{x + w / 2}" y="{y + 18 + 14 * k}" text-anchor="middle">{E(ln)}</text>')
    return "".join(out), h


def arrow(x1, y1, x2, y2, label=""):
    s = f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" class="ln" marker-end="url(#ah)"/>'
    if label:
        s += f'<text x="{(x1 + x2) / 2 + 4}" y="{(y1 + y2) / 2 - 3}" class="lbl">{E(label)}</text>'
    return s


DEFS = ('<defs><marker id="ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
        '<path d="M0,0 L10,5 L0,10 z" fill="currentColor"/></marker></defs>')


def flow_svg(d):
    """Logic board for one scenario: opportunity -> abstain gates -> measurement -> decision."""
    kind = d["kind"]
    W, cx, bw = 720, 250, 400
    parts, y = [DEFS], 10
    b, h = box(cx - bw / 2, y, bw, ["OPPORTUNITY"] + wrap(d["opportunity"], 58), "b op")
    parts.append(b); y += h
    if kind == "tip":
        parts.append(arrow(cx, y, cx, y + 28)); y += 28
        b, h = box(cx - bw / 2, y, bw, ["NOT JUDGED"] + wrap(d["rule"], 58) + ["→ pre-drive tip / parked quiz"], "b ab")
        parts.append(b); y += h
        return _svg(parts, W, y + 10, d)
    gates = [g.strip() for g in d["abstain_and_guards"].split(";") if g.strip()]
    if kind in ("context", "exclusion", "internal"):
        gates = gates[:2]
    side_x = cx + bw / 2 + 30
    for g in gates:
        parts.append(arrow(cx, y, cx, y + 22)); y += 22
        lines = wrap(g + " ?", 50)
        b, h = box(cx - bw / 2 + 40, y, bw - 80, lines, "b gate", rx=16)
        parts.append(b)
        parts.append(arrow(cx + bw / 2 - 40, y + h / 2, side_x, y + h / 2, "yes"))
        b2, _ = box(side_x, y + h / 2 - 14, 150, ["ABSTAIN"], "b ab", h=28)
        parts.append(b2)
        y += h
    parts.append(arrow(cx, y, cx, y + 22, "no" if gates else "")); y += 22
    title = {"internal": "MEASURE (internal)", "context": "EFFECT", "exclusion": "EXPECTED ENGINE BEHAVIOUR"}.get(kind, "MEASURE & DECIDE")
    b, h = box(cx - bw / 2, y, bw, [title] + wrap(d["rule"], 58), "b me")
    parts.append(b); y += h
    if kind in ("praise", "praise_only"):
        parts.append(arrow(cx, y, cx, y + 26)); y += 26
        x0 = cx - bw / 2 - 40
        outs = [("SUCCESS", "shown + XP", "ok"), ("GUARD BAND", "ABSTAIN", "ab"),
                ("MISS", "internal only" if kind == "praise" else "→ ABSTAIN", "ms")]
        parts.append(f'<line x1="{x0 + 75}" y1="{y - 10}" x2="{x0 + 330 + 75}" y2="{y - 10}" class="ln"/>')
        for k, (t1, t2, c) in enumerate(outs):
            parts.append(arrow(x0 + 75 + k * 165, y - 10, x0 + 75 + k * 165, y + 8))
            b, _ = box(x0 + k * 165, y + 8, 150, [t1, t2], f"b {c}", h=44)
            parts.append(b)
        parts.append(f'<text x="{x0 + 75}" y="{y + 66}" text-anchor="middle" class="lbl">inside by &gt; guard</text>')
        parts.append(f'<text x="{x0 + 240}" y="{y + 66}" text-anchor="middle" class="lbl">within guard of line</text>')
        parts.append(f'<text x="{x0 + 405}" y="{y + 66}" text-anchor="middle" class="lbl">outside the line</text>')
        y += 72
    elif kind == "internal":
        parts.append(arrow(cx, y, cx, y + 24)); y += 24
        b, h = box(cx - bw / 2, y, bw, ["→ reward gate / next practice / insurer score (opt-in)", "never shown to the driver"], "b ms")
        parts.append(b); y += h
    return _svg(parts, W, y + 10, d)


def _svg(parts, W, H, d):
    lab = E(f'{d["id"]} {d["name_en"]}: logic flow')
    return (f'<svg viewBox="0 0 {W} {H}" role="img" aria-label="{lab}" class="flow">' + "".join(parts) + "</svg>")


def architecture_svg():
    p = [DEFS]
    def b(x, y, w, h, lines, cls="b"):
        s, _ = box(x, y, w, lines, cls, h=h)
        p.append(s)
    # sources
    b(10, 20, 170, 150, ["SIGNAL SOURCES", "Vehicle SDK: brake, steer,", "blinker, telltales, wiper,", "lights, seats, odometer", "AAOS: speed, gear, temp", "NaviHelper: limit, TBT,", "caution, camera, traffic"], "b op")
    b(220, 20, 200, 70, ["SignalCollector", "callbacks → 10 Hz frame", "freshness check (≤1.5 s)"])
    p.append(arrow(180, 55, 220, 55))
    p.append('<text x="183" y="48" class="lbl">subscribe</text>')
    # driving lane
    p.append('<rect x="205" y="110" width="430" height="150" rx="8" class="zone"/>')
    p.append('<text x="215" y="128" class="zl">WHILE DRIVING  (≈0.01 % CPU measured, budget < 5 %)</text>')
    b(220, 140, 190, 56, ["DriveRecorder", "24 B/frame · 0.8 MB/h"])
    b(430, 140, 190, 56, ["ChimeDetector", "1 practice habit, O(1)"])
    b(430, 205, 190, 46, ["SignalGate → chime", "busy? defer / drop"], "b ok")
    p.append(arrow(320, 90, 315, 140, "frame"))
    p.append(arrow(330, 90, 520, 140, ""))
    p.append(arrow(525, 196, 525, 205, ""))
    # parked lane
    p.append('<rect x="10" y="290" width="760" height="250" rx="8" class="zone"/>')
    p.append('<text x="20" y="308" class="zl">WHEN PARKED (gear P)  ≈0.1 s per driving hour</text>')
    b(20, 320, 140, 70, ["derive()", "a_long = Δv/1 s", "a_lat = v·yaw(steer)"])
    b(180, 320, 140, 70, ["detectors", "23 opportunity", "types"])
    b(340, 320, 140, 70, ["judges", "thresholds.yaml", "guard bands"])
    b(500, 320, 120, 70, ["dual-key gate", "TCN 25k params", "veto only"], "b gate")
    b(640, 320, 120, 70, ["Judgment", "SUCCESS / MISS", "/ ABSTAIN"])
    for x1, x2 in ((160, 180), (320, 340), (480, 500), (620, 640)):
        p.append(arrow(x1, 355, x2, 355))
    p.append(arrow(315, 196, 90, 320, "frames"))
    b(20, 420, 230, 100, ["SHOWN (praise only)", "good points '11 of 12'", "journal: 2 best scenes", "XP · points · streaks"], "b ok")
    b(270, 420, 230, 100, ["DriverProfile", "Beta posterior per habit", "Skill Index 0-100", "mastery = P10 ≥ 0.8"])
    b(520, 420, 240, 100, ["INTERNAL (never shown)", "risk monitors → reward gate", "next practice (positive text)", "insurer score (opt-in)"], "b ms")
    p.append('<line x1="700" y1="390" x2="700" y2="405" class="ln"/><line x1="135" y1="405" x2="700" y2="405" class="ln"/>')
    p.append(arrow(135, 405, 135, 420)); p.append(arrow(385, 405, 385, 420)); p.append(arrow(640, 405, 640, 420))
    p.append('<text x="140" y="401" class="lbl">SUCCESS only</text><text x="390" y="401" class="lbl">all judgments</text><text x="560" y="401" class="lbl">MISS + risk events</text>')
    return f'<svg viewBox="0 0 780 550" role="img" aria-label="Merged SafeLog system: drive-time recorder and chime, parked-time engine and gate" class="flow">{"".join(p)}</svg>'


def training_svg():
    p = [DEFS]
    def b(x, y, w, h, lines, cls="b"):
        s, _ = box(x, y, w, lines, cls, h=h)
        p.append(s)
    b(10, 20, 170, 110, ["SIMULATORS", "SafeLog-Sim (signal twin)", "SUMO (traffic, Korea OSM)", "CARLA (physics, weather)", "Pleos emulator replay"], "b op")
    b(210, 20, 150, 110, ["Pleos-schema trips", "clean = truth", "obs = noisy, 1 Hz,", "dropouts, nav off"])
    b(390, 20, 150, 50, ["Rule engine", "on obs"])
    b(390, 80, 150, 50, ["Same judges on", "clean + sim truth"])
    b(570, 20, 190, 110, ["match & label", "precision / recall /", "false praise by cause"])
    b(570, 160, 190, 60, ["train TCN gate", "RTX 3060 Ti (CUDA)"], "b gate")
    b(330, 160, 210, 60, ["calibrate (temperature)", "veto threshold for", "precision ≥ 99 %"])
    b(90, 160, 210, 60, ["export gate.pt / ONNX", "+ thresholds.yaml", "→ Kotlin app"], "b ok")
    p += [arrow(180, 75, 210, 75), arrow(360, 45, 390, 45), arrow(360, 105, 390, 105), arrow(540, 45, 570, 60),
          arrow(540, 105, 570, 95), arrow(665, 130, 665, 160), arrow(570, 190, 540, 190), arrow(330, 190, 300, 190)]
    return f'<svg viewBox="0 0 780 235" role="img" aria-label="Offline loop: simulate, label, train, calibrate, export" class="flow">{"".join(p)}</svg>'


CSS = """
:root{--bg:#fbfbf9;--fg:#1d1d1b;--mut:#6b6b66;--card:#fff;--ok:#1f7a4d;--ab:#8a6d1f;--ms:#a33a3a;--op:#2d5c8a;--line:#d9d8d2}
@media (prefers-color-scheme:dark){:root{--bg:#161615;--fg:#ecebe6;--mut:#9c9b95;--card:#1f1f1d;--ok:#5fc292;--ab:#d8b45a;--ms:#e07a7a;--op:#7fb0e0;--line:#3a3a37}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 "IBM Plex Sans","Noto Sans KR",system-ui,sans-serif}
header{padding:28px 32px 8px;max-width:1180px;margin:auto}h1{font-size:26px;margin:0 0 4px}h2{font-size:19px;margin:34px 0 10px}
.mut{color:var(--mut)}.wrap{display:grid;grid-template-columns:230px 1fr;gap:28px;max-width:1180px;margin:auto;padding:0 32px 60px}
nav{position:sticky;top:12px;align-self:start;font-size:13px;max-height:95vh;overflow:auto}nav a{display:block;color:var(--fg);text-decoration:none;padding:3px 0}
nav a:hover{color:var(--op)}.kpis{display:flex;flex-wrap:wrap;gap:12px;margin:14px 0}.kpi{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px 14px;min-width:150px}
.kpi b{display:block;font-size:22px}.kpi span{font-size:12px;color:var(--mut)}
details{background:var(--card);border:1px solid var(--line);border-radius:10px;margin:8px 0;padding:0 14px}summary{cursor:pointer;padding:11px 0;font-weight:600;list-style:none}
summary::-webkit-details-marker{display:none}.tag{font-size:11px;font-weight:600;border-radius:20px;padding:2px 8px;margin-left:6px;border:1px solid var(--line);color:var(--mut)}
.t-engine{color:var(--ok);border-color:var(--ok)}.t-confirm{color:var(--ab);border-color:var(--ab)}.t-tip{color:var(--mut)}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:6px 22px;font-size:13.5px;margin:4px 0 12px}.grid div b{color:var(--mut);font-weight:600;display:block;font-size:11.5px;text-transform:uppercase;letter-spacing:.04em}
svg.flow{width:100%;max-width:760px;height:auto;color:var(--fg);font-size:12px;margin:6px 0 14px}
svg .b{fill:var(--card);stroke:currentColor;stroke-width:1}svg .op{stroke:var(--op);stroke-width:1.6}svg .gate{stroke:var(--ab)}svg .ab{stroke:var(--ab);stroke-dasharray:4 3}
svg .ok{stroke:var(--ok);stroke-width:1.8}svg .ms{stroke:var(--ms)}svg .me{stroke:currentColor;stroke-width:1.4}svg text{fill:currentColor}
svg .ln{stroke:currentColor;stroke-width:1}svg .lbl{font-size:10.5px;fill:var(--mut)}svg .zone{fill:none;stroke:var(--line);stroke-width:1.5;stroke-dasharray:6 4}svg .zl{font-size:11.5px;fill:var(--mut);font-weight:600}
table{border-collapse:collapse;width:100%;font-size:13px;margin:8px 0}td,th{border-bottom:1px solid var(--line);padding:5px 8px;text-align:left;vertical-align:top}th{color:var(--mut);font-weight:600}
code,.f{font-family:"IBM Plex Mono",ui-monospace,monospace;font-size:12.5px}.f{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px 14px;white-space:pre-wrap;display:block}
figure{margin:0}figcaption{font-size:12.5px;color:var(--mut)}
@media (max-width:820px){.wrap{grid-template-columns:1fr}nav{position:static;max-height:none}.grid{grid-template-columns:1fr}}
"""

FORMULAS = """a_long(t) = [v(t+0.5 s) − v(t−0.5 s)] / 1 s                 # same as "speed change in one second" (Tesla, Progressive)
yaw(t)    = v · tan(δ_wheel / ratio) / (L · (1 + K·v²))          # L = 2.895 m (Grandeur), ratio ≈ 13.5, K ≈ 0.0025 s²/m²
a_lat(t)  = v · yaw(t)   (1-s mean)  — cross-checked with d(heading)/dt · v when FusedLocation is available

Judgment  = ABSTAIN  if any required signal is missing / FCA ≤ 3 s before / cruise on / value inside guard band
          = SUCCESS  if value is inside the boundary by more than the guard band
          = MISS     otherwise  (internal only; praise_only habits turn MISS into ABSTAIN)
guard     = 0.02 g (10 Hz) · 0.04 g (1 Hz) · 0.5 s (lead time) · 5 m (lead distance) · +2 km/h (entry speed)

Per habit h (after every trip, λ = 0.5^(1/15)):
  α_h ← α0 + λ(α_h − α0) + successes      β_h ← β0 + λ(β_h − β0) + misses      α0 = 0.7·4, β0 = 0.3·4
  skill_h = α_h / (α_h + β_h)              mastered ⇔ BetaInv(0.10; α_h, β_h) ≥ 0.80 and ≥ 5 opportunities

Skill Index (0–100) = 100 · Σ w_h · skill_h / Σ w_h     over habits with ≥ 5 opportunities
  w = 3 (tier A: crash-risk evidence) · 2 (tier B: legal duty, intent) · 1 (tier C: anticipation, comfort) · 0 (bonus)
  re-fit later with fleet outcomes:  log E[crashes] = log(km) + Σ β_h · (1 − skill_h)   (Poisson GLM, like Tesla's PCF)

XP per trip = Σ_successes  base_tier(A3 B2 C1 X5) · min(2, ∏ context multipliers: rain 1.5, night 1.25),  ≤ 10 per habit per trip
            + 10 per passed practice;   = 0 when the trip had severe speeding, school-zone overspeed, FCA, or ≥ 2 severe events
Points      = attendance / missions / quiz / mileage tier (plan v0.4 F8), monthly cap 3,000 P — never paid for distance"""


def main():
    rs = rows()
    with open(ROOT / "docs" / "scenario_catalogue.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(rs)
    ev_r, ev_g, bench = _load("eval_rules_only_test.json"), _load("eval_dual_key_test.json"), _load("benchmark.json")
    per = (ev_g or ev_r or {}).get("per_habit", {})
    kinds = Counter(d["kind"] for d in rs)
    status = Counter(d["status"] for d in rs)
    H = ['<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">',
         '<title>SafeLog Logic Board</title><link rel="preconnect" href="https://fonts.googleapis.com">',
         '<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;600&family=IBM+Plex+Mono&family=Noto+Sans+KR:wght@400;600&display=swap" rel="stylesheet">',
         f"<style>{CSS}</style></head><body>",
         "<header><h1>SafeLog Logic Board</h1>",
         '<div class="mut">Every driving-habit scenario SafeLog can judge from Pleos car signals: how each one is detected, calculated, guarded against false alarms, weighted and rewarded. Bad habits are never shown to the driver: a MISS stays internal.</div>']
    kp = [(len(rs), "scenarios catalogued"), (status["engine"], "implemented & simulated"), (kinds["praise"] + kinds["praise_only"], "praise habits"),
          (kinds["tip"], "not detectable → tips")]
    if ev_r and ev_g:
        kp += [(f'{ev_r["overall"]["praise_precision"] * 100:.1f}% → {ev_g["overall"]["praise_precision"] * 100:.1f}%', "praise precision, rules → dual-key (held-out trips)"),
               (f'{ev_g["overall"]["praise_recall"] * 100:.0f}%', "deserved praises given (dual-key)")]
    if bench:
        kp += [(f'{bench["in_drive_cpu_percent_of_one_core"]:.3f}%', "drive-time CPU, 1 core (Python ref.)"),
               (f'{bench["recorder_MB_per_hour"]:.1f} MB/h', "drive-time memory")]
    H.append('<div class="kpis">' + "".join(f'<div class="kpi"><b>{E(str(a))}</b><span>{E(b)}</span></div>' for a, b in kp) + "</div></header>")
    H.append('<div class="wrap"><nav><b>Sections</b><a href="#system">1 · Merged system</a><a href="#calc">2 · Calculation board</a><a href="#train">3 · Simulation & training loop</a><a href="#budget">4 · Compute budget</a>')
    for k, v in CATEGORIES.items():
        H.append(f'<a href="#cat-{k}">{k} · {E(v)}</a>')
    H.append('<a href="#table">Full table</a></nav><main>')
    H.append('<h2 id="system">1 · Merged system</h2><figure>' + architecture_svg() +
             '<figcaption>One engine, two phases. While moving, only a recorder and one O(1) chime detector run; every habit is judged once the car is parked, then the dual-key gate can veto a praise but never create one.</figcaption></figure>')
    H.append(f'<h2 id="calc">2 · Calculation board</h2><span class="f">{E(FORMULAS)}</span>')
    H.append('<h2 id="train">3 · Simulation & training loop</h2><figure>' + training_svg() +
             '<figcaption>Simulators produce trips in the exact Pleos signal schema; truth comes from the simulator, not from the rules being tested.</figcaption></figure>')
    if bench:
        H.append('<h2 id="budget">4 · Compute budget</h2><table><tr><th>Item</th><th>Measured</th></tr>' +
                 "".join(f"<tr><td>{E(k)}</td><td>{E(str(v))}</td></tr>" for k, v in bench.items()) +
                 '</table><p class="mut">Pleos performance guide: non-navigation app while driving &lt; 5 % CPU, parked ≤ 25 %, RAM ≤ 400 MB. Measured on a Python reference implementation; re-measure the Kotlin port with Android Studio Profiler in the Pleos emulator.</p>')
    for k, v in CATEGORIES.items():
        H.append(f'<h2 id="cat-{k}">{k} · {E(v)}</h2>')
        for d in [x for x in rs if x["id"].startswith(k)]:
            m = per.get(d["engine_habit"]) if d["engine_habit"] else None
            stat = ""
            if m:
                pr = m.get("praise_precision"); rc = m.get("praise_recall")
                f = lambda x: "–" if x is None or x != x else f"{x * 100:.1f}%"
                stat = f'<div><b>Simulation (held-out, dual-key)</b>precision {f(pr)} · recall {f(rc)} · praises {m.get("engine_success", 0)} · false {m.get("false_praise", 0)}</div>'
            H.append(f'<details id="{d["id"]}"><summary>{d["id"]} · {E(d["name_en"])} <span class="mut">· {E(d["name_ko"])}</span>'
                     f'<span class="tag">{E(d["kind"])}</span>{("<span class=tag>tier " + d["tier"] + "</span>") if d["tier"] else ""}'
                     f'<span class="tag t-{d["status"]}">{E(d["status"])}</span></summary>')
            H.append('<div class="grid">' + "".join(f"<div><b>{a}</b>{E(b)}</div>" for a, b in [
                ("Opportunity", d["opportunity"]), ("Success rule", d["rule"]), ("Signals / API", d["signals"]),
                ("Evidence / source", d["source"] or "—"), ("Abstain & false-alarm guards", d["abstain_and_guards"] or "—"),
                ("Engine habit", d["engine_habit"] or "—")]) + stat + "</div>")
            H.append(flow_svg(d) + "</details>")
    H.append('<h2 id="table">Full table</h2><table><tr><th>ID</th><th>Scenario</th><th>Kind</th><th>Tier</th><th>Status</th><th>Rule</th></tr>' +
             "".join(f'<tr><td>{d["id"]}</td><td>{E(d["name_en"])}<br><span class="mut">{E(d["name_ko"])}</span></td><td>{d["kind"]}</td><td>{d["tier"]}</td><td>{d["status"]}</td><td>{E(d["rule"])}</td></tr>' for d in rs) +
             "</table></main></div></body></html>")
    (ROOT / "docs" / "logic_board.html").write_text("".join(H), encoding="utf-8")
    print(f"catalogue: {len(rs)} scenarios -> docs/scenario_catalogue.csv, docs/logic_board.html")


if __name__ == "__main__":
    main()
