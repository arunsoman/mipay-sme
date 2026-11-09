"""Write uat-evidence/index.html: one section per UAT item with the video, live links and screenshots.

Usage: python make_index.py <evidence_dir> <results.json>
"""
import html
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from walkthrough import BASE, BC_STEPS, EXIT_STEPS, ROLE_SHORT, LOGINS  # noqa: E402
from modules import BC_ACCEPTANCE  # noqa: E402

EVID = sys.argv[1]
man = json.load(open(os.path.join(EVID, "manifest.json")))
auto = json.load(open(sys.argv[2])) if len(sys.argv) > 2 else {}

items = [(f"2.{i}", n, BC_STEPS[n], ev) for i, (n, ev, _) in enumerate(BC_ACCEPTANCE, 1)]
from modules import DEMO  # noqa: E402
from walkthrough import DEMO_STEPS  # noqa: E402
_demo = [(r, u, f"Step {k}: {d}", "This demo step is shown on screen (see the video).")
         for k, ((d, _), pages) in enumerate(zip(DEMO, DEMO_STEPS), 1) for (r, u) in pages]
items.append(("2.14", "Demo script", _demo, "Demonstration script (BC p.17), five steps end to end"))
items.append(("2.15", "Exit-criteria checks", [(r, u, w, s) for (w, r, u, s) in EXIT_STEPS], "Service targets visible on screen"))

e = html.escape
RATES = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 2]
SPEED = ('<div class="speed" role="group" aria-label="Playback speed"><span>Speed</span>'
         + "".join(f'<button type="button" data-rate="{r}">{r:g}×</button>' for r in RATES) + '</div>')
out = []
for iid, name, steps, ev in items:
    m = man.get(iid, {})
    vid = m.get("video")
    pct = auto.get(name)
    badge = "" if pct is None else f'<span class="pct {"g" if pct >= 95 else "a" if pct >= 75 else "r"}">automated {pct:.0f}%</span>'
    rows = "".join(
        f'<tr><td>{e(ROLE_SHORT[r])}</td><td><a href="{BASE}{u}" target="_blank" rel="noopener">{BASE}{u}</a></td>'
        f'<td>{e(do)}</td><td>{e(see)}</td></tr>' for (r, u, do, see) in steps)
    shots = "".join(f'<a href="{e(s)}" target="_blank"><img loading="lazy" src="{e(s)}" alt="{e(os.path.basename(s))}"></a>'
                    for s in m.get("screens", []))
    probs = [n for n in m.get("notes", []) if not n.startswith("setup")]
    probs_html = ("<div class='warn'><b>Seen in the live app:</b><ul>" + "".join(f"<li>{e(p)}</li>" for p in probs) + "</ul></div>") if probs else ""
    video = (f'<video controls preload="metadata" poster="posters/{e(os.path.splitext(os.path.basename(vid))[0])}.jpg" src="{e(vid)}"></video>{SPEED}' if vid else "<p class='muted'>No recording.</p>")
    out.append(f"""
<section id="{iid}">
  <header><h2><span class="num">{iid}</span> {e(name)}</h2>{badge}</header>
  <p class="muted">{"BC p.17" if iid == "2.14" else "BC p.18"}: {e(ev)}</p>
  <div class="grid">
    <div>{video}<p class="muted small">{m.get('duration', 0):.0f} s recording of the live sandbox · <a href="{e(vid or '')}" download>download</a></p></div>
    <div>
      <table><thead><tr><th>Log in as</th><th>Live page</th><th>Do this</th><th>Pass if you see</th></tr></thead><tbody>{rows}</tbody></table>
      {probs_html}
    </div>
  </div>
  <details><summary>Screenshots ({len(m.get('screens', []))})</summary><div class="shots">{shots}</div></details>
</section>""")

tiles = []
for iid, name, steps, ev in items:
    m = man.get(iid, {})
    if not m.get("video"):
        continue
    pct = auto.get(name)
    cls = "" if pct is None else ("g" if pct >= 95 else "a" if pct >= 75 else "r")
    probs = [n for n in m.get("notes", []) if not n.startswith("setup")]
    poster = "posters/" + os.path.splitext(os.path.basename(m["video"]))[0] + ".jpg"
    tiles.append(f"""<button class="tile" data-video="{e(m['video'])}" data-title="{e(iid + '  ' + name)}" aria-label="Play {e(name)}">
  <span class="thumb"><img src="{e(poster)}" alt=""><span class="play" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg></span>
  <span class="dur">{int(m.get('duration', 0)) // 60}:{int(m.get('duration', 0)) % 60:02d}</span></span>
  <span class="cap"><b>{e(iid)}</b> {e(name)}</span>
  <span class="meta">{'' if pct is None else f'<span class="pct {cls}">automated {pct:.0f}%</span>'}{'<span class="pct r">issue seen live</span>' if probs else ''}</span>
</button>""")
gallery = "".join(tiles)

# ---------------------------------------------------------------- Addendum 1 screens
from addendum import GROUPS as AGROUPS, A as AV, P as PA, stats  # noqa: E402
_am_path = os.path.join(EVID, "addendum_manifest.json")
aman = json.load(open(_am_path)) if os.path.exists(_am_path) else {}
ROLE_OF = {k: v for k, v in ROLE_SHORT.items()}
ST_CLS = {AV: "g", PA: "a"}
all_scr = [x for _, _, sc in AGROUPS for x in sc]
ta, tp, tn = stats(all_scr)
atiles, asecs, arows_sum = [], [], []
for gid, gname, sc in AGROUPS:
    m = aman.get(gid, {})
    a_, p_, n_ = stats(sc)
    arows_sum.append(f'<tr><td><a href="#add-{gid}">{gid}</a></td><td>{e(gname)}</td><td>{len(sc)}</td><td>{a_}</td><td>{p_}</td><td>{n_}</td>'
                     f'<td><b>{100 * a_ / len(sc):.0f}%</b></td><td>{100 * (a_ + p_) / len(sc):.0f}%</td></tr>')
    shots = {os.path.splitext(os.path.basename(x))[0].split("_", 1)[1]: x for x in m.get("screens", [])}
    if m.get("video"):
        poster = "posters/" + os.path.splitext(os.path.basename(m["video"]))[0] + ".jpg"
        d = int(m.get("duration", 0))
        atiles.append(f"""<button class="tile" data-video="{e(m['video'])}" data-title="{e('Addendum ' + gid + '  ' + gname)}" aria-label="Play {e(gname)}">
  <span class="thumb"><img src="{e(poster)}" alt=""><span class="play" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg></span>
  <span class="dur">{d // 60}:{d % 60:02d}</span></span>
  <span class="cap"><b>{e(gid)}</b> {e(gname)}</span>
  <span class="meta"><span class="pct g">{a_} available</span>{f'<span class="pct a">{p_} partial</span>' if p_ else ''}{f'<span class="pct r">{n_} not built</span>' if n_ else ''}</span>
</button>""")
    rows = []
    for sid, name, st, role, route, hl, note in sc:
        cls = ST_CLS.get(st, "r")
        live = (f'{e(ROLE_OF[role])}<br><a href="{BASE}{route}" target="_blank" rel="noopener">{BASE}{route}</a>' if route else
                ('<a href="http://localhost:8080/swagger-ui/index.html" target="_blank" rel="noopener">localhost:8080/swagger-ui</a>' if sid == "X17" else '<span class="muted">none</span>'))
        th = shots.get(sid)
        img = f'<a href="{e(th)}" target="_blank"><img class="th" loading="lazy" src="{e(th)}" alt="{sid} screenshot"></a>' if th else ""
        rows.append(f'<tr><td><b>{sid}</b></td><td>{e(name)}</td><td><span class="pct {cls}">{st.lower()}</span></td><td>{live}</td><td>{e(note)}</td><td>{img}</td></tr>')
    vid = m.get("video")
    vblock = (f'<div class="addvid"><video controls preload="metadata" poster="posters/{e(os.path.splitext(os.path.basename(vid))[0])}.jpg" src="{e(vid)}"></video>{SPEED}</div>' if vid else
              '<p class="muted small">No video: none of these screens exist in the app.</p>')
    asecs.append(f"""
<section id="add-{gid}">
  <header><h2><span class="num">{gid}</span> {e(gname)}</h2><span class="pct g">{a_} available</span><span class="pct a">{p_} partial</span><span class="pct r">{n_} not built</span></header>
  {vblock}
  <div class="box"><table class="add"><thead><tr><th>ID</th><th>Screen</th><th>Status</th><th>Live page</th><th>What the app has</th><th>Screenshot</th></tr></thead><tbody>{''.join(rows)}</tbody></table></div>
</section>""")
addendum_html = f"""
<h2 id="addendum" style="margin:34px 0 6px">Addendum 1 screens: setup, configuration and operations</h2>
<p class="muted">Each screen listed in Addendum 1 (Parts A and B) checked against the live app on {BASE}.
<b>Available</b>: the screen does what the addendum describes. <b>Partial</b>: some of it is on screen. <b>Not built</b>: no screen does it.</p>
<div class="kpis"><div><b>{len(all_scr)}</b><span>screens in the addendum</span></div>
<div><b class="g">{ta}</b><span>available ({100 * ta / len(all_scr):.0f}%)</span></div>
<div><b class="a">{tp}</b><span>partial ({100 * tp / len(all_scr):.0f}%)</span></div>
<div><b class="r">{tn}</b><span>not built ({100 * tn / len(all_scr):.0f}%)</span></div></div>
<div class="box"><table style="max-width:820px"><thead><tr><th>Group</th><th>Area</th><th>Screens</th><th>Available</th><th>Partial</th><th>Not built</th><th>Available %</th><th>Available or partial %</th></tr></thead>
<tbody>{''.join(arows_sum)}</tbody></table></div>
<h3 style="margin:18px 0 10px">Screen tour videos: click a tile to play</h3>
<div class="gallery">{''.join(atiles)}</div>
{''.join(asecs)}
"""

logins = "".join(f"<tr><td>{e(a)}</td><td><code>{e(c)}</code></td><td><code>{e(d)}</code></td></tr>" for a, _, c, d, _ in LOGINS)
toc = "".join(f'<a href="#{iid}">{iid} {e(n)}</a>' for iid, n, _, _ in items)

page = f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>UAT Video Evidence</title>
<style>
:root {{ --bg:#f6f7f9; --card:#fff; --ink:#1b2430; --muted:#5b6675; --rule:#d6dbe1; --accent:#0b5c8a;
        --g:#1f7a4d; --gbg:#e5f3eb; --a:#a86400; --abg:#fbf0dc; --r:#b3261e; --rbg:#f9e3e1; }}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{ --bg:#0f141a; --card:#171e26; --ink:#e6ebf0; --muted:#9aa6b2; --rule:#2a3440;
        --accent:#6cb6e6; --gbg:#173a29; --abg:#3d2c10; --rbg:#3f1715; }} }}
* {{ box-sizing:border-box }}
body {{ margin:0; background:var(--bg); color:var(--ink); font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif }}
.wrap {{ max-width:1200px; margin:0 auto; padding:24px 16px 64px }}
h1 {{ font-size:24px; margin:0 0 4px }} h2 {{ font-size:18px; margin:0 }}
.muted {{ color:var(--muted) }} .small {{ font-size:12px }}
a {{ color:var(--accent) }}
nav {{ display:flex; flex-wrap:wrap; gap:6px 14px; margin:14px 0 20px; font-size:13px }}
section {{ background:var(--card); border:1px solid var(--rule); border-radius:10px; padding:16px; margin:0 0 16px; scroll-margin-top:12px }}
section header {{ display:flex; align-items:center; gap:12px; flex-wrap:wrap }}
.num {{ color:var(--muted); font-weight:600; margin-right:4px }}
.pct {{ font-size:12px; font-weight:700; padding:2px 8px; border-radius:999px }}
.pct.g {{ color:var(--g); background:var(--gbg) }} .pct.a {{ color:var(--a); background:var(--abg) }} .pct.r {{ color:var(--r); background:var(--rbg) }}
.grid {{ display:grid; grid-template-columns:minmax(0,1.1fr) minmax(0,1fr); gap:16px; margin-top:10px }}
@media (max-width:860px) {{ .grid {{ grid-template-columns:1fr }} }}
video {{ width:100%; border-radius:8px; border:1px solid var(--rule); background:#000 }}
table {{ width:100%; border-collapse:collapse; font-size:12.5px }}
th,td {{ text-align:left; vertical-align:top; padding:6px; border-bottom:1px solid var(--rule) }}
th {{ font-size:11.5px; color:var(--muted) }}
td a {{ word-break:break-all }}
.warn {{ margin-top:10px; padding:8px 10px; border-radius:8px; background:var(--rbg); color:var(--ink); font-size:12.5px }}
.warn ul {{ margin:4px 0 0 18px; padding:0 }}
details {{ margin-top:10px }} summary {{ cursor:pointer; color:var(--accent) }}
.shots {{ display:grid; grid-template-columns:repeat(auto-fill,minmax(180px,1fr)); gap:8px; margin-top:8px }}
.shots img {{ width:100%; border-radius:6px; border:1px solid var(--rule) }}
.box {{ overflow-x:auto }}
.gallery {{ display:grid; grid-template-columns:repeat(auto-fill,minmax(230px,1fr)); gap:14px }}
.tile {{ all:unset; cursor:pointer; display:flex; flex-direction:column; gap:6px; background:var(--card); border:1px solid var(--rule); border-radius:10px; padding:8px }}
.tile:hover, .tile:focus-visible {{ border-color:var(--accent); box-shadow:0 0 0 2px var(--accent) }}
.thumb {{ position:relative; display:block; aspect-ratio:16/10; border-radius:7px; overflow:hidden; background:#000 }}
.thumb img {{ width:100%; height:100%; object-fit:cover; display:block; opacity:.92 }}
.play {{ position:absolute; inset:0; margin:auto; width:54px; height:54px; border-radius:50%; background:rgba(0,0,0,.6); display:grid; place-items:center }}
.play svg {{ width:26px; height:26px; fill:#fff; margin-left:3px }}
.dur {{ position:absolute; right:6px; bottom:6px; background:rgba(0,0,0,.7); color:#fff; font-size:11px; padding:1px 6px; border-radius:4px }}
.cap {{ font-size:13.5px; line-height:1.3 }} .meta {{ display:flex; gap:6px; flex-wrap:wrap }}
dialog {{ width:min(1100px,96vw); padding:0; border:none; border-radius:12px; background:#000; color:#fff }}
dialog::backdrop {{ background:rgba(0,0,0,.75) }}
.ph {{ display:flex; justify-content:space-between; align-items:center; padding:8px 12px; background:#111 }}
.ph button {{ all:unset; cursor:pointer; font-size:18px; padding:2px 8px }}
.speed {{ display:flex; flex-wrap:wrap; align-items:center; gap:4px; margin-top:6px; font-size:12px }}
.speed span {{ color:var(--muted); margin-right:4px }}
.speed button {{ font:inherit; cursor:pointer; padding:3px 9px; border-radius:999px; border:1px solid var(--rule); background:var(--card); color:var(--ink) }}
.speed button:hover {{ border-color:var(--accent) }}
.speed button[aria-pressed="true"] {{ background:var(--accent); border-color:var(--accent); color:#fff; font-weight:700 }}
.pbar {{ display:flex; flex-wrap:wrap; justify-content:space-between; align-items:center; gap:8px; padding:6px 12px 10px; background:#111 }}
.pbar .speed {{ margin:0 }} .pbar .speed span, .keys {{ color:#aaa; font-size:12px }}
.pbar .speed button {{ background:#222; border-color:#444; color:#fff }}
.pbar .speed button[aria-pressed="true"] {{ background:#6cb6e6; border-color:#6cb6e6; color:#000 }}
kbd {{ font:11px ui-monospace,monospace; padding:0 4px; border:1px solid #555; border-radius:3px }}
.kpis {{ display:flex; flex-wrap:wrap; gap:10px; margin:12px 0 }}
.kpis div {{ background:var(--card); border:1px solid var(--rule); border-radius:10px; padding:10px 14px; min-width:150px }}
.kpis b {{ display:block; font-size:24px }} .kpis span {{ color:var(--muted); font-size:12px }}
b.g {{ color:var(--g) }} b.a {{ color:var(--a) }} b.r {{ color:var(--r) }}
.addvid {{ max-width:760px; margin:10px 0 }}
table.add td:nth-child(5) {{ min-width:240px }}
img.th {{ width:150px; border-radius:5px; border:1px solid var(--rule); display:block }}
dialog video {{ width:100%; display:block; border:none; border-radius:0 }}
</style></head><body><div class="wrap">
<h1>UAT Video Evidence</h1>
<p class="muted">TPTS · MiPay SME. Recorded walkthroughs of each Business Case v3.0 UAT item on the live sandbox ({BASE}).
Watch the video, or open the live page and check it yourself. Record your Pass/Fail verdict in the PDF report (section 2).</p>
<div class="box"><table style="max-width:560px"><thead><tr><th>Role</th><th>Phone</th><th>Password / OTP</th></tr></thead><tbody>{logins}</tbody></table></div>
<p class="small muted">Log in at <a href="{BASE}/login" target="_blank">{BASE}/login</a> first, then open a live link.</p>
<h2 style="margin:22px 0 10px">Videos: click a tile to play</h2>
<div class="gallery">{gallery}</div>
<dialog id="player"><div class="ph"><b id="ptitle"></b><button id="pclose" aria-label="Close">✕</button></div><video id="pvideo" controls playsinline></video><div class="pbar">{SPEED}<span class="keys">Keys: <kbd>&lt;</kbd> slower · <kbd>&gt;</kbd> faster · <kbd>Space</kbd> play/pause</span></div></dialog>
<p style="margin:10px 0 0"><a href="#addendum">Jump to the Addendum 1 screens (setup, configuration and operations) →</a></p>
<h2 style="margin:28px 0 6px">Step-by-step checks and live links</h2>
<nav>{toc}</nav>
{''.join(out)}
{addendum_html}
</div>
<script>
const RATES = {RATES};
let rate = 1;
try {{ rate = parseFloat(localStorage.getItem('uat.rate')) || 1; }} catch (e) {{}}
const vids = () => document.querySelectorAll('video');
function setRate(r) {{
  rate = r;
  try {{ localStorage.setItem('uat.rate', r); }} catch (e) {{}}
  vids().forEach(v => {{ v.defaultPlaybackRate = r; v.playbackRate = r; }});
  document.querySelectorAll('.speed button').forEach(b => b.setAttribute('aria-pressed', parseFloat(b.dataset.rate) === r));
}}
document.querySelectorAll('.speed button').forEach(b => b.addEventListener('click', () => setRate(parseFloat(b.dataset.rate))));
vids().forEach(v => v.addEventListener('loadedmetadata', () => {{ v.playbackRate = rate; }}));
setRate(rate);
const dlg = document.getElementById('player'), v = document.getElementById('pvideo');
document.querySelectorAll('.tile').forEach(t => t.addEventListener('click', () => {{
  document.getElementById('ptitle').textContent = t.dataset.title;
  v.src = t.dataset.video; dlg.showModal(); v.play().catch(() => {{}});
}}));
const close = () => {{ v.pause(); dlg.close(); }};
document.getElementById('pclose').addEventListener('click', close);
dlg.addEventListener('click', e => {{ if (e.target === dlg) close(); }});
dlg.addEventListener('close', () => v.pause());
dlg.addEventListener('keydown', e => {{
  const i = RATES.indexOf(rate);
  if (e.key === '<' || e.key === ',') {{ setRate(RATES[Math.max(0, i - 1)]); e.preventDefault(); }}
  else if (e.key === '>' || e.key === '.') {{ setRate(RATES[Math.min(RATES.length - 1, i + 1)]); e.preventDefault(); }}
  else if (e.key === ' ' && e.target.tagName !== 'BUTTON') {{ v.paused ? v.play() : v.pause(); e.preventDefault(); }}
}});
</script></body></html>"""
open(os.path.join(EVID, "index.html"), "w").write(page)
print("wrote", os.path.join(EVID, "index.html"))
