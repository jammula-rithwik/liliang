#!/usr/bin/env python3
"""Build docs/Liliang-Guide.pdf — the complete Lìliàng guide.

    pip install reportlab fonttools
    python scripts/build-guide.py

Typefaces: Fraunces (headings, instanced from the app's variable subset) and
Ma Shan Zheng (hanzi) come from app/src/main/res/font. Body text uses Segoe UI
when it is installed (Windows) and falls back to Helvetica elsewhere.
Screenshots come from docs/screenshots.
"""

import math
import re
import tempfile
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont as RLFont
from reportlab.platypus import (
    BaseDocTemplate, Flowable, Frame, Image, KeepTogether, NextPageTemplate,
    PageBreak, PageTemplate, Paragraph, Spacer, Table, TableStyle,
)
from reportlab.platypus.tableofcontents import TableOfContents

ROOT = Path(__file__).resolve().parent.parent
FONTS = ROOT / "app" / "src" / "main" / "res" / "font"
SHOTS = ROOT / "docs" / "screenshots"
OUT = ROOT / "docs" / "Liliang-Guide.pdf"
VERSION = "R22"

# ── palette (from design/InkTheme.kt) ──
PAPER_DARK = colors.HexColor("#0B0A08")
PAGE = colors.HexColor("#F7F2E8")
INK = colors.HexColor("#1E1A15")
INK_DIM = colors.HexColor("#5E554A")
LINE = colors.HexColor("#D9CFBE")
VERMILLION = colors.HexColor("#C73E2E")
VERMILLION_DEEP = colors.HexColor("#992D20")
GOLD = colors.HexColor("#C9A227")
JADE = colors.HexColor("#4F8A6E")
BONE = colors.HexColor("#E9E1D2")
CARD = colors.HexColor("#EFE7D8")

# ───────────────────────── fonts ─────────────────────────

def register_fonts() -> tuple[str, str]:
    tmp = Path(tempfile.mkdtemp())
    var = TTFont(str(FONTS / "fraunces.ttf"))
    for name, wght in (("Fraunces", 560), ("FrauncesLight", 380)):
        inst = instancer.instantiateVariableFont(
            TTFont(str(FONTS / "fraunces.ttf")), {"wght": wght, "opsz": 72, "SOFT": 0, "WONK": 0})
        p = tmp / f"{name}.ttf"
        inst.save(str(p))
        pdfmetrics.registerFont(RLFont(name, str(p)))
    del var
    pdfmetrics.registerFont(RLFont("MSZ", str(FONTS / "ma_shan_zheng.ttf")))

    win = Path("C:/Windows/Fonts")
    if (win / "segoeui.ttf").exists():
        pdfmetrics.registerFont(RLFont("Body", str(win / "segoeui.ttf")))
        pdfmetrics.registerFont(RLFont("Body-Bold", str(win / "seguisb.ttf")))
        pdfmetrics.registerFont(RLFont("Body-Italic", str(win / "segoeuii.ttf")))
        pdfmetrics.registerFontFamily("Body", normal="Body", bold="Body-Bold", italic="Body-Italic", boldItalic="Body-Bold")
        return "Body", "Body-Bold"
    return "Helvetica", "Helvetica-Bold"


BODY, BODY_BOLD = register_fonts()
MSZ_CMAP = set(TTFont(str(FONTS / "ma_shan_zheng.ttf")).getBestCmap())
CJK = re.compile(r"([\u2E80-\u9FFF\uF900-\uFAFF\u3000-\u303F\uFF00-\uFFEF]+)")


def zh(text: str) -> str:
    """Wrap every run of hanzi in the brush face (Paragraph markup)."""
    def repl(m):
        run = m.group(1)
        missing = [c for c in run if ord(c) not in MSZ_CMAP]
        if missing:
            raise SystemExit(f"glyphs missing from Ma Shan Zheng subset: {''.join(missing)}")
        return f'<font name="MSZ">{run}</font>'
    return CJK.sub(repl, text)


# ───────────────────────── styles ─────────────────────────

S = {
    "body": ParagraphStyle("body", fontName=BODY, fontSize=10, leading=14.2, textColor=INK, spaceAfter=5),
    "dim": ParagraphStyle("dim", fontName=BODY, fontSize=9, leading=13, textColor=INK_DIM, spaceAfter=4),
    "bullet": ParagraphStyle("bullet", fontName=BODY, fontSize=10, leading=13.8, textColor=INK,
                             leftIndent=14, bulletIndent=2, spaceAfter=3),
    "h1": ParagraphStyle("h1", fontName="Fraunces", fontSize=26, leading=30, textColor=INK, spaceAfter=4),
    "kicker": ParagraphStyle("kicker", fontName=BODY_BOLD, fontSize=8.5, leading=11, textColor=VERMILLION,
                             spaceAfter=2),
    "h2": ParagraphStyle("h2", fontName="Fraunces", fontSize=15, leading=19, textColor=INK, spaceBefore=10,
                         spaceAfter=4),
    "cell": ParagraphStyle("cell", fontName=BODY, fontSize=9, leading=12, textColor=INK),
    "cellb": ParagraphStyle("cellb", fontName=BODY_BOLD, fontSize=9, leading=12, textColor=INK),
    "code": ParagraphStyle("code", fontName="Courier", fontSize=8.2, leading=10.6, textColor=BONE,
                           backColor=colors.HexColor("#1E1A15"), borderPadding=(7, 8, 7, 8),
                           leftIndent=8, rightIndent=8, spaceBefore=4, spaceAfter=10),
    "caption": ParagraphStyle("caption", fontName=BODY, fontSize=8, leading=10, textColor=INK_DIM,
                              alignment=TA_CENTER),
    "toc1": ParagraphStyle("toc1", fontName="Fraunces", fontSize=12, leading=20, textColor=INK, leftIndent=0),
}


def P(text, style="body"):
    return Paragraph(zh(text), S[style])


def bullets(items):
    return [Paragraph(zh(t), S["bullet"], bulletText="•") for t in items]


def code(text):
    esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>").replace(" ", "&nbsp;")
    return Paragraph(esc, S["code"])


def table(rows, widths, header=True):
    data = [[Paragraph(zh(c), S["cellb" if (header and i == 0) else "cell"]) for c in r] for i, r in enumerate(rows)]
    t = Table(data, colWidths=widths, hAlign="LEFT")
    style = [
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LINEBELOW", (0, 0), (-1, -1), 0.4, LINE),
        ("TOPPADDING", (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
    ]
    if header:
        style += [("BACKGROUND", (0, 0), (-1, 0), CARD), ("LINEBELOW", (0, 0), (-1, 0), 0.8, VERMILLION)]
    t.setStyle(TableStyle(style))
    return t


class Chapter(Flowable):
    """Chapter opener: big brush hanzi, kicker, title, and a brush stroke. Registers in the TOC."""

    def __init__(self, hanzi, kicker, title):
        super().__init__()
        zh(hanzi)  # fails the build if a glyph is missing from the subset
        self.hanzi, self.kicker, self.title = hanzi, kicker, title
        self.height = 30 * mm

    def wrap(self, aw, ah):
        self.width = aw
        return aw, self.height

    def draw(self):
        c = self.canv
        c.setFillColor(VERMILLION)
        c.setFont("MSZ", 46)
        c.drawString(0, 6 * mm, self.hanzi)
        x = pdfmetrics.stringWidth(self.hanzi, "MSZ", 46) + 5 * mm
        c.setFont(BODY_BOLD, 8.5)
        c.drawString(x, 19 * mm, self.kicker.upper())
        c.setFillColor(INK)
        c.setFont("Fraunces", 24)
        c.drawString(x, 9 * mm, self.title)
        brush(c, 0, 2 * mm, self.width, seed=len(self.title))
        key = f"ch-{self.title}"
        c.bookmarkPage(key)
        c.addOutlineEntry(self.title, key, level=0)


def brush(c, x, y, w, seed=1, color=INK):
    """A tapered dry-brush stroke, the app's BrushDivider."""
    import random
    rng = random.Random(seed)
    c.saveState()
    c.setFillColor(color)
    c.setFillAlpha(0.55)
    p = c.beginPath()
    n = 24
    top, bot = [], []
    for i in range(n + 1):
        t = i / n
        thick = (math.sin(math.pi * t) ** 0.7) * (1.1 + rng.random() * 0.5)
        top.append((x + w * t, y + thick))
        bot.append((x + w * t, y - thick * 0.6))
    p.moveTo(*top[0])
    for pt in top[1:]:
        p.lineTo(*pt)
    for pt in reversed(bot):
        p.lineTo(*pt)
    p.close()
    c.drawPath(p, fill=1, stroke=0)
    c.restoreState()


def cloud(c, cx, cy, r, color, alpha, flip=False, width=1.0):
    """The 祥云 cloud scroll from design/Motifs.kt."""
    d = -1 if flip else 1
    c.saveState()
    c.setStrokeColor(color)
    c.setStrokeAlpha(alpha)
    c.setLineWidth(width)
    c.setLineCap(1)

    def spiral(x0, y0, rr, turns, dd):
        p = c.beginPath()
        for i in range(41):
            t = i / 40
            a = 2 * math.pi * turns * (1 - t)
            rad = rr * (1 - 0.82 * t)
            px, py = x0 + dd * rad * math.cos(a), y0 + rad * math.sin(a)
            (p.moveTo if i == 0 else p.lineTo)(px, py)
        c.drawPath(p, stroke=1, fill=0)

    spiral(cx, cy, r, 1.55, d)
    spiral(cx + d * r * 1.55, cy - r * 0.35, r * 0.62, 1.25, -d)
    p = c.beginPath()
    p.moveTo(cx - d * r * 0.2, cy - r)
    p.curveTo(cx + d * r * 1.2, cy - r * 1.45, cx + d * r * 2.4, cy - r * 0.55, cx + d * r * 3.6, cy - r * 1.05)
    c.drawPath(p, stroke=1, fill=0)
    c.restoreState()


def seal(c, cx, cy, r, glyph="力", round_=True, fill=VERMILLION, ring=GOLD, glyph_color=BONE, size=None):
    c.saveState()
    c.setFillColor(fill)
    if round_:
        c.circle(cx, cy, r, stroke=0, fill=1)
        c.setStrokeColor(ring)
        c.setLineWidth(max(0.8, r * 0.05))
        c.circle(cx, cy, r * 0.86, stroke=1, fill=0)
    else:
        c.roundRect(cx - r, cy - r, 2 * r, 2 * r, r * 0.22, stroke=0, fill=1)
        c.setStrokeColor(ring)
        c.setLineWidth(max(0.6, r * 0.05))
        c.roundRect(cx - r * 0.84, cy - r * 0.84, r * 1.68, r * 1.68, r * 0.15, stroke=1, fill=0)
    fs = size or r * 1.15
    c.setFillColor(glyph_color)
    c.setFont("MSZ", fs)
    c.drawCentredString(cx, cy - fs * 0.36, glyph)
    c.restoreState()


class SealRow(Flowable):
    """The three seal tiers side by side."""

    def wrap(self, aw, ah):
        self.width = aw
        return aw, 30 * mm

    def draw(self):
        c = self.canv
        items = [
            ("始", False, VERMILLION, BONE, BONE, "Cinnabar · square"),
            ("恒", True, JADE, BONE, BONE, "Jade · round"),
            ("泰", False, GOLD, INK, INK, "Gold · double border"),
        ]
        step = self.width / 3
        for i, (g, rnd, fill, ring, gc, label) in enumerate(items):
            cx = step * i + step / 2
            seal(c, cx, 18 * mm, 9 * mm, g, rnd, fill, ring, gc)
            if g == "泰":
                c.setStrokeColor(INK)
                c.setLineWidth(0.5)
                c.roundRect(cx - 9 * mm * 0.72, 18 * mm - 9 * mm * 0.72, 9 * mm * 1.44, 9 * mm * 1.44, 2, stroke=1, fill=0)
            c.setFillColor(INK_DIM)
            c.setFont(BODY, 8.5)
            c.drawCentredString(cx, 3 * mm, label)


class Swatches(Flowable):
    SW = [("Paper", "#0B0A08"), ("Bone", "#E9E1D2"), ("Vermillion", "#C73E2E"), ("Gold", "#C9A227"),
          ("Ink wash", "#41525B"), ("Jade", "#7FA98E"), ("Iron", "#9AA7B0"), ("Azurite", "#4A7FBF")]

    def wrap(self, aw, ah):
        self.width = aw
        return aw, 22 * mm

    def draw(self):
        c = self.canv
        w = self.width / len(self.SW)
        for i, (n, hexv) in enumerate(self.SW):
            c.setFillColor(colors.HexColor(hexv))
            c.setStrokeColor(LINE)
            c.roundRect(i * w + 2, 8 * mm, w - 6, 13 * mm, 3, stroke=1, fill=1)
            c.setFillColor(INK)
            c.setFont(BODY_BOLD, 7.5)
            c.drawString(i * w + 3, 4.5 * mm, n)
            c.setFillColor(INK_DIM)
            c.setFont(BODY, 7)
            c.drawString(i * w + 3, 1.2 * mm, hexv)


def shot(name, width=48 * mm, caption=None):
    p = SHOTS / f"{name}.jpg"
    img = Image(str(p), width=width, height=width * 1200 / 540)
    cells = [[img]]
    if caption:
        cells.append([Paragraph(zh(caption), S["caption"])])
    t = Table(cells, colWidths=[width + 4])
    t.setStyle(TableStyle([("ALIGN", (0, 0), (-1, -1), "CENTER"), ("LEFTPADDING", (0, 0), (-1, -1), 0),
                           ("RIGHTPADDING", (0, 0), (-1, -1), 0), ("BOX", (0, 0), (0, 0), 0.6, LINE)]))
    return t


def shots_row(items, width=46 * mm):
    t = Table([[shot(n, width, c) for n, c in items]], hAlign="CENTER")
    t.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 6),
                           ("RIGHTPADDING", (0, 0), (-1, -1), 6)]))
    return t


def side_by_side(flowables, image, img_width=50 * mm, gap=8 * mm, total=170 * mm):
    t = Table([[flowables, image]], colWidths=[total - img_width - gap, img_width + gap])
    t.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 0),
                           ("RIGHTPADDING", (0, 0), (-1, -1), 0), ("ALIGN", (1, 0), (1, 0), "RIGHT")]))
    return t


# ───────────────────────── page furniture ─────────────────────────

W, H = A4
MARGIN = 20 * mm


def draw_cover(c, doc):
    c.saveState()
    c.setFillColor(PAPER_DARK)
    c.rect(0, 0, W, H, stroke=0, fill=1)
    # ink-wash mountains
    import random
    rng = random.Random(7)
    for layer in range(3):
        depth = layer / 2
        top = 70 * mm * (1 - depth * 0.45)
        p = c.beginPath()
        p.moveTo(0, 0)
        p.lineTo(0, top - 10 * mm)
        peaks = 4 + layer
        seg = W / peaks
        for i in range(peaks):
            x0 = seg * i
            px = x0 + seg * (0.35 + rng.random() * 0.3)
            py = top + rng.random() * 18 * mm
            p.curveTo(px - seg * 0.2, py - 6 * mm, px - seg * 0.05, py, px, py)
            ey = top - (8 + rng.random() * 12) * mm
            p.curveTo(px + seg * 0.15, py, x0 + seg * 0.8, ey, x0 + seg, ey)
        p.lineTo(W, 0)
        p.close()
        c.setFillColor(colors.HexColor("#41525B"))
        c.setFillAlpha(0.10 + 0.10 * depth)
        c.drawPath(p, fill=1, stroke=0)
    c.setFillAlpha(1)
    cloud(c, 34 * mm, H - 46 * mm, 7 * mm, GOLD, 0.35, width=1.4)
    cloud(c, W - 40 * mm, H - 118 * mm, 9 * mm, GOLD, 0.25, flip=True, width=1.4)
    cloud(c, 40 * mm, 104 * mm, 6 * mm, BONE, 0.12, width=1.2)
    # the seal
    seal(c, W / 2, H - 112 * mm, 30 * mm, "力", True, VERMILLION, GOLD, BONE, size=40 * mm)
    c.setFillColor(BONE)
    c.setFont("Fraunces", 44)
    c.drawCentredString(W / 2, H - 172 * mm, "Lìliàng")
    c.setFont("MSZ", 30)
    c.setFillColor(VERMILLION)
    c.drawCentredString(W / 2, H - 187 * mm, "力量")
    c.setFillColor(BONE)
    c.setFont(BODY, 9)
    c.drawCentredString(W / 2, H - 197 * mm, "S T R E N G T H ,   I N   I N K")
    c.setFont("FrauncesLight", 17)
    c.drawCentredString(W / 2, H - 214 * mm, "The Complete Guide")
    c.setFont(BODY, 8.5)
    c.setFillColor(colors.HexColor("#AFA694"))
    c.drawCentredString(W / 2, 16 * mm, f"Release {VERSION}  ·  Android 8.0 – 16  ·  Lifting, running and the martial arts in one log")
    c.restoreState()


def draw_page(c, doc):
    c.saveState()
    c.setFillColor(PAGE)
    c.rect(0, 0, W, H, stroke=0, fill=1)
    cloud(c, W - 30 * mm, H - 13 * mm, 2.6 * mm, VERMILLION, 0.35, flip=True, width=0.7)
    # running header
    seal(c, MARGIN + 3.2 * mm, H - 12.5 * mm, 3.2 * mm, "力", True, VERMILLION, GOLD, BONE)
    c.setFillColor(INK_DIM)
    c.setFont(BODY, 8)
    c.drawString(MARGIN + 9 * mm, H - 14 * mm, "Lìliàng  ·  The Complete Guide")
    c.setStrokeColor(LINE)
    c.setLineWidth(0.5)
    c.line(MARGIN, H - 18 * mm, W - MARGIN, H - 18 * mm)
    # footer
    c.line(MARGIN, 14 * mm, W - MARGIN, 14 * mm)
    c.setFont(BODY, 8)
    c.drawRightString(W - MARGIN, 9 * mm, str(doc.page))
    c.drawString(MARGIN, 9 * mm, f"Release {VERSION}")
    c.restoreState()


class GuideDoc(BaseDocTemplate):
    def __init__(self, path):
        super().__init__(str(path), pagesize=A4, title="Lìliàng — The Complete Guide",
                         author="Lìliàng", subject="User and developer guide for the Lìliàng Android app",
                         leftMargin=MARGIN, rightMargin=MARGIN, topMargin=24 * mm, bottomMargin=20 * mm)
        cover = Frame(0, 0, W, H, id="cover")
        body = Frame(MARGIN, 20 * mm, W - 2 * MARGIN, H - 44 * mm, id="body")
        self.addPageTemplates([
            PageTemplate("cover", [cover], onPage=draw_cover),
            PageTemplate("body", [body], onPage=draw_page),
        ])

    def afterFlowable(self, f):
        if isinstance(f, Chapter):
            self.notify("TOCEntry", (0, f.title, self.page, f"ch-{f.title}"))


# ───────────────────────── content ─────────────────────────

def build():
    story = [NextPageTemplate("body"), PageBreak()]

    toc = TableOfContents()
    toc.levelStyles = [S["toc1"]]
    toc.dotsMinLevel = 0
    story += [P("CONTENTS", "kicker"), Paragraph("Inside this guide", S["h1"]), Spacer(1, 6), toc, PageBreak()]

    # 1 — welcome
    story += [Chapter("力", "Welcome", "What Lìliàng is"),
              P("<b>Lìliàng</b> (力量, <i>strength</i>) is a workout log for people who lift and also train a martial art. "
                "Gym sessions, runs, bag rounds and form practice share one history. From that history a progression engine "
                "writes the next session for you, and every prescription comes with a reason you can read."),
              P("The app is built like a training hall. Four rooms sit on an ink dock: Today, the Calendar, the Scroll (your training year) "
                "and the Hall (your martial arts). The design is ink on black paper: bone-white type, vermillion seals and brush-calligraphy "
                "hanzi. Auspicious clouds (祥云), key-fret borders (回纹) and an ink-wash mountain horizon (山水) frame the pages."),
              P("Everything stays on your phone in an ordinary SQLite database you can export at any time. There are no accounts, "
                "no ads and no cloud sync."),
              Spacer(1, 6),
              shots_row([("splash", "Android 12+ splash: the 力 seal"), ("today", "Today, with the streak lantern lit"),
                         ("album", "The seal album 印谱")], width=44 * mm),
              PageBreak()]

    # 2 — getting started
    story += [Chapter("始", "Chapter one", "Getting started"),
              Paragraph("Requirements", S["h2"]),
              *bullets(["Android 8.0 (API 26) or newer. Lìliàng targets Android 16 (API 36) and supports edge-to-edge "
                        "layouts, predictive back, the system splash screen and themed icons.",
                        "About 15 MB of storage for the app. Your log grows slowly: years of training fit in a few megabytes."]),
              Paragraph("Installing", S["h2"]),
              *bullets(["Copy <b>Liliang-R22.apk</b> to the phone and open it. Android asks you to allow installs from that source once.",
                        "Or build it yourself; see <i>Building from source</i>.",
                        "Lìliàng installs as <b>com.liliang.app</b>, alongside any older KUAN install. To bring a KUAN history over, "
                        "export it from KUAN (Settings › Data &amp; backup › Export database), then in Lìliàng choose "
                        "Settings › Data &amp; backup › Choose file &amp; import."]),
              Paragraph("First launch", S["h2"]),
              P("After the system splash a vermillion bloom fills the screen, the 力量 seal presses down, and the page lifts to reveal Today. "
                "A fresh install contains:"),
              *bullets(["an empty training log;",
                        "the exercise and combo libraries, and the forms catalogue;",
                        "a sample week: Upper A, Bag &amp; Forms, Lower A, Easy Run, Upper B, Kicks &amp; Conditioning. "
                        "Edit or replace it under Settings › Plans."]),
              Paragraph("Finding your way", S["h2"]),
              table([["Seal", "Room", "What lives there"],
                     ["今", "Today", "The day's workout, quick logs for a run or a martial session, the streak lantern."],
                     ["历", "Calendar", "Every day you trained, painted by activity, plus planned days. Tap a day for its detail."],
                     ["卷", "Scroll", "Your training year: streak, attendance, the seal album, goals, PRs, featured lifts, rhythm."],
                     ["馆", "Hall", "The 36 Chambers, your martial arts, forms in progress, combos and the round timer."],
                     ["设", "Settings", "Top-right on every room: goals, workouts, plans, library, roles, curation, data."]],
                    [16 * mm, 24 * mm, 130 * mm]),
              P("Overlay pages (a session, a form, the album) open over the room and hide the dock. Back, or the predictive-back "
                "gesture, returns you to the room.", "dim"),
              PageBreak()]

    # 3 — today
    story += [Chapter("今", "Chapter two", "Today"),
              side_by_side([
                  P("Today is the room you open most. The carved beam at the top carries the date. Below it hangs the "
                    "<b>streak lantern</b>, and below that three wooden tablets."),
                  Paragraph("The three tablets", S["h2"]),
                  *bullets(["<b>Centre, gilded:</b> today's planned workout from your active plan, framed in gold key-fret. "
                            "It shows the plan name, the exercise count and \"coach ready\" once the engine has written the session. Tap to begin.",
                            "<b>跑 Run:</b> opens the run log for today.",
                            "<b>武 Martial:</b> opens the martial-arts log for today."]),
                  P("Under the tablets, <b>All saved</b> opens your saved workouts and <b>Blank workout</b> starts an empty session. "
                    "\"Sealed today\" lists what you have already logged."),
              ], shot("today", 50 * mm, "Today"), 50 * mm),
              Paragraph("The forgiving streak lantern", S["h2"]),
              P("The lantern counts how many training days deep your current run is. It forgives the way a sensible coach would:"),
              *bullets(["Every day you trained adds one and resets the break counter.",
                        "Weekdays your active plan marks as <b>rest</b> are neutral: they neither feed the lantern nor put it out.",
                        "Any other missed day uses up one of two allowed break days. The <b>third</b> missed day in a row puts the lantern out.",
                        "The caption tells you where you stand: \"lit · trained today\", \"still lit · 1 rest day in hand\", or "
                        "\"cold — train today to light it\"."]),
              P("The Scroll's day streak and the seal album use exactly the same number.", "dim"),
              PageBreak()]

    # 4 — sessions
    story += [Chapter("铁", "Chapter three", "Logging a gym session"),
              side_by_side([
                  P("A session opens with the coach's prescription for each exercise, last session's sets as pills, and the dials."),
                  *bullets(["<b>Weight and reps dials:</b> drag up or down to adjust, or tap to type an exact value. "
                            "Swiping down makes the number bigger.",
                            "<b>Last time:</b> the previous session's sets show as pills, so you always know what to beat.",
                            "<b>Rest timer:</b> each exercise carries a planned rest, and logging a set starts the timer.",
                            "<b>Supersets:</b> exercises sharing a letter alternate. A is vermillion, B is azurite blue, then gold, jade and mist.",
                            "<b>Per-side holds and timed sets</b> log duration instead of reps.",
                            "<b>Units:</b> choose kg or lb per exercise. Storage is always kg, so switching never corrupts history.",
                            "<b>Spoken set notes:</b> press the note seal and speak. The phone's own recogniser transcribes it. "
                            "The microphone permission is only requested on first use."]),
              ], shot("session", 50 * mm, "A session in progress"), 50 * mm),
              Paragraph("Never lose a set", S["h2"]),
              P("If you leave the app mid-session, or Android reclaims it in the background, the live session is saved as a draft. "
                "Coming back resumes the same session, and finishing completes the same record. The session and round-timer screens "
                "lock to portrait so a phone lying on the floor doesn't flip."),
              PageBreak()]

    # 5 — engine
    story += [Chapter("师", "Chapter four", "The progression engine"),
              P("The engine is a coach made of pure functions. It reads your history for each exercise, your goals and your plan's "
                "emphasis, and writes a concrete prescription (sets × reps × weight, or a run with distance and pace), "
                "always with a one-sentence rationale."),
              Paragraph("How strength is measured", S["h2"]),
              P("Each session's best set is turned into an <b>estimated one-rep max</b> with the Epley formula: "
                "<b>e1RM = weight × (1 + reps ÷ 30)</b>, with reps capped at 15. The trend of e1RM, not just the weight on the bar, "
                "decides what comes next."),
              Paragraph("What the coach can prescribe", S["h2"]),
              table([["Intent", "When it appears"],
                     ["First exposure", "You have never logged the exercise; the engine starts from the template or a safe default."],
                     ["Back from a break", "The exercise hasn't been trained for 21 days (configurable). A rebuild ramp replaces stall logic."],
                     ["Progress", "The normal case: add reps inside the range, then weight once the top of the range is reached."],
                     ["Consolidate", "Hold the load and own it, usually after a hard jump or a missed target."],
                     ["Heavy wave", "Plateau under a strength emphasis: heavier, lower-rep work to break through."],
                     ["Volume wave", "Plateau under a hypertrophy emphasis: more total work at moderate loads."],
                     ["Speed wave", "Plateau under a power emphasis: lighter loads, moved fast."],
                     ["Deload", "Too many sessions without a new e1RM: a light week at about 85% of the last working weight."],
                     ["Test day", "You are within 97% of a lift goal: a test single to claim it (compound lifts only)."]],
                    [38 * mm, 132 * mm]),
              Paragraph("Default settings", S["h2"]),
              table([["Setting", "Default"],
                     ["Plateau detection", "3 sessions without a new e1RM changes the stimulus"],
                     ["Forced deload", "after 6 sessions without an e1RM PR"],
                     ["Layoff (rebuild mode)", "21 days since the exercise was last trained"],
                     ["Hypertrophy double progression", "8–12 reps, then add weight"],
                     ["Strength scheme (compound primaries)", "5 × 5"],
                     ["Power protocol", "3 × 5–8 at 50% of e1RM, moved fast"],
                     ["Weight step", "2.5 kg (primary and secondary lifts)"],
                     ["Training emphasis", "Strength 40 · Hypertrophy 40 · Power 20 (relative)"]],
                    [70 * mm, 100 * mm]),
              P("Isolation exercises never go below 6 reps and never get test singles. Change any default under "
                "Settings › Plans › engine settings.", "dim"),
              PageBreak()]

    # 6 — runs
    story += [Chapter("跑", "Chapter five", "Runs"),
              P("The run log takes distance (drag the big number, or tap to type), time and pace. Fill any two and the third follows. "
                "Choose a kind: <b>Easy Run</b>, <b>Long Run</b>, <b>Intervals</b> or <b>Tempo Run</b>."),
              P("A coach line at the top suggests the next run from your history. With no runs logged it suggests three easy kilometres "
                "to set a baseline. If your plan sets a weekly running target, suggestions build toward it."),
              P("The Scroll tracks running separately: distance over the last 30 days, this year's runs and distance, "
                "your longest run, fastest pace, average run and your last three long runs."),
              Spacer(1, 10),
              Chapter("馆", "Chapter six", "The Hall"),
              side_by_side([
                  P("The Hall is the martial-arts room. It opens with a proverb, then the 36 Chambers, the seal album and your arts."),
                  Paragraph("Forms as data", S["h2"]),
                  *bullets(["The catalogue ships with Chen Yi Lu, Yang 37, Wing Chun, Xiao Hong Quan, Taegeuk, qigong sets and sun salutations. "
                            "Add your own, move by move, in the form editor.",
                            "<b>Move wheel:</b> when you log practice, spin to the move you reached. The next time, the picker opens there.",
                            "<b>Learned moves:</b> tick moves off as you learn them, and see percent complete on each form.",
                            "<b>Mastered:</b> mark a whole form mastered. It stops showing checklists and joins your lineage.",
                            "<b>Disciplines:</b> add any art (the Kendo case) with + Art. Long-press an art's card to remove it."]),
              ], shot("hall", 50 * mm, "The Hall"), 50 * mm),
              Paragraph("Combos and the round timer", S["h2"]),
              P("Boxing, Muay Thai and taekwondo combo libraries live under 库 (Library). The <b>round timer</b> runs bag rounds with rest "
                "periods and calls combos aloud through the phone's text-to-speech, so you can keep your hands up instead of reading."),
              PageBreak()]

    # 7 — chambers
    story += [Chapter("三十六房", "Chapter seven", "The 36 Chambers"),
              P("A three-year sample path in twelve seasons. Each season has three <b>filmable feats</b>: things you could put in front of a "
                "camera and prove, like a form from memory, a horse stance held, or rounds on the bag."),
              *bullets(["The Hall shows the current season, the week within it and every chamber's progress.",
                        "Measurable chambers read your history and show where you are against the season's clock: "
                        "<i>on track</i> or <i>behind</i>.",
                        "Open a chamber for what it asks, how to learn it, and a button to seal it as passed. Passing chambers earns "
                        "seals in the album.",
                        "The path is a sample built from the forms Lìliàng ships with. Make it yours."]),
              Spacer(1, 8),
              Chapter("印", "Chapter eight", "The seal album 印谱"),
              P("Milestones become carved seals, measured from the history the app already keeps. Open the album from its card on the Scroll or in the Hall."),
              SealRow(),
              *bullets(["<b>Earning a seal:</b> the moment the history meets a seal's mark, it stamps down full-screen: the page dims, "
                        "the seal drops and presses with a thud, and gold leaf bursts. Several seals queue one after another. "
                        "The stamp waits until you finish a gym session or round timer.",
                        "<b>Earned seals stay earned,</b> with the date, even after the rolling window that earned them moves on.",
                        "<b>Unearned seals</b> are faint carved outlines. A coloured arc traces the border as you close in, and "
                        "the album shows a percentage. Tap any seal for its story and exact progress."]),
              PageBreak()]

    seals = [["Seal", "Name", "Tier", "How to earn it"],
             ["始 初始", "The First Step", "Cinnabar", "Log your first day of training."],
             ["七 七灯", "Seven Lanterns", "Cinnabar", "Keep the lantern lit for 7 days."],
             ["月 满月", "Full Moon", "Jade", "A 30-day streak."],
             ["恒 恒心", "Constancy", "Jade", "Train on 20 of the last 30 days."],
             ["百 百日", "A Hundred Days", "Gold", "100 training days in one calendar year."],
             ["和 三合", "Three Harmonies", "Jade", "Lift, run and train a martial art within the same 7 days."],
             ["破 突破", "Breakthrough", "Cinnabar", "Set your first personal record."],
             ["雷 雷动", "Rolling Thunder", "Jade", "Five personal records in the recent feed at once."],
             ["山 移山", "Moving Mountains", "Jade", "Lift 100 kg in a single working set."],
             ["泰 泰山", "Mount Tai", "Gold", "Lift 140 kg in a single working set."],
             ["风 追风", "Chasing the Wind", "Cinnabar", "Run 10 km in one go."],
             ["龙 龙行", "Dragon's Road", "Gold", "Run a half marathon, 21.1 km, in one go."],
             ["里 千里", "A Thousand Li", "Jade", "Run 250 km in a calendar year."],
             ["武 习武", "On the Path", "Cinnabar", "10 martial-arts sessions this year."],
             ["拳 铁拳", "Iron Fist", "Jade", "50 martial-arts sessions this year."],
             ["学 好学", "The Student", "Cinnabar", "Learn 50 moves across your forms."],
             ["形 成形", "The Form Complete", "Jade", "Mark a whole form as mastered."],
             ["宗 传宗", "Keeper of the Lineage", "Gold", "Master three forms."],
             ["房 入室", "Through the Door", "Cinnabar", "Pass your first of the 36 Chambers."],
             ["季 一季", "A Season", "Jade", "Pass three chambers."],
             ["师 宗师", "Master of the 36th", "Gold", "Pass all 36 Chambers."]]
    story += [P("THE FULL CATALOGUE", "kicker"),
              Paragraph("All 21 seals", S["h2"]),
              P("Five paths: 恒 Discipline, 铁 Iron, 路 Road, 武 Martial and 房 Chambers.", "dim"),
              table(seals, [24 * mm, 40 * mm, 20 * mm, 86 * mm]),
              Spacer(1, 10),
              shots_row([("seal", "A new seal stamping down"), ("album", "The album, two seals carved"),
                         ("scroll", "The album card on the Scroll")], width=30 * mm),
              PageBreak()]

    # 8 — history, plans
    story += [Chapter("卷", "Chapter nine", "Scroll, Calendar and Goals"),
              P("The <b>Scroll</b> unrolls your training year in ink:"),
              *bullets(["the day streak and this year's training days;",
                        "attendance: the last 30 days as ticks, a 16-week sparkline, and last-year comparisons;",
                        "the seal album card: your latest seals and the next one you are closest to;",
                        "goals and cadence goals, with progress arcs;",
                        "a gold feed of recent personal records;",
                        "featured lifts as swipeable charts;",
                        "the year rhythm (\"do I always train less in winter?\") and the muscle balance grid (\"is my pressing eating my pulling?\")."]),
              P("The <b>Calendar</b> paints every trained day as an ink blot coloured by activity, shows planned days from your active plan, "
                "and opens <b>day detail</b> with everything logged that day."),
              P("<b>Goals (志)</b> come in eight kinds: lift a weight, run a time, run a distance, body weight, train N times (per week or month), "
                "practice cadence for one discipline, master a form, and inspiration (a goal that is simply written down). "
                "When you near a lift goal, the coach plans a test day."),
              Spacer(1, 6),
              Chapter("历", "Chapter ten", "Plans, workouts and the library"),
              *bullets(["<b>Plans</b> are weekday-anchored programmes, weekly or multi-week. The active plan paints the calendar, fills Today "
                        "and tells the streak which weekdays are rest.",
                        "<b>Workouts</b> are reusable templates: exercises with target sets, reps, weight, rest, duration or distance, and combos.",
                        "The <b>Library (库)</b> holds exercises, with category, muscle groups and movement type (compound or isolation), "
                        "plus combos for the round timer.",
                        "<b>Exercise roles (役)</b> mark lifts as primary, secondary or accessory. Roles decide the progression scheme and weight steps.",
                        "<b>Dashboard curation</b> chooses which lifts and forms the Scroll features."]),
              PageBreak()]

    # 9 — data
    story += [Chapter("藏", "Chapter eleven", "Your data"),
              P("Lìliàng is local-first. Everything lives in one SQLite database on the phone, and the app deliberately keeps it "
                "<b>out of Android cloud backup</b>: a silent restore of an old copy could overwrite newer training."),
              table([["Tool", "Where", "What it does"],
                     ["Export database", "Settings › Data & backup", "Saves a full copy (liliang-backup.db) wherever you choose."],
                     ["Import", "Settings › Data & backup", "Brings in a database file, for example from KUAN or another phone. A full import replaces "
                                                          "everything, after you confirm; lift history can also be merged."],
                     ["Auto-backup", "Settings › Data & backup", "Pick a folder; every change writes a fresh export there. Back up now / Clear."],
                     ["Folder ingest", "Settings › Data & backup", "Pick a folder of JSON definitions (exercises, combos, forms, workouts, schedules) "
                                                                  "and ingest them into the library."]],
                    [32 * mm, 42 * mm, 96 * mm]),
              P("Tip: point auto-backup at a folder your own sync tool already watches (Syncthing, a cloud drive app). "
                "That gives you off-phone copies without the app ever talking to a server.", "dim"),
              Spacer(1, 8),
              Chapter("墨", "Chapter twelve", "The design language"),
              P("The app is one dark theme, OLED-black by design rather than by system setting. Its palette:"),
              Swatches(),
              *bullets(["<b>Type.</b> Fraunces for headings and numerals, Ma Shan Zheng brush calligraphy for hanzi, and the system sans for reading text.",
                        "<b>Motifs.</b> Drawn as vectors so they stay crisp at any size: 祥云 cloud scrolls in corners and drifting slowly behind "
                        "Today and the Hall; 回纹 key-fret brackets on featured cards; 山水 ink-wash ridges on the horizon; ink-wear flecks on seals.",
                        "<b>Paper.</b> An AGSL grain shader gives the page texture on Android 13+, with a bitmap fallback on older phones.",
                        "<b>Motion.</b> Pages ink in with a short fade and rise, tabs drift in from the side you tapped, stamps overshoot slightly. "
                        "If you switch off animations in Android settings, all decorative motion stops and the app becomes instant."]),
              PageBreak()]

    # 10 — build
    story += [Chapter("工", "Chapter thirteen", "Building from source"),
              Paragraph("Requirements", S["h2"]),
              *bullets(["JDK 17 or 21.",
                        "The Android SDK with platform 36 and build-tools. The command-line tools are enough; Android Studio is optional.",
                        "Gradle 8.14.3, fetched by the wrapper."]),
              Paragraph("Build, test and install", S["h2"]),
              code('echo "sdk.dir=/path/to/android-sdk" > local.properties\n'
                   '#   Windows:  sdk.dir=C\\:/Users/you/AndroidTools/sdk\n\n'
                   './gradlew assembleDebug        # app/build/outputs/apk/debug/Liliang-R22.apk\n'
                   './gradlew testDebugUnitTest    # engine, parsing, plans, chambers, seals\n'
                   './gradlew lintDebug\n'
                   'adb install -r app/build/outputs/apk/debug/Liliang-R*.apk'),
              Paragraph("Run it in an emulator", S["h2"]),
              code('sdkmanager "emulator" "system-images;android-36;google_apis;x86_64"\n'
                   'avdmanager create avd -n Liliang_API36 \\\n'
                   '    -k "system-images;android-36;google_apis;x86_64" -d pixel_8\n'
                   'emulator -avd Liliang_API36\n'
                   'emulator -accel-check          # Windows: should report WHPX usable'),
              Paragraph("Project layout", S["h2"]),
              table([["Path", "Contents"],
                     ["MainActivity, LiliangApp", "Activity, splash, the shell: dock, overlay stack, transitions, seal stamp-down."],
                     ["LiliangViewModel, LiliangNav", "All app state, the tab enum and the overlay back stack."],
                     ["data/", "Domain models, the SQLite repository, formatting, parsing, martial arts, the Chambers, seals."],
                     ["engine/", "WorkoutProgression: e1RM, plateau waves, deloads, test days. Pure and unit-tested."],
                     ["platform/", "Text-to-speech, haptics, voice notes."],
                     ["design/", "Ink theme, typography, paper grain, motion, components, charts, motifs."],
                     ["ui/", "Every screen: Today, Session, Calendar, Scroll, Hall, Seal album, Settings and more."],
                     ["scripts/", "Build, test and install wrappers; font subsetting; icon generation; this guide (build-guide.py)."]],
                    [48 * mm, 122 * mm]),
              Paragraph("Regenerating assets", S["h2"]),
              *bullets(["<b>Fonts:</b> scripts/fetch-fonts.sh downloads the OFL typefaces and subsets them to the glyphs the app uses. "
                        "New hanzi in the source code are picked up automatically.",
                        "<b>Icon:</b> scripts/gen-icon.py traces 力 from Ma Shan Zheng into the adaptive icon, the themed icon and the splash seal.",
                        "<b>This guide:</b> python scripts/build-guide.py (needs reportlab and fonttools)."]),
              PageBreak()]

    # 11 — faq
    story += [Chapter("问", "Chapter fourteen", "Troubleshooting"),
              table([["Problem", "What to do"],
                     ["The lantern went out after rest days", "Mark those weekdays as rest in your active plan. Planned rest days never count against the streak."],
                     ["A seal I expected hasn't appeared", "Seals are checked after every dashboard refresh. Open the album to see the exact reading. "
                                                           "Some seals count this calendar year only."],
                     ["Voice notes fall back to typing", "Allow the microphone for Lìliàng in Android settings, and make sure the phone has a speech recogniser (Google app or similar)."],
                     ["The round timer is silent", "Check the phone's text-to-speech engine and media volume."],
                     ["The app looks different without animation", "Android's \"Remove animations\" switch turns off Lìliàng's decorative motion by design."],
                     ["Gradle wrapper times out (Windows)", "Download gradle-8.14.3-bin.zip with a browser or curl, unzip it, and run its bin/gradle.bat directly."],
                     ["Lint complains about local.properties", "Escape the drive colon: sdk.dir=C\\:/path/to/sdk."],
                     ["Emulator won't start (Windows)", "Run emulator -accel-check. WHPX must be usable; enable Windows Hypervisor Platform if it isn't."]],
                    [55 * mm, 115 * mm]),
              Spacer(1, 12),
              Chapter("谢", "Credits", "Credits and licence"),
              P("Lìliàng is built on <b>KUAN</b> by Greg Gaynor, released under the MIT License. This release brings the rebrand, "
                "the Android 16 update, the seal album and the visual refresh."),
              P("Released under the MIT License. Fraunces and Ma Shan Zheng are under the SIL Open Font License; "
                "their licence texts ship inside the app in assets/licenses."),
              Spacer(1, 20),
              Paragraph(zh("力量 · strength, in ink"), ParagraphStyle("end", parent=S["body"], alignment=TA_CENTER,
                                                                  textColor=VERMILLION, fontSize=12))]

    doc = GuideDoc(OUT)
    doc.multiBuild(story)
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    build()
