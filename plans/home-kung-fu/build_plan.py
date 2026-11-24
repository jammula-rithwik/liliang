#!/usr/bin/env python3
"""居家功夫 — Home Kung Fu: an 8-week, 5-day bodyweight + Shaolin + Tai Chi plan.

One source of truth, two outputs:
  - Home-Kung-Fu-Plan.pdf   the printable plan with clickable video references
  - ingest/*.json           Lìliàng folder-ingest files (exercises, forms,
                            workouts, schedule) so the plan runs inside the app

    pip install reportlab fonttools
    python plans/home-kung-fu/build_plan.py [--start 2026-11-30]
"""

import argparse
import re
import importlib.util
import json
from datetime import date, timedelta
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont as RLFont
from reportlab.platypus import (BaseDocTemplate, CondPageBreak, Frame, KeepTogether, NextPageTemplate,
                                PageBreak, PageTemplate, Paragraph, Spacer, Table, TableStyle)

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

# Reuse the guide's fonts, palette and drawing helpers.
_spec = importlib.util.spec_from_file_location("guide", ROOT / "scripts" / "build-guide.py")
G = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(G)

INK, INK_DIM, LINE, CARD = G.INK, G.INK_DIM, G.LINE, G.CARD
VERMILLION, GOLD, JADE, BONE = G.VERMILLION, G.GOLD, G.JADE, G.BONE
IRON = colors.HexColor("#6F8796")
MIST = colors.HexColor("#8FA3B8")

# Hanzi the brush subset lacks fall back to Microsoft YaHei (Windows) for the PDF.
_msyh = Path("C:/Windows/Fonts/msyh.ttc")
FALLBACK = None
if _msyh.exists():
    pdfmetrics.registerFont(RLFont("YaHei", str(_msyh), subfontIndex=0))
    FALLBACK = "YaHei"


def zh(text: str) -> str:
    def repl(m):
        run = m.group(1)
        if all(ord(c) in G.MSZ_CMAP for c in run) or not FALLBACK:
            return f'<font name="MSZ">{run}</font>'
        return f'<font name="{FALLBACK}">{run}</font>'
    return G.CJK.sub(repl, text)


G.zh = zh  # the guide's table() and Paragraph helpers use the fallback too


def hz(c, font_size, x, y, text, centred=False):
    """Draw hanzi on a canvas with the brush face, or the fallback if a glyph is missing."""
    font = "MSZ" if all(ord(ch) in G.MSZ_CMAP for ch in text) or not FALLBACK else FALLBACK
    c.setFont(font, font_size)
    (c.drawCentredString if centred else c.drawString)(x, y, text)


S = dict(G.S)
S["small"] = ParagraphStyle("small", parent=G.S["cell"], fontSize=8.2, leading=10.5, textColor=INK_DIM)
S["link"] = ParagraphStyle("link", parent=G.S["cell"], fontSize=8.6, leading=11)


def P(text, style="body"):
    return Paragraph(zh(text), S[style])


def bullets(items):
    return [Paragraph(zh(t), S["bullet"], bulletText="•") for t in items]


# ───────────────────────── reference videos ─────────────────────────
# Every entry was taken from YouTube's own search results (video id, title,
# channel, length) on 24 Nov 2026.

V = {
    "kfr_daily": ("Kung Fu Basic Training at Home: 15-Minute Daily Routine, Lesson One", "Kung Fu Republic", "16:17", "owYawbxQ134"),
    "yl_beginners": ("Shaolin Kung Fu Training for Beginners", "Shifu Yan Lei", "5:48", "7BShP7ZD2bU"),
    "yl_stances": ("The 5 Fundamental Shaolin Stances", "Shifu Yan Lei", "6:04", "w-4n7cbNXZw"),
    "yl_horse": ("Kung Fu Horse Stance: Internal and External", "Shifu Yan Lei", "15:57", "T-m7-ikRIYI"),
    "uk_punch": ("Powerful Shaolin Kung Fu Punch: Basics Tutorial", "Shaolin Temple UK", "8:33", "x56TN5EtNzg"),
    "song_basics": ("Shaolin Kung Fu Wushu Basic Form Training for Beginners", "Master Song Kung Fu", "12:52", "H71OH6wGVVs"),
    "wang_kicks": ("Shaolin Kung Fu Class 8: Basic Kicks", "Wang Shifu", "4:26", "CZe5hDY-wwg"),
    "bao_wbq1": ("Five Stance Form (Wu Bu Quan) Tutorial 1", "David Bao", "11:31", "G3-Drrupfxo"),
    "bao_wbq_demo": ("Five Stance Form (Wu Bu Quan), full demonstration", "David Bao", "1:17", "HBCJyoCptKY"),
    "shy_wbq1": ("Wu Bu Quan (5 Stances Beginner Form), Section 1", "Shi Heng Yi Online", "54:00", "2F_4JKcmPX0"),
    "shy_wbq_full": ("Wu Bu Quan (5 Stances Beginner Form), Complete Routine", "Shi Heng Yi Online", "46:35", "4MPGqtp19VI"),
    "lam_beginners": ("Tai Chi for Beginners: Free Lesson and Introduction", "Dr Paul Lam, Tai Chi Productions", "44:26", "hIOHGrYCEJ4"),
    "lam_playlist": ("Tai Chi for Beginners Series (playlist)", "Dr Paul Lam, Tai Chi Productions", "22 videos",
                     "playlist?list=PLMypbNPFKGgRcuiKMpj79cE-VHP4I2zhB"),
    "shy_bdj": ("Shaolin Qi Gong 20-Minute Daily Morning Routine (Ba Duan Jin)", "Shi Heng Yi Online", "22:36", "UpN3AcXLSSk"),
    "warrior_stretch": ("15 Minute Stretching Routine for Fighters and Martial Artists", "Warrior Martial Arts", "15:57", "UiO3WBOxUsc"),
    "pushup_zero": ("Can't Do a Push-Up? How to Start Push-Ups from Zero", "Fit Father Project", "9:35", "CCZGD55NxGo"),
    "pushup_levels": ("The 7 Levels of Push-Ups", "Calisthenicmovement", "8:57", "X7VxeubJSsA"),
    "squat": ("Squat (Bodyweight), Fit for the 500", "IU Health Sports Performance", "1:10", "8uoaYwS6iFM"),
    "madfit": ("30 Min Full Body Workout for Total Beginners (all standing, low impact)", "MadFit", "32:23", "3rdsdh5fVeY"),
}


def url(key):
    vid = V[key][3]
    return f"https://www.youtube.com/{vid}" if vid.startswith("playlist") else f"https://youtu.be/{vid}"


def vlink(key, label=None):
    title, channel, length, _ = V[key]
    text = label or f"{channel} · {length}"
    return f'<a href="{url(key)}" color="#C73E2E"><u>{text}</u></a>'


# ───────────────────────── the programme (data) ─────────────────────────
# Each item: (exercise, sets, reps-or-time, rest seconds, cue, video key or None)

STRENGTH = {
    "A": {
        "focus": "Squat · push · hinge · pull · core",
        "foundation": [
            ("Bodyweight Squat", 3, "10–15", 75, "Sit between the heels, chest proud, knees track the toes.", "squat"),
            ("Incline Pushups", 3, "6–12", 75, "Hands on a sturdy table or bed. Body one straight plank.", "pushup_zero"),
            ("Glute Bridge", 3, "12–15", 60, "Drive through heels, squeeze glutes 1 s at the top.", None),
            ("Prone Y-T-W Raise", 3, "6 each letter", 60, "Face down, thumbs up, lift arms into Y, T, W. Slow.", None),
            ("Plank", 3, "20–40 s", 45, "Ribs down, glutes tight. Stop when the hips sag.", None),
            ("Dead Bug", 3, "6–10 / side", 45, "Low back pressed to the floor the whole time.", None),
        ],
        "build": [
            ("Pause Squat", 4, "10–15", 75, "2 s pause at the bottom. Add a backpack if 15 feels easy.", "squat"),
            ("Pushups", 4, "5–12", 90, "Full push-ups; back to incline for the last set if needed.", "pushup_levels"),
            ("Single-Leg Glute Bridge", 3, "8–12 / side", 60, "Hips stay level; other knee hugged to the chest.", None),
            ("Prone Y-T-W Raise", 3, "8 each letter", 60, "Hold each top position 2 s.", None),
            ("Plank", 3, "40–60 s", 45, "Add a shoulder tap every 5 s once 60 s is easy.", None),
            ("Dead Bug", 3, "10–12 / side", 45, "Exhale fully as the arm and leg reach.", None),
        ],
    },
    "B": {
        "focus": "Single leg · push · posterior chain · anti-rotation",
        "foundation": [
            ("Reverse Lunge", 3, "6–10 / side", 75, "Step back, both knees to 90°. Hold a wall if balance wobbles.", None),
            ("Knee Pushups", 3, "6–12", 75, "From the knees, hips in line. Chest to fist-height off the floor.", "pushup_zero"),
            ("Superman Hold", 3, "15–30 s", 60, "Lift chest and legs a few cm. Long neck, eyes down.", None),
            ("Side Plank", 3, "15–30 s / side", 45, "From knees at first; straight line head to knee.", None),
            ("Mountain Climbers", 3, "10–20 / side", 60, "Slow and controlled; plank stays still.", None),
            ("Wall Slides", 2, "30 s", 30, "Back and arms against the wall; slide up without arching.", None),
        ],
        "build": [
            ("Bulgarian Split Squat", 3, "6–10 / side", 90, "Rear foot on a chair or sofa. Front shin near vertical.", None),
            ("Pushups", 3, "max − 2", 90, "Stop two short of failure. Note the number each week.", "pushup_levels"),
            ("Superman Hold", 3, "30–45 s", 60, "Add a slow swim (arms Y → W) for the last 10 s.", None),
            ("Side Plank", 3, "30–45 s / side", 45, "Full side plank from the feet.", None),
            ("Mountain Climbers", 3, "20–30 / side", 60, "Pick up the pace while the hips stay level.", None),
            ("Wall Slides", 2, "45 s", 30, "", None),
        ],
    },
    "C": {
        "focus": "Kung fu strength · legs · core · crawling",
        "foundation": [
            ("Split Squat", 3, "6–10 / side", 75, "Static lunge: drop straight down, back knee hovers.", None),
            ("Pushups", 3, "max − 2 (incline ok)", 90, "Use the hardest version you can do for 5+ clean reps.", "pushup_zero"),
            ("Horse Stance", 3, "30–60 s", 60, "马步: feet 2 shoulder-widths, thighs toward parallel, back upright.", "yl_horse"),
            ("Bird Dog", 3, "6–8 / side", 45, "Reach long; don't let the hips rotate.", None),
            ("Hollow Hold", 3, "15–30 s", 45, "Tuck version: knees bent, low back flat.", None),
            ("Bear Crawl", 3, "20 s", 60, "Knees 3 cm off the floor; small steps forward and back.", None),
        ],
        "build": [
            ("Cossack Squats", 3, "6–8 / side", 75, "Side-to-side deep squat; builds the drop stance (仆步).", None),
            ("Pushups", 4, "max − 2", 90, "Try 1 set with hands narrow (diamond-ish).", "pushup_levels"),
            ("Horse Stance", 3, "60–120 s", 60, "Lower each week. Breathe slowly through the nose.", "yl_horse"),
            ("Bird Dog", 3, "8–10 / side", 45, "Hold the reach 2 s.", None),
            ("Hollow Hold", 3, "30–45 s", 45, "Straighten the legs as you get stronger.", None),
            ("Bear Crawl", 3, "30–40 s", 60, "Add lateral crawls on the last set.", None),
        ],
    },
}

SHAOLIN = {
    "foundation": [
        ("Five Stances Hold", 3, "20 s each stance", 30,
         "马步 horse · 弓步 bow · 虚步 empty · 仆步 drop · 歇步 crossed. Both sides where it applies.", "yl_stances"),
        ("Horse Stance Punches", 3, "20 alternating", 45, "冲拳 from 马步: fist turns over at the end, other fist pulls to the hip.", "uk_punch"),
        ("Bow Stance Punches", 2, "10 / side", 45, "弓步冲拳: drive from the back heel through the hip.", "uk_punch"),
        ("Front Stretch Kick", 2, "10 / leg", 30, "正踢腿: straight leg swings up, toes pulled back. Walk the length of the room.", "wang_kicks"),
        ("Crescent Kicks", 2, "8 / leg each way", 30, "外摆腿 outside / 里合腿 inside. Big, relaxed arc.", "song_basics"),
        ("Front Snap Kick", 2, "8 / leg", 30, "弹腿: chamber the knee, snap the instep out, recoil.", "wang_kicks"),
        ("Horse Stance", 1, "max hold", 0, "Finisher. Write the time down.", "yl_horse"),
        ("Kung Fu Stretch", 1, "8 min", 0, "Hamstrings, hip flexors, butterfly, side splits. Easy breathing.", "warrior_stretch"),
    ],
    "build": [
        ("Five Stances Hold", 3, "30 s each stance", 30, "Lower stances. Flow from one to the next without standing up.", "yl_stances"),
        ("Horse Stance Punches", 3, "30 alternating", 45, "Last 10 at speed; keep the stance still.", "uk_punch"),
        ("Bow Stance Punches", 3, "12 / side", 45, "Exhale sharply on each punch.", "uk_punch"),
        ("Front Stretch Kick", 3, "12 / leg", 30, "Height comes from flexibility, not momentum.", "wang_kicks"),
        ("Crescent Kicks", 2, "10 / leg each way", 30, "", "song_basics"),
        ("Front Snap Kick", 3, "10 / leg", 30, "Add a punch with the kick: 弹踢冲拳 from Wu Bu Quan.", "wang_kicks"),
        ("Horse Stance", 1, "max hold", 0, "Target: 2 minutes by week 8.", "yl_horse"),
        ("Kung Fu Stretch", 1, "8 min", 0, "", "warrior_stretch"),
    ],
}

FORMS = {
    "foundation": [
        ("Wu Bu Quan Practice", 1, "20 min", 0, "五步拳. Learn 3 moves a week (see the skill track). Slow, then smooth.", "bao_wbq1"),
        ("Tai Chi", 1, "20 min", 0, "Dr Lam's beginner lesson: warm-up and qigong first, then movements 1–2.", "lam_beginners"),
    ],
    "build": [
        ("Wu Bu Quan Practice", 1, "20 min", 0, "Whole form, both directions, 5–8 times. Film one run in week 8.", "shy_wbq1"),
        ("Tai Chi", 1, "20 min", 0, "Continue Dr Lam's beginner series, one new movement a week.", "lam_playlist"),
    ],
}

QIGONG = [("Ba Duan Jin", 1, "20 min", 0, "Optional on rest days. Gentle; leave refreshed, not tired.", "shy_bdj")]

WU_BU_QUAN_MOVES = [
    ("Ready: feet together, fists at the hips", "预备势 · 并步抱拳"),
    ("Bow stance punch", "弓步冲拳"),
    ("Snap kick with punch", "弹踢冲拳"),
    ("Horse stance block and punch", "马步架打"),
    ("Crossed stance cover and punch", "歇步盖打"),
    ("Knee raise, piercing palm", "提膝穿掌"),
    ("Drop stance, piercing palm", "仆步穿掌"),
    ("Empty stance, lifting palm", "虚步挑掌"),
    ("Feet together, fists meet", "并步对拳"),
    ("Close: return to ready", "还原"),
]

SKILL_TRACK = [
    # week, Wu Bu Quan, Tai Chi, test
    (1, "Learn the five stances; moves 1–3", "Lesson: warm-up and qigong", "Baseline: max push-ups, horse stance, plank"),
    (2, "Moves 4–6", "Movement 1", ""),
    (3, "Moves 7–10", "Movement 2", ""),
    (4, "Whole form, slowly, 3 times", "Review 1–2", "Lighter week: 2 sets per exercise. Re-test."),
    (5, "Whole form both directions (Shi Heng Yi Section 1)", "Movement 3", "Phase 2: Build variations start"),
    (6, "Sharpen stances: lower, stiller", "Movement 4", ""),
    (7, "Add power: snap the kicks and punches", "Movements 5–6", ""),
    (8, "Film one run, side on and front on", "All six movements together", "Final test. Compare with week 1."),
]

WEEK = [
    ("Mon", "力", "Strength A", "strength"),
    ("Tue", "少林", "Shaolin Basics", "shaolin"),
    ("Wed", "力", "Strength B", "strength"),
    ("Thu", "息", "Rest · optional Ba Duan Jin", "rest"),
    ("Fri", "力", "Strength C", "strength"),
    ("Sat", "形", "Forms & Tai Chi", "forms"),
    ("Sun", "息", "Rest", "rest"),
]

# New exercise definitions for Lìliàng (names the app doesn't ship).
NEW_EXERCISES = [
    # name, muscle, category, logFormat, perSide
    ("Bodyweight Squat", "Legs", "Strength", "reps", False),
    ("Pause Squat", "Legs", "Strength", "reps", False),
    ("Incline Pushups", "Chest", "Strength", "reps", False),
    ("Knee Pushups", "Chest", "Strength", "reps", False),
    ("Glute Bridge", "Glutes", "Strength", "reps", False),
    ("Single-Leg Glute Bridge", "Glutes", "Strength", "reps", True),
    ("Prone Y-T-W Raise", "Back", "Strength", "reps", False),
    ("Dead Bug", "Abs", "Core", "reps", True),
    ("Reverse Lunge", "Legs", "Strength", "reps", True),
    ("Split Squat", "Legs", "Strength", "reps", True),
    ("Superman Hold", "Back", "Core", "seconds", False),
    ("Side Plank", "Abs", "Core", "seconds", True),
    ("Mountain Climbers", "Abs", "Conditioning", "reps", True),
    ("Bird Dog", "Abs", "Core", "reps", True),
    ("Hollow Hold", "Abs", "Core", "seconds", False),
    ("Bear Crawl", "Full-Body", "Conditioning", "seconds", False),
    ("Five Stances Hold", "Legs", "Martial Arts", "seconds", False),
    ("Horse Stance Punches", "Full-Body", "Martial Arts", "reps", False),
    ("Bow Stance Punches", "Full-Body", "Martial Arts", "reps", True),
    ("Front Stretch Kick", "Legs", "Martial Arts", "reps", True),
    ("Crescent Kicks", "Legs", "Martial Arts", "reps", True),
    ("Front Snap Kick", "Legs", "Martial Arts", "reps", True),
    ("Kung Fu Stretch", "Full-Body", "Mobility", "minutes", False),
    ("Wu Bu Quan Practice", "Full-Body", "Martial Arts", "minutes", False),
    ("Ba Duan Jin", "Full-Body", "Martial Arts", "minutes", False),
]
# Already in Lìliàng's library (referenced, never redefined): Pushups, Plank,
# Horse Stance, Tai Chi, Wall Slides, Bulgarian Split Squat, Cossack Squats.

WARMUP = [
    "Joint circles, top to bottom: neck, shoulders, elbows, wrists, waist, hips, knees, ankles (2 min).",
    "March or easy jumping jacks (1 min).",
    "Arm swings and leg swings, front and side, 10 each (1 min).",
    "10 slow bodyweight squats, 5 inchworms, 5 bow-stance lunges each side (2 min).",
    "Shaolin days: add the arm exercises from Kung Fu Republic's daily routine (1 min).",
]
COOLDOWN = [
    "Strength days: 5 min easy stretching (quads, hamstrings, chest in a doorway, child's pose).",
    "Or the first 5 minutes of Shi Heng Yi's Ba Duan Jin, a calm way to finish.",
]


# ───────────────────────── ingest JSON ─────────────────────────

def seconds_of(spec: str):
    """'20–40 s' -> 40, '8 min' -> 480, '10 / side' -> None."""
    m = re.search(r"(\d+)\s*(s|min)\b", spec)
    if not m:
        return None
    return int(m.group(1)) * (60 if m.group(2) == "min" else 1)


def template_items(rows):
    items = []
    for name, sets, reps, rest, cue, vkey in rows:
        item = {"exercise": name, "sets": sets}
        dur = seconds_of(reps)
        if dur and re.search(r"\d\s*(s|min)\b", reps):
            item["durationSeconds"] = dur
        else:
            item["reps"] = reps
        if rest:
            item["restSeconds"] = rest
        note = cue
        if vkey:
            note = (note + " " if note else "") + f"Video: {V[vkey][1]}, {url(vkey)}"
        if note:
            item["notes"] = note
        items.append(item)
    return items


def build_ingest(out: Path, start: date):
    out.mkdir(parents=True, exist_ok=True)
    exercises = [{"name": n, "muscle": m, "category": c, "logFormat": f, "bodyweight": True, "perSide": ps}
                 for n, m, c, f, ps in NEW_EXERCISES]
    forms = {"forms": [{
        "id": "shaolin_wu_bu_quan",
        "title": "五步拳 Wu Bu Quan",
        "subtitle": "Five Stance Fist · the classic first form",
        "discipline": "Shaolin Kung Fu",
        "shortTitle": "Wu Bu Quan",
        "moves": [{"index": i + 1, "name": f"{zh_name} · {en}", "section": "Form"} for i, (en, zh_name) in enumerate(WU_BU_QUAN_MOVES)],
    }]}
    workouts = []
    for phase, tag in (("foundation", "Foundation"), ("build", "Build")):
        for key in "ABC":
            workouts.append({"name": f"Home Strength {key} · {tag}",
                             "notes": f"居家功夫 Home Kung Fu, {STRENGTH[key]['focus']}. 7 min warm-up first.",
                             "items": template_items(STRENGTH[key][phase])})
        workouts.append({"name": f"Shaolin Basics · {tag}", "discipline": "Shaolin Kung Fu",
                         "notes": "Stances, punches, kicks, stretch. 7 min warm-up first.",
                         "items": template_items(SHAOLIN[phase])})
        workouts.append({"name": f"Forms & Tai Chi · {tag}", "discipline": "Shaolin Kung Fu",
                         "notes": "五步拳 then Tai Chi. 5 min warm-up first.",
                         "items": template_items(FORMS[phase])})
    workouts.append({"name": "Ba Duan Jin (optional)", "discipline": "Qigong",
                     "notes": "Rest-day qigong.", "items": template_items(QIGONG)})

    days = []
    for week in range(1, 9):
        tag = "Foundation" if week <= 4 else "Build"
        for day, _, label, kind in WEEK:
            d = {"week": week, "day": day.lower(), "label": label}
            if kind == "strength":
                d["workout"] = f"Home Strength {label[-1]} · {tag}"
            elif kind == "shaolin":
                d["workout"] = f"Shaolin Basics · {tag}"
            elif kind == "forms":
                d["workout"] = f"Forms & Tai Chi · {tag}"
            days.append(d)
    schedule = {"plans": [{"name": "居家功夫 Home Kung Fu · 8 weeks", "startDate": start.isoformat(), "weeks": 8,
                           "active": True, "repeatWeekly": False, "useEngine": False, "days": days}]}

    def dump(name, obj):
        (out / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    dump("exercises.json", {"exercises": exercises})
    dump("forms.json", forms)
    dump("workouts.json", {"workouts": workouts})
    dump("schedule.json", schedule)
    return len(exercises), len(workouts), len(days)


# ───────────────────────── PDF ─────────────────────────

W, H = A4
M = 18 * mm
KIND_COLOUR = {"strength": IRON, "shaolin": VERMILLION, "forms": GOLD, "rest": MIST}


def cover(c, doc):
    c.saveState()
    c.setFillColor(G.PAPER_DARK)
    c.rect(0, 0, W, H, stroke=0, fill=1)
    G.cloud(c, 30 * mm, H - 40 * mm, 7 * mm, GOLD, 0.35, width=1.4)
    G.cloud(c, W - 42 * mm, H - 120 * mm, 9 * mm, GOLD, 0.22, flip=True, width=1.4)
    # mountains
    import random
    rng = random.Random(3)
    for layer in range(3):
        depth = layer / 2
        top = 64 * mm * (1 - depth * 0.45)
        p = c.beginPath()
        p.moveTo(0, 0)
        p.lineTo(0, top - 8 * mm)
        peaks = 4 + layer
        seg = W / peaks
        for i in range(peaks):
            x0 = seg * i
            px = x0 + seg * (0.35 + rng.random() * 0.3)
            py = top + rng.random() * 16 * mm
            p.curveTo(px - seg * 0.2, py - 6 * mm, px - seg * 0.05, py, px, py)
            ey = top - (8 + rng.random() * 12) * mm
            p.curveTo(px + seg * 0.15, py, x0 + seg * 0.8, ey, x0 + seg, ey)
        p.lineTo(W, 0)
        p.close()
        c.setFillColor(colors.HexColor("#41525B"))
        c.setFillAlpha(0.10 + 0.10 * depth)
        c.drawPath(p, fill=1, stroke=0)
    c.setFillAlpha(1)
    # two seals: 居家 + 功夫
    c.setFillColor(VERMILLION)
    c.roundRect(W / 2 - 46 * mm, H - 135 * mm, 42 * mm, 42 * mm, 6 * mm, stroke=0, fill=1)
    c.setStrokeColor(GOLD)
    c.setLineWidth(1.4)
    c.roundRect(W / 2 - 43 * mm, H - 132 * mm, 36 * mm, 36 * mm, 4 * mm, stroke=1, fill=0)
    c.setFillColor(BONE)
    hz(c, 30, W / 2 - 25 * mm, H - 122 * mm, "居家", centred=True)
    G.seal(c, W / 2 + 25 * mm, H - 114 * mm, 21 * mm, "功", True, colors.HexColor("#1E1A15"), GOLD, BONE, size=26 * mm)
    c.setFillColor(BONE)
    c.setFont("Fraunces", 38)
    c.drawCentredString(W / 2, H - 160 * mm, "Home Kung Fu")
    c.setFont("FrauncesLight", 15)
    c.drawCentredString(W / 2, H - 172 * mm, "An 8-week plan: full-body strength, Shaolin basics and Tai Chi")
    c.setFont(G.BODY, 9)
    c.setFillColor(colors.HexColor("#AFA694"))
    c.drawCentredString(W / 2, H - 184 * mm, "BEGINNER  ·  NO EQUIPMENT  ·  5 DAYS A WEEK  ·  45 MINUTES")
    c.setFillColor(colors.HexColor("#AFA694"))
    c.setFont(G.BODY, 8.5)
    c.drawCentredString(W / 2, 16 * mm, f"Made for Lìliàng  ·  starts {doc.start:%A %d %B %Y}")
    c.restoreState()


def page(c, doc):
    c.saveState()
    c.setFillColor(G.PAGE)
    c.rect(0, 0, W, H, stroke=0, fill=1)
    G.seal(c, M + 3.2 * mm, H - 12.5 * mm, 3.2 * mm, "力", True, VERMILLION, GOLD, BONE)
    c.setFillColor(INK_DIM)
    c.setFont(G.BODY, 8)
    c.drawString(M + 9 * mm, H - 14 * mm, "Home Kung Fu  ·  8-week plan")
    G.cloud(c, W - 30 * mm, H - 13 * mm, 2.6 * mm, VERMILLION, 0.35, flip=True, width=0.7)
    c.setStrokeColor(LINE)
    c.setLineWidth(0.5)
    c.line(M, H - 18 * mm, W - M, H - 18 * mm)
    c.line(M, 14 * mm, W - M, 14 * mm)
    c.drawRightString(W - M, 9 * mm, str(doc.page))
    c.drawString(M, 9 * mm, "Stop any exercise that causes sharp pain. Effort is fine; pain is not.")
    c.restoreState()


class PlanDoc(BaseDocTemplate):
    def __init__(self, path, start):
        super().__init__(str(path), pagesize=A4, title="Home Kung Fu: 8-week plan", author="Lìliàng",
                         leftMargin=M, rightMargin=M, topMargin=24 * mm, bottomMargin=20 * mm)
        self.start = start
        self.addPageTemplates([
            PageTemplate("cover", [Frame(0, 0, W, H)], onPage=cover),
            PageTemplate("body", [Frame(M, 20 * mm, W - 2 * M, H - 44 * mm)], onPage=page),
        ])


class Heading(G.Chapter):
    """Chapter opener that falls back to YaHei for hanzi the brush subset lacks."""

    def __init__(self, hanzi, kicker, title):
        self.hanzi, self.kicker, self.title = hanzi, kicker, title
        from reportlab.platypus import Flowable
        Flowable.__init__(self)
        self.height = 28 * mm

    def draw(self):
        c = self.canv
        c.setFillColor(VERMILLION)
        hz(c, 40, 0, 6 * mm, self.hanzi)
        font = "MSZ" if all(ord(ch) in G.MSZ_CMAP for ch in self.hanzi) else FALLBACK
        x = pdfmetrics.stringWidth(self.hanzi, font, 40) + 5 * mm
        c.setFont(G.BODY_BOLD, 8.5)
        c.drawString(x, 18 * mm, self.kicker.upper())
        c.setFillColor(INK)
        c.setFont("Fraunces", 22)
        c.drawString(x, 8.5 * mm, self.title)
        G.brush(c, 0, 2 * mm, self.width, seed=len(self.title))
        c.bookmarkPage(self.title)
        c.addOutlineEntry(self.title, self.title, level=0)


def week_grid():
    head = [Paragraph(f"<b>{d}</b>", ParagraphStyle("h", parent=S["cell"], alignment=TA_CENTER)) for d, *_ in WEEK]
    glyph_style = ParagraphStyle("g", parent=S["cell"], fontSize=20, leading=24, alignment=TA_CENTER, textColor=BONE)
    lab_style = ParagraphStyle("l", parent=S["cell"], fontSize=8, leading=10, alignment=TA_CENTER, textColor=BONE)
    glyphs = [Paragraph(zh(g), glyph_style) for _, g, _, _ in WEEK]
    labels = [Paragraph(zh(l), lab_style) for _, _, l, _ in WEEK]
    t = Table([head, glyphs, labels], colWidths=[(W - 2 * M) / 7] * 7, rowHeights=[7 * mm, 12 * mm, 12 * mm])
    style = [("VALIGN", (0, 0), (-1, -1), "MIDDLE"), ("ALIGN", (0, 0), (-1, -1), "CENTER"),
             ("LINEBELOW", (0, 0), (-1, 0), 0.8, VERMILLION)]
    for i, (_, _, _, kind) in enumerate(WEEK):
        style.append(("BACKGROUND", (i, 1), (i, 2), KIND_COLOUR[kind]))
        style.append(("LINEAFTER", (i, 0), (i, 2), 2, colors.HexColor("#F7F2E8")))
    t.setStyle(TableStyle(style))
    return t


def session_table(rows, accent):
    data = [[P("<b>Exercise</b>", "cellb"), P("<b>Sets</b>", "cellb"), P("<b>Reps / time</b>", "cellb"),
             P("<b>Rest</b>", "cellb"), P("<b>Cue and reference</b>", "cellb")]]
    for name, sets, reps, rest, cue, vkey in rows:
        cue_txt = cue + (f"<br/>› {vlink(vkey)}" if vkey else "")
        data.append([P(f"<b>{name}</b>", "cell"), P(str(sets), "cell"), P(reps, "cell"),
                     P(f"{rest} s" if rest else "—", "cell"), P(cue_txt, "small")])
    t = Table(data, colWidths=[38 * mm, 11 * mm, 27 * mm, 13 * mm, 85 * mm], repeatRows=1)
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"), ("LINEBELOW", (0, 0), (-1, -1), 0.4, LINE),
        ("BACKGROUND", (0, 0), (-1, 0), CARD), ("LINEBELOW", (0, 0), (-1, 0), 0.9, accent),
        ("LINEBEFORE", (0, 1), (0, -1), 2.2, accent),
        ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]))
    return t


def checklist():
    data = [[P("<b>Week</b>", "cellb")] + [P(f"<b>{d}</b>", "cellb") for d, *_ in WEEK] + [P("<b>Notes / test</b>", "cellb")]]
    for wk in range(1, 9):
        row = [P(f"<b>{wk}</b> · {'Foundation' if wk <= 4 else 'Build'}", "small")]
        for _, _, label, kind in WEEK:
            row.append("" if kind != "rest" else P("rest", "small"))
        test = SKILL_TRACK[wk - 1][3]
        row.append(P(test, "small"))
        data.append(row)
    t = Table(data, colWidths=[24 * mm] + [11 * mm] * 7 + [73 * mm])
    box = [("BOX", (1 + i, r), (1 + i, r), 0.7, INK_DIM) for r in range(1, 9) for i, w in enumerate(WEEK) if w[3] != "rest"]
    t.setStyle(TableStyle(box + [("VALIGN", (0, 0), (-1, -1), "MIDDLE"), ("ALIGN", (1, 0), (7, -1), "CENTER"),
                           ("LINEBELOW", (0, 0), (-1, -1), 0.4, LINE), ("BACKGROUND", (0, 0), (-1, 0), CARD),
                           ("LINEBELOW", (0, 0), (-1, 0), 0.9, VERMILLION), ("BACKGROUND", (0, 5), (-1, 5), colors.HexColor("#F1E6D3")),
                           ("TOPPADDING", (0, 0), (-1, -1), 4), ("BOTTOMPADDING", (0, 0), (-1, -1), 4)]))
    return t


def build_pdf(path: Path, start: date):
    st = []
    st += [NextPageTemplate("body"), PageBreak()]

    # Overview
    st += [Heading("居家", "The plan", "How the eight weeks work"),
           P("This plan builds <b>full-body strength with no equipment</b>, while you learn the foundations of <b>Shaolin Kung Fu</b> "
             "(stances, punches, kicks and the first form, 五步拳 Wu Bu Quan) and <b>Tai Chi</b>. It is written for a beginner: "
             "5 days a week, about 45 minutes each, in a space roughly 2 × 3 metres."),
           Spacer(1, 4), week_grid(), Spacer(1, 6),
           P("Grey: strength · red: Shaolin · gold: forms · blue: rest. Move days around if you need to, but keep at least "
             "one rest day between the three strength days.", "dim"),
           G.Paragraph("The two phases", S["h2"]),
           *bullets([f"<b>Weeks 1–4 · Foundation (基础).</b> Learn every movement with easier variations. Week 4 is lighter: "
                     "2 sets per exercise, then re-test.",
                     "<b>Weeks 5–8 · Build (进阶).</b> Harder variations: full push-ups, Bulgarian split squats, longer horse stances. "
                     "Wu Bu Quan in both directions, plus more Tai Chi movements. Final test in week 8."]),
           G.Paragraph("How to progress (double progression)", S["h2"]),
           *bullets(["Each exercise has a range, e.g. 3 × 6–12. Start at the bottom of the range with clean form.",
                     "When you hit the <b>top of the range on every set</b> in two sessions running, move to the harder variation "
                     "or add 5–10 s to a hold.",
                     "Finish each set with <b>2–3 good reps left in you</b>. Never grind ugly reps.",
                     "Rest 45–90 s between sets as listed. Keep a sip of water nearby."]),
           G.Paragraph("Every session", S["h2"]),
           P("<b>Warm-up, 7 minutes</b>"), *bullets(WARMUP),
           P("<b>Cool-down, 5 minutes</b>"), *bullets(COOLDOWN),
           PageBreak()]

    # Strength
    for key, accent in zip("ABC", (IRON, IRON, IRON)):
        block = STRENGTH[key]
        st += [CondPageBreak(120 * mm),
               Heading("力", f"Mon · Wed · Fri", f"Strength {key}"),
               P(f"<b>Focus:</b> {block['focus']}. Straight sets: finish all sets of one exercise, then move on."),
               G.Paragraph("Weeks 1–4 · Foundation", S["h2"]), session_table(block["foundation"], accent),
               G.Paragraph("Weeks 5–8 · Build", S["h2"]), session_table(block["build"], accent),
               Spacer(1, 8)]
    st += [P(f"Optional conditioning on a strength day, in place of the last two exercises once a week: "
             f"{vlink('madfit', 'MadFit, 30 Min Full Body Workout for Total Beginners (32:23)')}. Standing and low impact.", "dim"),
           PageBreak()]

    # Shaolin
    st += [Heading("少林", "Tuesday", "Shaolin Basics"),
           P("Shaolin training starts from the ground up: <b>stances (步型)</b> build the legs and balance every technique "
             "stands on, then hands, then kicks. Watch the referenced video once before the session, then train without it."),
           P(f"Before your first session, watch {vlink('yl_beginners', 'Shifu Yan Lei, Shaolin Kung Fu Training for Beginners (5:48)')} "
             f"and {vlink('yl_stances', 'The 5 Fundamental Shaolin Stances (6:04)')}."),
           G.Paragraph("The five stances", S["h2"]),
           G.table([["Stance", "How it looks", "Key point"],
                    ["马步 Ma Bu · horse", "Feet ~2 shoulder-widths, toes forward, sit down", "Knees push out, back vertical"],
                    ["弓步 Gong Bu · bow", "Front knee over ankle, back leg straight", "Back heel stays down"],
                    ["虚步 Xu Bu · empty", "Weight on the back leg, front toes touch lightly", "Almost all weight behind"],
                    ["仆步 Pu Bu · drop", "One leg in a deep squat, the other straight to the side", "Both feet flat (build to it)"],
                    ["歇步 Xie Bu · crossed", "Legs crossed, sit low on the back calf", "Front foot turned out"]],
                   [42 * mm, 74 * mm, 58 * mm]),
           G.Paragraph("Weeks 1–4 · Foundation", S["h2"]), session_table(SHAOLIN["foundation"], VERMILLION),
           G.Paragraph("Weeks 5–8 · Build", S["h2"]), session_table(SHAOLIN["build"], VERMILLION),
           PageBreak()]

    # Forms
    st += [Heading("形", "Saturday", "Forms & Tai Chi"),
           P("Saturday is about <b>flow</b>. The first 20 minutes learn 五步拳 Wu Bu Quan, the Five Stance Fist. It's the "
             "classic first form of Chinese long fist, and it strings all five stances together. The second 20 minutes are Tai Chi "
             "with Dr Paul Lam's beginner course: slow, relaxed, and good for balance and recovery."),
           G.Paragraph("Weeks 1–4 · Foundation", S["h2"]), session_table(FORMS["foundation"], GOLD),
           G.Paragraph("Weeks 5–8 · Build", S["h2"]), session_table(FORMS["build"], GOLD),
           G.Paragraph("Wu Bu Quan, move by move", S["h2"]),
           P(f"See it whole first: {vlink('bao_wbq_demo', 'David Bao, full demonstration (1:17)')}. For a slow, detailed class, "
             f"use {vlink('shy_wbq1', 'Shi Heng Yi, Section 1 (54:00)')} and the {vlink('shy_wbq_full', 'complete routine (46:35)')}. "
             "This is the standard sequence; the form then turns and repeats on the other side. Your teacher's version may differ slightly.", "dim"),
           G.table([["#", "Move", "中文"]] + [[str(i + 1), en, zhn] for i, (en, zhn) in enumerate(WU_BU_QUAN_MOVES)],
                   [10 * mm, 104 * mm, 60 * mm]),
           Spacer(1, 6),
           P(f"<b>Rest days (Thu, optional):</b> {vlink('shy_bdj', 'Shi Heng Yi, Shaolin Qi Gong 20-Minute Morning Routine, Ba Duan Jin (22:36)')}. "
             "The Eight Brocades, gentle enough for a rest day. Lìliàng already has Ba Duan Jin in the Hall to tick off as you learn it."),
           PageBreak()]

    # Skill track + checklist
    st += [Heading("周", "Week by week", "Skill track and checklist"),
           G.table([["Week", "Wu Bu Quan", "Tai Chi", "Test / note"]] + [[str(w), a, b, c] for w, a, b, c in SKILL_TRACK],
                   [13 * mm, 62 * mm, 45 * mm, 54 * mm]),
           Spacer(1, 10),
           G.Paragraph("Tick it off", S["h2"]), checklist(), Spacer(1, 10),
           G.Paragraph("Tests (weeks 1, 4 and 8)", S["h2"]),
           G.table([["Test", "Week 1", "Week 4", "Week 8"],
                    ["Max push-ups (any version; note which)", "", "", ""],
                    ["Horse stance hold (马步)", "", "", ""],
                    ["Plank hold", "", "", ""],
                    ["Front stretch kick: how high? (waist / chest / head)", "", "", ""],
                    ["Wu Bu Quan: moves known", "", "", ""]],
                   [80 * mm, 31 * mm, 31 * mm, 32 * mm]),
           PageBreak()]

    # Videos
    rows = [["Topic", "Video", "Channel", "Length"]]
    order = [("Warm-up", "kfr_daily"), ("Shaolin intro", "yl_beginners"), ("Stances", "yl_stances"), ("Horse stance", "yl_horse"),
             ("Punches", "uk_punch"), ("Basics and kicks", "song_basics"), ("Kicks", "wang_kicks"), ("Wu Bu Quan", "bao_wbq1"),
             ("Wu Bu Quan", "bao_wbq_demo"), ("Wu Bu Quan", "shy_wbq1"), ("Wu Bu Quan", "shy_wbq_full"),
             ("Tai Chi", "lam_beginners"), ("Tai Chi", "lam_playlist"), ("Qigong", "shy_bdj"), ("Flexibility", "warrior_stretch"),
             ("Push-ups", "pushup_zero"), ("Push-ups", "pushup_levels"), ("Squat", "squat"), ("Conditioning", "madfit")]
    for topic, k in order:
        title, ch, length, _ = V[k]
        rows.append([topic, f'<a href="{url(k)}" color="#C73E2E"><u>{title}</u></a>', ch, length])
    st += [Heading("影", "Reference", "Video library"),
           P("Every video below is linked; tap or click a title to open it on YouTube. They were chosen from YouTube search for "
             "beginner-level teaching from established channels and teachers, including Shifu Yan Lei (31st-generation Shaolin), "
             "Shi Heng Yi (Shaolin Temple Europe), Dr Paul Lam (Tai Chi for Health) and David Bao (wushu). Watch at 0.75× "
             "speed for new movements.", "dim"),
           G.table(rows, [26 * mm, 86 * mm, 44 * mm, 18 * mm]),
           CondPageBreak(95 * mm),
           Heading("力", "In the app", "Track it in Lìliàng"),
           *bullets(["Copy the <b>ingest</b> folder (exercises.json, forms.json, workouts.json, schedule.json) to your phone, for example to Download/liliang-ingest.",
                     "In Lìliàng: <b>Settings › Data &amp; backup › Ingest folder</b>, choose that folder, then <b>Ingest now</b>.",
                     f"You get 25 new exercises, the 五步拳 Wu Bu Quan form in the Hall (tick moves off as you learn them), 11 workouts "
                     f"and the plan <b>居家功夫 Home Kung Fu · 8 weeks</b>, starting {start:%a %d %b %Y}. It becomes the active plan, "
                     "so Today shows each day's session and the streak lantern treats Thursday and Sunday as rest.",
                     "Each exercise's notes carry its cue and video link, so the reference is one tap away mid-session.",
                     "Seals you can earn along the way: 始 The First Step, 七 Seven Lanterns, 武 On the Path, 学 The Student, and "
                     "形 The Form Complete when you mark Wu Bu Quan mastered."]),
           Spacer(1, 8),
           P("<b>Safety.</b> If you have a heart, joint or back condition, check with a doctor first. Kicks need clear space "
             "(nothing within a leg's length) and a non-slip floor. Bare feet or flat shoes. Stances should feel like effort "
             "in the muscles, never pain in the knees: raise the stance until the knees are happy.", "dim")]

    doc = PlanDoc(path, start)
    doc.build(st)


def main():
    ap = argparse.ArgumentParser()
    today = date.today()
    next_monday = today + timedelta(days=(7 - today.weekday()) % 7 or 7)
    ap.add_argument("--start", default=next_monday.isoformat(), help="plan start date (a Monday), default next Monday")
    args = ap.parse_args()
    start = date.fromisoformat(args.start)
    n_ex, n_wk, n_days = build_ingest(HERE / "ingest", start)
    pdf = HERE / "Home-Kung-Fu-Plan.pdf"
    build_pdf(pdf, start)
    print(f"ingest: {n_ex} exercises, 1 form, {n_wk} workouts, plan with {n_days} day slots from {start}")
    print(f"pdf: {pdf} ({pdf.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
