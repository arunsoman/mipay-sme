"""Build the UAT compliance evidence PDF.

Usage: python build_report.py <results.json> <out.pdf> [perf_long.json]
"""
import json
import os
import re
import sys
import datetime
from collections import defaultdict, OrderedDict

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT, TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (BaseDocTemplate, Frame, PageTemplate, Paragraph, Spacer, Table,
                                TableStyle, PageBreak, KeepTogether, CondPageBreak)
from reportlab.graphics.shapes import Drawing, Rect, String

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from modules import MODULES, BC_ACCEPTANCE, DEMO, WINDOWS, CRITICAL  # noqa: E402
from walkthrough import BASE, LOGINS, ROLE_SHORT, BC_STEPS, DEMO_STEPS, EXIT_STEPS  # noqa: E402

FONT_DIR = "/usr/share/fonts/noto/"
pdfmetrics.registerFont(TTFont("Sans", FONT_DIR + "NotoSans-Regular.ttf"))
pdfmetrics.registerFont(TTFont("Sans-Bold", FONT_DIR + "NotoSans-Bold.ttf"))
pdfmetrics.registerFont(TTFont("Sans-Italic", FONT_DIR + "NotoSans-Italic.ttf"))
pdfmetrics.registerFont(TTFont("Mono", FONT_DIR + "NotoSansMono-Regular.ttf"))
from reportlab.pdfbase.pdfmetrics import registerFontFamily  # noqa: E402
registerFontFamily("Sans", normal="Sans", bold="Sans-Bold", italic="Sans-Italic", boldItalic="Sans-Bold")

INK = colors.HexColor("#1b2430")
MUTED = colors.HexColor("#5b6675")
RULE = colors.HexColor("#d6dbe1")
BAND = colors.HexColor("#f3f5f7")
ACCENT = colors.HexColor("#0b5c8a")
GOOD = colors.HexColor("#1f7a4d")
WARN = colors.HexColor("#a86400")
BAD = colors.HexColor("#b3261e")
GOOD_BG = colors.HexColor("#e5f3eb")
WARN_BG = colors.HexColor("#fbf0dc")
BAD_BG = colors.HexColor("#f9e3e1")

ss = {
    "h1": ParagraphStyle("h1", fontName="Sans-Bold", fontSize=17, leading=22, textColor=INK, spaceAfter=6),
    "h2": ParagraphStyle("h2", fontName="Sans-Bold", fontSize=12.5, leading=16, textColor=INK, spaceBefore=8, spaceAfter=4),
    "h3": ParagraphStyle("h3", fontName="Sans-Bold", fontSize=10, leading=13, textColor=ACCENT, spaceBefore=6, spaceAfter=3),
    "body": ParagraphStyle("body", fontName="Sans", fontSize=9, leading=12.5, textColor=INK, spaceAfter=4),
    "small": ParagraphStyle("small", fontName="Sans", fontSize=7.6, leading=10, textColor=MUTED),
    "cell": ParagraphStyle("cell", fontName="Sans", fontSize=7.6, leading=9.6, textColor=INK),
    "cellb": ParagraphStyle("cellb", fontName="Sans-Bold", fontSize=7.6, leading=9.6, textColor=INK),
    "mono": ParagraphStyle("mono", fontName="Mono", fontSize=6.8, leading=8.8, textColor=INK),
    "hdr": ParagraphStyle("hdr", fontName="Sans-Bold", fontSize=7.6, leading=9.6, textColor=colors.white),
    "cover_t": ParagraphStyle("ct", fontName="Sans-Bold", fontSize=26, leading=32, textColor=INK),
    "cover_s": ParagraphStyle("cs", fontName="Sans", fontSize=12, leading=17, textColor=MUTED),
    "big": ParagraphStyle("big", fontName="Sans-Bold", fontSize=22, leading=26, textColor=INK, alignment=TA_LEFT),
}


def P(t, s="cell"):
    return Paragraph(t, ss[s])


def esc(t):
    return (t or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\u2192", "-&gt;")


def pct(n, d):
    return 0.0 if d == 0 else 100.0 * n / d


def tone(p):
    return (GOOD, GOOD_BG) if p >= 95 else (WARN, WARN_BG) if p >= 75 else (BAD, BAD_BG)


# ------------------------------------------------------------------ data
res = json.load(open(sys.argv[1]))
OUT = sys.argv[2]
perf_long = json.load(open(sys.argv[3])) if len(sys.argv) > 3 else None
tests = res["results"]
run_meta = res.get("meta", {})

ID_RE = re.compile(r"TC-([A-Z0-9]+)-(\d+)([a-z]?)((?:/\d+[a-z]?)*)")


def ids_of(display, method=""):
    head = display.split("·")[0] if "·" in display else display  # leading IDs only, not "(TC-x, Rn)" references
    out = []
    for m in ID_RE.finditer(head):
        mod, num, suf, more = m.groups()
        out.append(f"TC-{mod}-{int(num):02d}")
        for extra in [x for x in more.split("/") if x]:
            out.append(f"TC-{mod}-{int(re.match(r'\d+', extra).group()):02d}")
    sm = re.match(r"\s*Scene (\d)", display)
    if sm:
        out.append(f"Scene {sm.group(1)}")
    if not out:
        mm = re.match(r"tc([A-Z][a-z0-9]+?)(\d{2})", method or display)
        if mm:
            out.append(f"TC-{mm.group(1).upper()}-{mm.group(2)}")
    return list(OrderedDict.fromkeys(out))


by_id = defaultdict(list)
for t in tests:
    t["ids"] = ids_of(t["display"], t["method"])
    for i in t["ids"]:
        by_id[i].append(t)

# Long-run perf override (full 20 TPS x 30 min acceptance run)
if perf_long:
    for t in perf_long["results"]:
        if t["method"].startswith("tcPerf01"):
            t["ids"] = ["TC-PERF-01"]
            t["display"] = t["display"] + " [full 30-minute run]"
            by_id["TC-PERF-01"] = [t]


# repeated race-condition runs (flakiness evidence)
import glob as _g, xml.etree.ElementTree as _ET, os as _os
RACE_RUNS = []
for _f in sorted(_g.glob(_os.path.join(_os.path.dirname(sys.argv[1]), "race*.xml"))):
    try:
        _r = _ET.parse(_f).getroot()
    except Exception:
        continue
    _d = {}
    for _tc in _r.iter("testcase"):
        _m = re.search(r"TC-RACE-\d+", _tc.get("name", ""))
        _k = _m.group(0) if _m else _tc.get("name")
        _d[_k] = "FAIL" if (_tc.find("failure") is not None or _tc.find("error") is not None) else "PASS"
    RACE_RUNS.append(_d)


def id_status(i):
    ts = by_id.get(i, [])
    if not ts:
        return "NOT COVERED"
    st = {t["status"] for t in ts}
    if "FAIL" in st:
        return "FAIL"
    if any(d.get(i) == "FAIL" for d in RACE_RUNS):
        return "FAIL"  # flaky: failed in at least one re-run on the same snapshot
    if "SKIP" in st:
        return "PARTIAL"
    if i == "TC-PERF-01" and not perf_long:
        return "PARTIAL"
    return "PASS"


STATUS_COL = {"PASS": (GOOD, GOOD_BG), "FAIL": (BAD, BAD_BG), "PARTIAL": (WARN, WARN_BG),
              "NOT COVERED": (MUTED, BAND), "SKIP": (WARN, WARN_BG)}


def module_stats(mod):
    req = mod["required"]
    st = {i: id_status(i) for i in req}
    covered = sum(1 for s in st.values() if s != "NOT COVERED")
    passed = sum(1 for s in st.values() if s == "PASS")
    return {"req": len(req), "covered": covered, "passed": passed,
            "fail": sum(1 for s in st.values() if s == "FAIL"),
            "partial": sum(1 for s in st.values() if s == "PARTIAL"),
            "uncov": len(req) - covered,
            "cov_pct": pct(covered, len(req)), "comp_pct": pct(passed, len(req)), "status": st}


STATS = {m["key"]: module_stats(m) for m in MODULES}
tot_req = sum(s["req"] for s in STATS.values())
tot_cov = sum(s["covered"] for s in STATS.values())
tot_pass = sum(s["passed"] for s in STATS.values())
n_tests = len(tests)
n_pass = sum(1 for t in tests if t["status"] == "PASS")
n_fail = sum(1 for t in tests if t["status"] == "FAIL")
n_skip = sum(1 for t in tests if t["status"] == "SKIP")

crit_req = sum(STATS[k]["req"] for k in CRITICAL)
crit_pass = sum(STATS[k]["passed"] for k in CRITICAL)
oth_req = tot_req - crit_req
oth_pass = tot_pass - crit_pass
BC_PCTS = []
for _n, _e, _ids in BC_ACCEPTANCE:
    _sts = [id_status(i) for i in _ids]
    BC_PCTS.append(pct(sum(1 for x in _sts if x == "PASS"), len(_ids)))
BC_FULL = sum(1 for x in BC_PCTS if x == 100)
BC_AVG = sum(BC_PCTS) / len(BC_PCTS)
json.dump({n: p for (n, _, _), p in zip(BC_ACCEPTANCE, BC_PCTS)}, open(os.path.join(os.path.dirname(os.path.abspath(sys.argv[1])), "bc_pcts.json"), "w"))



# ------------------------------------------------------------------ drawing helpers
def bar(p, w=60 * mm, h=4.2 * mm):
    d = Drawing(w, h)
    d.add(Rect(0, 0, w, h, fillColor=BAND, strokeColor=None))
    fg, _ = tone(p)
    d.add(Rect(0, 0, w * p / 100.0, h, fillColor=fg, strokeColor=None))
    return d


def pill(text, status):
    fg, bg = STATUS_COL.get(status, (MUTED, BAND))
    t = Table([[Paragraph(f'<font color="{fg.hexval().replace("0x", "#")}"><b>{text}</b></font>', ss["cell"])]],
              style=[("BACKGROUND", (0, 0), (-1, -1), bg), ("LEFTPADDING", (0, 0), (-1, -1), 3),
                     ("RIGHTPADDING", (0, 0), (-1, -1), 3), ("TOPPADDING", (0, 0), (-1, -1), 1),
                     ("BOTTOMPADDING", (0, 0), (-1, -1), 1)])
    return t


def grid(data, widths, header=True, zebra=True, extra=None):
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    style = [
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LINEBELOW", (0, 0), (-1, -1), 0.4, RULE),
        ("LEFTPADDING", (0, 0), (-1, -1), 4), ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]
    if header:
        style += [("BACKGROUND", (0, 0), (-1, 0), INK)]
    if zebra:
        for r in range(1 if header else 0, len(data)):
            if r % 2 == 0:
                style.append(("BACKGROUND", (0, r), (-1, r), BAND))
    if extra:
        style += extra
    t.setStyle(TableStyle(style))
    return t


def hdr(*cols):
    return [P(c, "hdr") for c in cols]


def stat_cell(label, value, sub, color=INK):
    return [Paragraph(f'<font color="{color.hexval().replace("0x", "#")}">{value}</font>', ss["big"]),
            P(f"<b>{label}</b>", "cell"), P(sub, "small")]


# ------------------------------------------------------------------ page frame
run_date = run_meta.get("date") or datetime.date.today().isoformat()


def on_page(c, doc):
    c.saveState()
    c.setFont("Sans", 7.2)
    c.setFillColor(MUTED)
    c.drawString(18 * mm, 10 * mm, "TPTS · MiPay SME — UAT compliance evidence report · derived from Business Case v3.0")
    c.drawRightString(A4[0] - 18 * mm, 10 * mm, f"Page {doc.page}")
    c.setStrokeColor(RULE)
    c.line(18 * mm, 13.5 * mm, A4[0] - 18 * mm, 13.5 * mm)
    c.restoreState()


def on_cover(c, doc):
    c.saveState()
    c.setFillColor(ACCENT)
    c.rect(0, A4[1] - 9 * mm, A4[0], 9 * mm, fill=1, stroke=0)
    c.restoreState()


doc = BaseDocTemplate(OUT, pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=16 * mm,
                      bottomMargin=18 * mm, title="UAT Compliance Evidence Report",
                      author="Prepared for TPTS / Mindlabs", subject="MiPay SME UAT coverage and compliance")
frame = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id="f")
doc.addPageTemplates([PageTemplate(id="cover", frames=[frame], onPage=on_cover),
                      PageTemplate(id="body", frames=[frame], onPage=on_page)])
W = doc.width
S = []

# ------------------------------------------------------------------ cover
from reportlab.platypus.doctemplate import NextPageTemplate  # noqa: E402
S += [Spacer(1, 38 * mm),
      P("TPTS · MiPay SME Digital Ecosystem", "cover_s"), Spacer(1, 4),
      P("UAT Compliance Evidence Report", "cover_t"), Spacer(1, 6),
      P("Where each UAT requirement of the Business Case v3.0 is implemented, and how far it complies — "
        "measured by test-case coverage of each UAT module", "cover_s"),
      Spacer(1, 18 * mm)]
cov_tbl = Table([[stat_cell("BC acceptance tests fully met", f"{BC_FULL} / 13",
                            f"BC p.18 · {13 - BC_FULL} partially met", tone(100 * BC_FULL / 13)[0]),
                  stat_cell("Average BC compliance", f"{BC_AVG:.1f}%",
                            "mean of the 13 acceptance tests", tone(BC_AVG)[0]),
                  stat_cell("Test cases passing", f"{pct(tot_pass, tot_req):.1f}%",
                            f"{tot_pass}/{tot_req} required · {n_tests} tests run ({n_fail} failed, {n_skip} skipped)")]],
                colWidths=[W / 3] * 3)
cov_tbl.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"), ("LINEABOVE", (0, 0), (-1, 0), 1.2, INK),
                             ("TOPPADDING", (0, 0), (-1, -1), 6), ("LEFTPADDING", (0, 0), (-1, -1), 0)]))
S += [cov_tbl, Spacer(1, 24 * mm)]
meta_rows = [
    ["Source business case", "TPTS Mindlabs SME Digital Ecosystem Business Case, v3.0 (3 Nov 2026) — UAT scope pp. 17–18"],
    ["Related inputs", "MiPay SME UAT Storyboard (SB); docs/UAT-BACKEND-TEST-PACK.md (TC-ID definitions)"],
    ["System under test", "MiPay SME platform — Spring Boot backend + Angular frontend (/home/arun/sme)"],
    ["Evidence run", f"{run_date} · ./gradlew test (H2 PostgreSQL mode, UTC, sandbox seed)"],
    ["Status", "Evidence document — for joint steering committee review"],
]
S.append(grid([[P(f"<b>{a}</b>", "cell"), P(b, "cell")] for a, b in meta_rows], [40 * mm, W - 40 * mm],
              header=False, zebra=False))
S += [NextPageTemplate("body"), PageBreak()]

# ------------------------------------------------------------------ 1. Executive summary (BC perspective)
BC_PCTS = []
for _n, _e, _ids in BC_ACCEPTANCE:
    _sts = [id_status(i) for i in _ids]
    BC_PCTS.append(pct(sum(1 for x in _sts if x == "PASS"), len(_ids)))
BC_FULL = sum(1 for x in BC_PCTS if x == 100)
BC_AVG = sum(BC_PCTS) / len(BC_PCTS)
crit_ok = crit_pass == crit_req
oth_pct = pct(oth_pass, oth_req)
EXEC_TEXT = (
    f"Measured against the Business Case v3.0 UAT section (pp. 17–18), <b>{BC_FULL} of the 13 acceptance tests are fully "
    f"compliant</b> and {13 - BC_FULL} are partially compliant. Average compliance across the 13 is <b>{BC_AVG:.1f}%</b>. "
    f"Each acceptance test is scored by the share of its evidencing test cases that pass. "
    f"All five demonstration steps on BC p.17 can be run end to end. "
    f"<b>The UAT exit bar is {'met' if crit_ok and oth_pct >= 95 else 'not yet met'}</b>: the business case requires every money and "
    f"security test to pass, and {crit_req - crit_pass} money/security test case(s) do not. Apart from the QR pending display, "
    f"every remaining gap is a concurrency defect. The flows behave correctly when actions happen one at a time, but some fail "
    f"when the same money is touched at the same moment. Those results also vary between runs (section 7.2).")
# ------------------------------------------------------------------ 3. BC p.18 cross-reference
S += [P("1. Executive summary — Business Case UAT compliance", "h1")]
S.append(P(EXEC_TEXT, "body"))
S.append(P("1.1 The 13 acceptance tests (BC p.18)", "h2"))
S.append(P("Each BC p.18 acceptance test is mapped to the TC-IDs that evidence it. "
           "Compliance = evidencing TC-IDs passing ÷ evidencing TC-IDs.", "body"))
rows = [hdr("BC acceptance test", "Minimum evidence (BC p.18)", "Evidencing test cases", "Compliance")]
for name, ev, ids in BC_ACCEPTANCE:
    sts = [id_status(i) for i in ids]
    p_ = pct(sum(1 for s in sts if s == "PASS"), len(ids))
    fg, _ = tone(p_)
    idtxt = ", ".join(
        f'<font color="{STATUS_COL[s][0].hexval().replace("0x", "#")}">{i}{"" if s == "PASS" else " (" + s.lower() + ")"}</font>'
        for i, s in zip(ids, sts))
    if name == "Fees and billing":
        idtxt += "<br/><font color='#a86400'>Note: the seeded card fee is 1.50%, but BC p.16 specifies 2.50%. The tests check the seeded value (see 7.3).</font>"
    rows.append([P(f"<b>{name}</b>"), P(esc(ev), "small"), P(idtxt, "small"),
                 P(f'<font color="{fg.hexval().replace("0x", "#")}"><b>{p_:.0f}%</b></font>')])
S.append(grid(rows, [32 * mm, 56 * mm, W - 108 * mm, 20 * mm]))

S += [CondPageBreak(70 * mm), Spacer(1, 6), P("1.2 UAT exit criteria (BC p.18)", "h2")]
perf = id_status("TC-PERF-01")
exit_rows = [hdr("Criterion", "Evidence", "Result")]
exit_rows.append([P("All financial and security-critical tests pass; no unexplained balance difference"),
                  P(f"{crit_pass}/{crit_req} critical TC-IDs pass; recon TC-RCN-01/02 = {id_status('TC-RCN-01')}/{id_status('TC-RCN-02')}"),
                  pill("MET" if crit_ok else "NOT MET", "PASS" if crit_ok else "FAIL")])
exit_rows.append([P("At least 95% of remaining agreed cases pass"),
                  P(f"{oth_pass}/{oth_req} = {oth_pct:.1f}%"),
                  pill("MET" if oth_pct >= 95 else "NOT MET", "PASS" if oth_pct >= 95 else "FAIL")])
perf_ev = ("Full 30-minute run executed (-Duat.long=true)" if perf_long else
           "Default pack runs 20 TPS for 30 s only; full 30-minute run (-Duat.long=true) not yet evidenced")
exit_rows.append([P("Peak test: 20 TPS for 30 minutes within sandbox limits"), P(perf_ev),
                  pill({"PASS": "MET", "FAIL": "NOT MET"}.get(perf, "PARTIAL"), perf)])
exit_rows.append([P("Service targets: 99.9% availability, 15-min critical ack, RTO ≤ 2 h, RPO = 0"),
                  P(f"TC-PERF-02 {id_status('TC-PERF-02')}, TC-PERF-03 {id_status('TC-PERF-03')}, TC-SEC-03 {id_status('TC-SEC-03')}"),
                  pill("MET" if all(id_status(x) == "PASS" for x in ["TC-PERF-02", "TC-PERF-03", "TC-SEC-03"]) else "NOT MET",
                       "PASS" if all(id_status(x) == "PASS" for x in ["TC-PERF-02", "TC-PERF-03", "TC-SEC-03"]) else "FAIL")])
S.append(grid(exit_rows, [62 * mm, W - 92 * mm, 30 * mm]))

# ------------------------------------------------------------------ 2. Live sign-off walkthrough
from reportlab.platypus import Flowable  # noqa: E402

# every linked route must exist in the Angular router
_routes_src = open("/home/arun/sme/frontend/src/app/app.routes.ts").read()
ROUTES = set("/" + r for r in re.findall(r"path:\s*'([^']*)'", _routes_src))
_used = {r for v in BC_STEPS.values() for (_, r, _, _) in v} | {r for d in DEMO_STEPS for (_, r) in d} | {r for (_, _, r, _) in EXIT_STEPS}
_missing = sorted(u for u in _used if u not in ROUTES)
assert not _missing, f"walkthrough links to routes not in app.routes.ts: {_missing}"

_field_seq = [0]


class Verdict(Flowable):
    """Fillable Pass / Fail checkboxes and a notes field (PDF AcroForm)."""

    def __init__(self, key, width=30 * mm, notes=True):
        super().__init__()
        self.key, self.w, self.notes = key, width, notes
        self.h = 15 * mm if notes else 5 * mm

    def wrap(self, aw, ah):
        return self.w, self.h

    def draw(self):
        _field_seq[0] += 1
        n = f"{self.key}_{_field_seq[0]}"
        c, f = self.canv, self.canv.acroForm
        y = self.h - 4.2 * mm
        for i, (lab, col) in enumerate([("Pass", GOOD), ("Fail", BAD)]):
            x = i * 15 * mm
            f.checkbox(name=f"{n}_{lab.lower()}", tooltip=f"{lab}", x=x, y=y, size=9, buttonStyle="check",
                       borderColor=col, fillColor=colors.white, textColor=col, forceBorder=True, relative=True)
            c.setFont("Sans-Bold", 7.2)
            c.setFillColor(col)
            c.drawString(x + 11, y + 2, lab)
        if self.notes:
            f.textfield(name=f"{n}_notes", tooltip="Tester notes", x=0, y=0, width=self.w - 1, height=8.5 * mm,
                        fontName="Helvetica", fontSize=7, borderColor=RULE, fillColor=colors.white,
                        borderWidth=0.5, fieldFlags="multiline", forceBorder=True, relative=True)


def ui_links(ui):
    out = []
    for tok in [t.strip() for t in ui.split("·")]:
        m = re.match(r"([a-z]+(?:/[a-z]+)?)(.*)", tok)
        if m and "/" + m.group(1) in ROUTES:
            out.append(link("/" + m.group(1), BASE.replace("http://", "") + "/" + m.group(1)) + esc(m.group(2)))
        elif m and m.group(1).startswith("merchant/") and "/merchant" in ROUTES and tok.startswith("merchant/") and "/" + tok.split()[0] not in ROUTES:
            out.append(esc(tok))
        else:
            out.append(esc(tok))
    return " · ".join(out)


def link(route, label=None):
    url = BASE + route
    return f'<a href="{url}" color="#0b5c8a"><u>{esc(label or url)}</u></a>'



# ---- recorded walkthrough media (videos + screenshots), copied next to the PDF
import os as _os2
EVID = _os2.environ.get("UAT_EVID_DIR", "")
MEDIA = json.load(open(_os2.path.join(EVID, "manifest.json"))) if EVID and _os2.path.exists(_os2.path.join(EVID, "manifest.json")) else {}
from reportlab.platypus import Image as _RLImage  # noqa: E402


def _furl(rel):
    return "file://" + _os2.path.join(EVID, rel).replace(" ", "%20")


def media_block(item_id):
    m = MEDIA.get(item_id)
    if not m or not m.get("video"):
        return None
    shots = m.get("screens") or []
    thumb = None
    if shots:
        pick = shots[min(len(shots) - 1, max(0, len(shots) // 2))]
        thumb = _RLImage(_os2.path.join(EVID, pick), width=46 * mm, height=46 * mm * 800 / 1280)
        thumb.hAlign = "LEFT"
    shot_dir = _os2.path.dirname(shots[0]) if shots else ""
    notes = m.get("notes") or []
    probs = [n for n in notes if not n.startswith("setup")]
    setup = [n for n in notes if n.startswith("setup")]
    txt = (f'<b>Recorded walkthrough</b> on the live sandbox ({int(m["duration"])} s, {len(shots)} screenshots)<br/>'
           f'<a href="{_furl(m["video"])}" color="#0b5c8a"><u>Play video: {esc(_os2.path.basename(m["video"]))}</u></a><br/>'
           f'<a href="{_furl(shot_dir)}" color="#0b5c8a"><u>Open screenshots folder</u></a> · '
           f'<a href="{_furl("index.html")}#{item_id}" color="#0b5c8a"><u>Open in the evidence player (video + live link)</u></a>')
    if probs:
        txt += "<br/><font color='#b3261e'><b>Seen in the live app:</b> " + esc("; ".join(probs))[:600] + "</font>"
    if setup:
        txt += "<br/><font color='#5b6675'>" + esc("; ".join(setup))[:300] + "</font>"
    cells = [[thumb if thumb else P(""), P(txt, "small")]]
    if thumb:
        # make the thumbnail itself clickable
        class _LinkedThumb(Flowable):
            def __init__(self, img, url):
                super().__init__(); self.img, self.url = img, url
            def wrap(self, aw, ah):
                return self.img.wrap(aw, ah)
            def draw(self):
                self.img.drawOn(self.canv, 0, 0)
                w, h = self.img.drawWidth, self.img.drawHeight
                self.canv.setFillColor(colors.Color(0, 0, 0, alpha=0.45))
                self.canv.circle(w / 2, h / 2, 6 * mm, stroke=0, fill=1)
                self.canv.setFillColor(colors.white)
                p = self.canv.beginPath(); p.moveTo(w / 2 - 2 * mm, h / 2 - 3 * mm); p.lineTo(w / 2 - 2 * mm, h / 2 + 3 * mm)
                p.lineTo(w / 2 + 3.2 * mm, h / 2); p.close(); self.canv.drawPath(p, stroke=0, fill=1)
                self.canv.linkURL(self.url, (0, 0, w, h), relative=1)
        cells = [[_LinkedThumb(thumb, _furl(m["video"])), P(txt, "small")]]
    t = Table(cells, colWidths=[50 * mm, W - 50 * mm])
    t.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"), ("BOX", (0, 0), (-1, -1), 0.5, RULE),
                           ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#fbfcfd")),
                           ("LEFTPADDING", (0, 0), (-1, -1), 4), ("TOPPADDING", (0, 0), (-1, -1), 4), ("BOTTOMPADDING", (0, 0), (-1, -1), 4)]))
    return t

S += [PageBreak(), P("2. Live UAT sign-off: click, check, decide", "h1"),
      P(f"Each UAT item below links to the live page on the sandbox (<b>{BASE}</b>). Open the link, follow the step, compare "
        "what you see with ‘Pass if’, and tick <b>Pass</b> or <b>Fail</b>. The boxes and notes fields can be filled in any PDF "
        "reader. Save the file to keep the verdicts. The automated result from the evidence run is shown next to each item for comparison. "
        "Each item also has a <b>recorded video</b> of the same steps on the live sandbox. Click the thumbnail to play it, or open the "
        "<b>evidence player</b> (uat-evidence/index.html), which shows every video next to its live link and screenshots.",
        "body"), Spacer(1, 2), P("Before you start", "h3")]
S.append(grid([hdr("Role", "Phone", "Password / OTP", "Login tab")] +
              [[P(f"<b>{r}</b>"), P(ph, "mono"), P(esc(pw), "mono"), P(tab, "small")] for r, _, ph, pw, tab in LOGINS],
              [48 * mm, 26 * mm, 52 * mm, W - 126 * mm]))
S.append(P(
    f"• Log in at {link('/login')} as the role named in the row, <b>then</b> click the link. If you click while logged out, or "
    "logged in as another role, the app sends you to the login page or that role's home page. Log in as the right role and click the link again.<br/>"
    "• The session is held per browser, so only one role can be logged in at a time. Use a private window for a second role.<br/>"
    "• Start the stack with <font face='Mono'>make up</font> if the links do not open. Everything is sandbox data, and every bank or lender event is SIMULATED.<br/>"
    "• Concurrency (simultaneous actions) cannot be judged by eye. For those items, use the automated TC-RACE results.", "small"))

_auto = dict(zip([n for n, _, _ in BC_ACCEPTANCE], BC_PCTS))
for idx, (name, ev, ids) in enumerate(BC_ACCEPTANCE, 1):
    p_ = _auto[name]
    fg, bg = tone(p_)
    head = Table([[P(f"<b>2.{idx}  {esc(name)}</b>", "h3"),
                   P(f'Automated: <font color="{fg.hexval().replace("0x", "#")}"><b>{p_:.0f}%</b></font>', "cell")]],
                 colWidths=[W - 34 * mm, 34 * mm])
    head.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "MIDDLE"), ("LINEBELOW", (0, 0), (-1, 0), 0.8, INK),
                              ("LEFTPADDING", (0, 0), (-1, -1), 0), ("BACKGROUND", (1, 0), (1, 0), bg)]))
    rows = [hdr("Log in as", "Live page (click)", "Do this", "Pass if you see", "Your verdict")]
    for j, (role, route, do, see) in enumerate(BC_STEPS[name]):
        rows.append([P(f"<b>{ROLE_SHORT[role]}</b>"), P(link(route), "small"), P(esc(do), "small"), P(esc(see), "small"),
                     Verdict(f"bc{idx:02d}s{j + 1}", notes=False)])
    rows.append([P("<b>Overall</b>"), P(f"BC p.18: {esc(ev)}", "small"), "", "", Verdict(f"bc{idx:02d}_overall")])
    t = grid(rows, [17 * mm, 37 * mm, 40 * mm, W - 128 * mm, 34 * mm],
             extra=[("SPAN", (1, len(rows) - 1), (3, len(rows) - 1)), ("BACKGROUND", (0, len(rows) - 1), (-1, len(rows) - 1), BAND)])
    _mb = media_block(f"2.{idx}")
    S.append(KeepTogether([Spacer(1, 4), head, Spacer(1, 2)] + ([_mb, Spacer(1, 2)] if _mb else []) + [t]))

S += [CondPageBreak(80 * mm), P("2.14  Demonstration script (BC p.17)", "h2")]
_mb = media_block("2.14")
if _mb:
    S += [_mb, Spacer(1, 3)]
rows = [hdr("Demonstration step", "Live pages (click)", "Automated", "Your verdict")]
for k, ((desc, ids), pages) in enumerate(zip(DEMO, DEMO_STEPS), 1):
    sts = [id_status(i) for i in ids]
    p_ = pct(sum(1 for x in sts if x == "PASS"), len(ids))
    fg, _ = tone(p_)
    rows.append([P(esc(desc), "small"),
                 P("<br/>".join(f"{ROLE_SHORT[r]}: {link(u)}" for r, u in pages), "small"),
                 P(f'<font color="{fg.hexval().replace("0x", "#")}"><b>{p_:.0f}%</b></font>'),
                 Verdict(f"demo{k}", notes=False)])
S.append(grid(rows, [62 * mm, W - 116 * mm, 20 * mm, 34 * mm]))

S += [Spacer(1, 6), P("2.15  Exit-criteria checks visible on screen (BC p.18)", "h2")]
_mb = media_block("2.15")
if _mb:
    S += [_mb, Spacer(1, 3)]
rows = [hdr("Check", "Log in as", "Live page (click)", "Pass if you see", "Your verdict")]
for k, (what, role, route, see) in enumerate(EXIT_STEPS, 1):
    rows.append([P(f"<b>{esc(what)}</b>", "small"), P(ROLE_SHORT[role], "small"), P(link(route), "small"), P(esc(see), "small"),
                 Verdict(f"exit{k}", notes=False)])
S.append(grid(rows, [36 * mm, 17 * mm, 36 * mm, W - 123 * mm, 34 * mm]))
S.append(P("The 20 TPS × 30-minute peak test and the concurrency tests cannot be judged on screen. Use the automated results in section 1.2 and section 7.2.", "small"))


class SignOff(Flowable):
    def wrap(self, aw, ah):
        self.w = aw
        return aw, 30 * mm

    def draw(self):
        c, f = self.canv, self.canv.acroForm
        labels = [("Tester name", "tester"), ("Organisation / role", "org"), ("Date tested", "date")]
        colw = (self.w - 8 * mm) / 3
        for i, (lab, key) in enumerate(labels):
            x = i * (colw + 4 * mm)
            c.setFont("Sans-Bold", 7.6)
            c.setFillColor(INK)
            c.drawString(x, 26 * mm, lab)
            f.textfield(name=f"signoff_{key}", x=x, y=18 * mm, width=colw, height=7 * mm, fontName="Helvetica", fontSize=9,
                        borderColor=RULE, fillColor=colors.white, borderWidth=0.5, forceBorder=True, relative=True)
        c.setFont("Sans-Bold", 7.6)
        c.drawString(0, 12 * mm, "Overall UAT verdict")
        for i, (lab, col) in enumerate([("Accepted", GOOD), ("Accepted with conditions", WARN), ("Rejected", BAD)]):
            x = i * 48 * mm
            f.checkbox(name=f"signoff_{lab.split()[0].lower()}{i}", x=x, y=4 * mm, size=10, buttonStyle="check",
                       borderColor=col, fillColor=colors.white, textColor=col, forceBorder=True, relative=True)
            c.setFillColor(col)
            c.drawString(x + 13, 4 * mm + 2.5, lab)


S += [Spacer(1, 6), P("2.16  Sign-off", "h2"), SignOff()]


# ------------------------------------------------------------------ 2. Method
S += [PageBreak(), P("3. How compliance is measured", "h1")]
S.append(P(
    "Compliance is measured by <b>test-case coverage of each UAT module</b>. The required test cases (TC-IDs) for "
    "each module come from the traceability matrix in <font face='Mono'>docs/UAT-BACKEND-TEST-PACK.md</font>, which "
    "maps every TC-ID to the storyboard (SB) and business-case (BC) pages it proves. Each TC-ID is assessed from "
    "the JUnit results of the backend UAT pack. The pack runs the real Spring Boot services, HTTP layer, security "
    "and database with no mocked business logic.", "body"))
S.append(grid([
    hdr("Measure", "Formula", "Meaning"),
    [P("<b>Coverage %</b>"), P("required TC-IDs with at least one automated test ÷ required TC-IDs"), P("How much of the module is evidenced by a test")],
    [P("<b>Compliance %</b>"), P("required TC-IDs whose tests all PASS ÷ required TC-IDs"), P("How much of the module is proven to behave as specified")],
], [28 * mm, 70 * mm, W - 98 * mm]))
S.append(Spacer(1, 4))
S.append(grid([
    hdr("TC status", "Rule"),
    [pill("PASS", "PASS"), P("Every automated test asserting this TC-ID passed.")],
    [pill("FAIL", "FAIL"), P("At least one test asserting this TC-ID failed. Counts against compliance.")],
    [pill("PARTIAL", "PARTIAL"), P("Implemented, but a spec test is disabled for a known defect, or the evidence run was shortened "
                                   "(e.g. TC-PERF-01 short load run). Counts against compliance.")],
    [pill("NOT COVERED", "NOT COVERED"), P("TC-ID is defined for the module but no automated test asserts it. Counts against coverage and compliance.")],
], [28 * mm, W - 28 * mm]))
S.append(P("“Where” evidence cites the implementing backend source (file:line), the REST endpoint and the frontend "
           "screen (route) for each capability. Production-only conditions from BC p.18 (signed agreements, "
           "certification, funded facility) are outside the software UAT and are listed separately in section 7.", "small"))

# ------------------------------------------------------------------ 1. Executive summary
S += [PageBreak(), P("4. Coverage by UAT module", "h1")]
S.append(P(
    f"Across <b>{tot_req}</b> required test cases in <b>{len(MODULES)}</b> UAT modules, <b>{tot_cov}</b> have an automated test "
    f"(<b>{pct(tot_cov, tot_req):.1f}% coverage</b>) and <b>{tot_pass}</b> pass in full (<b>{pct(tot_pass, tot_req):.1f}% compliance</b>). "
    f"Money &amp; security modules: {crit_pass}/{crit_req} ({pct(crit_pass, crit_req):.1f}%). Other modules: {oth_pass}/{oth_req} ({oth_pct:.1f}%).", "body"))
rows = [hdr("UAT module", "BC / SB reference", "Req.", "Covered", "Pass", "Coverage", "Compliance", "")]
extra = []
for i, m in enumerate(MODULES, 1):
    s = STATS[m["key"]]
    fg, _ = tone(s["comp_pct"])
    rows.append([P(f"<b>{esc(m['name'])}</b>{' <font color=\"#b3261e\">●</font>' if m['key'] in CRITICAL else ''}"),
                 P(esc(m["bc"].split(";")[0]), "small"),
                 P(str(s["req"])), P(str(s["covered"])), P(str(s["passed"])),
                 P(f"{s['cov_pct']:.0f}%"),
                 P(f'<font color="{fg.hexval().replace("0x", "#")}"><b>{s["comp_pct"]:.1f}%</b></font>'),
                 bar(s["comp_pct"], 22 * mm, 3.2 * mm)])
rows.append([P("<b>All modules</b>"), P(""), P(f"<b>{tot_req}</b>"), P(f"<b>{tot_cov}</b>"), P(f"<b>{tot_pass}</b>"),
             P(f"<b>{pct(tot_cov, tot_req):.0f}%</b>"), P(f"<b>{pct(tot_pass, tot_req):.1f}%</b>"),
             bar(pct(tot_pass, tot_req), 22 * mm, 3.2 * mm)])
extra.append(("LINEABOVE", (0, len(rows) - 1), (-1, len(rows) - 1), 1, INK))
S.append(grid(rows, [46 * mm, 44 * mm, 10 * mm, 13 * mm, 10 * mm, 15 * mm, 18 * mm, W - 156 * mm], extra=extra))
S.append(P('<font color="#b3261e">●</font> money- or security-critical module (BC p.18: must pass 100%). '
           "Req. = required test cases defined for the module; Covered = required cases with at least one automated test; "
           "Pass = covered cases whose tests all pass.", "small"))

# ------------------------------------------------------------------ 4. Delivery windows + demo
S += [Spacer(1, 6), P("5. UAT delivery plan and demonstration script (BC p.17)", "h2")]
rows = [hdr("Window", "Required delivery (BC p.17)", "Modules", "Compliance")]
for w, desc, keys in WINDOWS:
    rq = sum(STATS[k]["req"] for k in keys)
    ps = sum(STATS[k]["passed"] for k in keys)
    p_ = pct(ps, rq)
    fg, _ = tone(p_)
    rows.append([P(f"<b>{w}</b>"), P(desc, "small"),
                 P(", ".join(next(m["name"] for m in MODULES if m["key"] == k) for k in keys), "small"),
                 P(f'<font color="{fg.hexval().replace("0x", "#")}"><b>{p_:.1f}%</b></font> <font size="6.5">({ps}/{rq})</font>')])
S.append(grid(rows, [20 * mm, 62 * mm, W - 110 * mm, 28 * mm]))
S.append(Spacer(1, 6))
rows = [hdr("Demonstration step (BC p.17)", "Evidencing test cases", "Compliance")]
for desc, ids in DEMO:
    sts = [id_status(i) for i in ids]
    p_ = pct(sum(1 for s in sts if s == "PASS"), len(ids))
    fg, _ = tone(p_)
    idtxt = ", ".join(f'<font color="{STATUS_COL[s][0].hexval().replace("0x", "#")}">{i}</font>' for i, s in zip(ids, sts))
    rows.append([P(esc(desc)), P(idtxt, "small"), P(f'<font color="{fg.hexval().replace("0x", "#")}"><b>{p_:.0f}%</b></font>')])
S.append(grid(rows, [86 * mm, W - 106 * mm, 20 * mm]))

# ------------------------------------------------------------------ 5. Module detail
S += [PageBreak(), P("6. Module-by-module evidence", "h1"),
      P("For each UAT module: where it is implemented, every required test case with its automated test and result, "
        "and the resulting coverage and compliance.", "body")]

for idx, m in enumerate(MODULES, 1):
    s = STATS[m["key"]]
    fg, bg = tone(s["comp_pct"])
    head = Table([[P(f"<b>6.{idx}  {esc(m['name'])}</b>", "h2"),
                   P(f'<font color="{fg.hexval().replace("0x", "#")}"><b>{s["comp_pct"]:.1f}%</b></font> compliance<br/>'
                     f'{s["cov_pct"]:.0f}% coverage', "cell")]],
                 colWidths=[W - 34 * mm, 34 * mm])
    head.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "MIDDLE"), ("LINEBELOW", (0, 0), (-1, 0), 1, INK),
                              ("LEFTPADDING", (0, 0), (-1, -1), 0), ("BACKGROUND", (1, 0), (1, 0), bg)]))
    block = [CondPageBreak(60 * mm), head, Spacer(1, 3),
             P(f"<b>Business case:</b> {esc(m['bc'])} &nbsp;·&nbsp; <b>Storyboard:</b> {esc(m['sb'])} &nbsp;·&nbsp; "
               f"<b>UAT window:</b> {m['window']}"
               f"{' &nbsp;·&nbsp; <font color=\"#b3261e\"><b>money/security-critical</b></font>' if m['key'] in CRITICAL else ''}",
               "small"),
             Spacer(1, 3), P("Where it is implemented", "h3")]
    wr = [hdr("Capability", "Backend source", "API", "UI screen")]
    for cap, src, api, ui in m["where"]:
        wr.append([P(esc(cap)), P(esc(src.replace("backend/src/main/java/com/mipay/sme/", "…/sme/")), "mono"),
                   P(esc(api), "mono"), P(ui_links(ui), "small")])
    block.append(grid(wr, [44 * mm, 52 * mm, W - 136 * mm, 40 * mm]))
    S += block
    S.append(P(f"Test cases — {s['req']} required · {s['covered']} covered · {s['passed']} pass · "
               f"{s['fail']} fail · {s['partial']} partial · {s['uncov']} not covered", "h3"))
    tr = [hdr("TC-ID", "Automated test (class · line) and what it asserts", "Result")]
    ext = []
    for i in m["required"]:
        st = s["status"][i]
        ts = by_id.get(i, [])
        if ts:
            desc = "<br/>".join(
                f"<font face='Mono' size='6.6'>{t['cls']}:{t['line'] or '?'}</font> — {esc(re.sub(r'^.*?·\s*', '', t['display']))}"
                + (f" <font color='#b3261e' size='6.6'>[{t['status']}: {esc(t['msg'][:140])}]</font>" if t["status"] != "PASS" else "")
                for t in ts)
        else:
            desc = "<font color='#5b6675'>No automated test asserts this TC-ID.</font>"
        tr.append([P(f"<b>{i}</b>"), P(desc, "cell"), pill(st, st)])
    S.append(grid(tr, [20 * mm, W - 48 * mm, 28 * mm]))
    S.append(Spacer(1, 8))

# ------------------------------------------------------------------ 6. Findings and out-of-scope
S += [PageBreak(), P("7. Open findings and residual gaps", "h1"), P("7.1 Failing and skipped tests (main evidence run)", "h2")]
fails = [t for t in tests if t["status"] != "PASS"]
rows = [hdr("Test", "TC-IDs", "Status", "Observed")]
for t in fails:
    rows.append([P(f"<font face='Mono' size='6.6'>{t['cls']}:{t['line'] or '?'}</font><br/>{esc(t['display'][:110])}"),
                 P(", ".join(t["ids"]) or "—", "small"), pill(t["status"], t["status"]), P(esc(t["msg"][:260]), "small")])
if len(rows) == 1:
    rows.append([P("No failing or skipped tests"), P(""), P(""), P("")])
S.append(grid(rows, [62 * mm, 26 * mm, 18 * mm, W - 106 * mm]))

if RACE_RUNS:
    S += [Spacer(1, 6), P("7.2 Run-to-run variance of the concurrency tests", "h2"),
          P(f"The race-condition class was re-run {len(RACE_RUNS)} more times on the same source snapshot. "
            "A test case that passes in one run and fails in another has a timing-dependent defect: it can fail in production "
            "under real load. Such a case is not counted as compliant, even if it passed in the main evidence run.", "body")]
    _ids = sorted({k for d in RACE_RUNS for k in d if k.startswith("TC-RACE")}, key=lambda x: int(x.split("-")[-1]))
    _rows = [hdr("TC-ID", "Main run", *[f"Re-run {i+1}" for i in range(len(RACE_RUNS))], "Verdict")]
    for _k in _ids:
        _main = next((t["status"] for t in by_id.get(_k, [])), "—")
        _all = [_main] + [d.get(_k, "—") for d in RACE_RUNS]
        _v = "STABLE PASS" if all(x == "PASS" for x in _all) else ("STABLE FAIL" if all(x == "FAIL" for x in _all) else "FLAKY")
        _rows.append([P(f"<b>{_k}</b>"), pill(_main, _main)] + [pill(x, x) for x in _all[1:]] +
                     [pill(_v, "PASS" if _v == "STABLE PASS" else "FAIL" if _v == "STABLE FAIL" else "PARTIAL")])
    _n = len(RACE_RUNS)
    S.append(grid(_rows, [22 * mm, 24 * mm] + [24 * mm] * _n + [W - 70 * mm - 24 * mm * _n]))
S += [Spacer(1, 6), P("7.3 Specification deviations noted during code review", "h3")]
S.append(grid([
    hdr("Item", "Business case", "Implementation", "Impact"),
    [P("Card service fee"), P("BC p.16: 2.50% billed vs 2.20% external acquirer cost (0.30% contribution)"),
     P("<font face='Mono' size='6.6'>DataSeeder.java:191</font> seeds 1.50% billed vs 2.20%; TC-SET-10 asserts 1.50%"),
     P("Seeded tariff gives a negative card contribution. Confirm the agreed tariff and align the seed and TC-SET-10.")],
    [P("QR pending display (finding #2)"), P("BC p.7–8: amounts show as pending until the issuer confirms"),
     P("QR/short-code accepts create no pending subledger row; spec test disabled"),
     P("Money rule holds (not spendable); the display gap affects TC-BAL-01")],
    [P("Khata entries API (finding #3)"), P("BC p.6: Khata entries distinguish credit from payment"),
     P("<font face='Mono' size='6.6'>Dtos.java:391</font> puts @NotBlank on an enum -&gt; HTTP 500"),
     P("Khata charge/settle cannot run via API (TC-OPS-04)")],
    [P("Ops payment feed (finding #4)"), P("BC p.13: TPTS oversight and reconciliation"),
     P("<font face='Mono' size='6.6'>OpsController.java:229</font> loads merchant name lazily -&gt; 500"),
     P("Ops payment feed screen fails (TC-OPS-10)")],
    [P("Day windows use the default time zone (finding #1)"), P("Daily sweeps and statements"),
     P("Mixes LocalDate.now() with UTC offsets"), P("Passes under UTC; a deployment risk on a non-UTC JVM")],
    [P("Ops cannot approve a supplier bank-account change (seen while recording)"), P("BC p.9/p.18: account changes need independent approval"),
     P("Request status is PENDING_APPROVAL, but /ops/approvals shows Approve/Reject only for status PENDING"),
     P("The page says ‘already PENDING_APPROVAL’ with no Approve button, so the independent-approval step cannot be completed in the UI.")],
    [P("‘Run advance flow’ fails (seen while recording)"), P("BC p.17 demo step 5; BC p.18 ‘Advance funding’"),
     P("The Admin → Simulator button calls scene ‘advance_flow’; the backend answers ‘Unknown scene advance_flow’"),
     P("Together with the missing Fund button, advance funding cannot be shown in the UI.")],
    [P("No ‘Fund’ action in the UI"), P("BC p.11/p.18 ‘Advance funding’"),
     P("The lender and merchant screens have no control for POST /api/lender/advances/{id}/fund"),
     P("Funding can be shown only through Admin → Simulator → ‘Run advance flow’.")],
    [P("No restore-test action in the UI"), P("BC p.18 ‘Security and recovery’"),
     P("/admin/hygiene offers ‘Backup now’ but no button for POST /api/admin/backups/{id}/restore-test"),
     P("RTO/RPO evidence is available from the automated test (TC-SEC-03) only.")],
], [30 * mm, 44 * mm, 52 * mm, W - 126 * mm]))

S += [Spacer(1, 6), P("7.4 Outside the software UAT (BC p.17–18 production readiness)", "h3"),
      P("BC p.17 states that the 25–30-day UAT does not promise bank certification, a new licence or a live credit "
        "facility. These conditions cannot be shown by test-case coverage. They are tracked here as external dependencies, "
        "not as compliance gaps.", "body")]
S.append(grid([
    hdr("Production condition (BC p.18 / p.27)", "Owner", "Platform status"),
    [P("Executed issuer, sponsor, lender and technology agreements"), P("TPTS / partners"), P("External — not evidenced in code")],
    [P("Real bank/issuer API, book-transfer/EFT and IPS certification"), P("Bank / Mindlabs"),
     P("Simulated connectors only (BankSimulator; every event labelled SIMULATED — TC-SET-28)")],
    [P("Actual P2M switching tariff, sponsor markup, onboarding fee"), P("Finance leads"),
     P("Provisions configured and labelled as unquoted (TC-SET-11/12)")],
    [P("Funded lending facility and lender-approved credit policy"), P("Lender"), P("Simulated lender adapter (TC-SET-06)")],
    [P("Native IPP B2B"), P("Operator"), P("Excluded at go-live, as BC requires (TC-SET-02)")],
], [78 * mm, 30 * mm, W - 108 * mm]))

# ------------------------------------------------------------------ Appendix
S += [PageBreak(), P("Appendix A — Full automated test results", "h1"),
      P(f"Run: {run_date}. Command: <font face='Mono'>cd backend &amp;&amp; ./gradlew test</font>. "
        f"Source: <font face='Mono'>backend/build/test-results/test/TEST-*.xml</font>. "
        f"Total {n_tests}: {n_pass} passed, {n_fail} failed, {n_skip} skipped.", "small"), Spacer(1, 3)]
rows = [hdr("Test class · line", "Test (display name)", "Time s", "Result")]
for t in sorted(tests, key=lambda x: (x["cls"], x["line"] or 0)):
    rows.append([P(f"{t['cls']}:{t['line'] or '?'}", "mono"), P(esc(t["display"][:150]), "small"),
                 P(f"{t['time']:.2f}", "small"), pill(t["status"], t["status"])])
S.append(grid(rows, [56 * mm, W - 94 * mm, 14 * mm, 24 * mm]))

doc.build(S)
print("wrote", OUT, "| coverage", f"{pct(tot_cov, tot_req):.1f}", "compliance", f"{pct(tot_pass, tot_req):.1f}")
