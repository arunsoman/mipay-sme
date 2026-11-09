"""Record one captioned tour video per Addendum 1 screen group.

Usage: python addendum_rec.py <out_dir> [group ...]
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
OUT_ARG, GROUPS_ONLY = sys.argv[1], set(sys.argv[2:])
sys.argv = [sys.argv[0], OUT_ARG]          # record.py reads argv at import
import record as R                          # noqa: E402
from addendum import GROUPS, A, P, N         # noqa: E402

R.ROLE_NAME.setdefault("PUBLIC", "Customer (no login)")
LABEL = {A: "AVAILABLE", P: "PARTIAL", N: "NOT BUILT"}
KIND = {A: "pass", P: "info", N: "fail"}


def highlight(s, text):
    pg = s.page
    card = pg.locator("mp-card").filter(has_text=text)
    loc = card if card.count() else pg.get_by_text(text, exact=False)
    if loc.count():
        s.hl(loc)
        return True
    return False


def tour(gid, gname, screens):
    def fn(r):
        shown = [x for x in screens if x[4]]
        missing = [x for x in screens if not x[4]]
        s, cur = None, None
        for sid, name, st, role, route, hl, note in shown:
            if role != cur:
                if s:
                    r.end(s)
                s = r.seg(role); s.login(); cur = role
                s.page.goto(R.BASE + route); s.page.wait_for_load_state("networkidle")
                s.cap(f"Addendum 1 {gid} {gname}: screens available in the live app", hold=1800)
            if s.page.url.replace(R.BASE, "") != route:
                s.page.goto(R.BASE + route); s.page.wait_for_load_state("networkidle")
                s.page.wait_for_timeout(700)
            found = highlight(s, hl)
            s.cap(f"<b>{sid} {name}</b>: {LABEL[st]}<br>{note}" + ("" if found else " (element not found on screen)"), KIND[st], 3600)
            s.shot(sid)
        if missing:
            s.cap("Not built in this release: " + ", ".join(f"{x[0]} {x[1]}" for x in missing), "fail", 4500)
        r.end(s)
    return fn


if __name__ == "__main__":
    import json
    mf = os.path.join(R.OUT, "manifest.json")
    with R.sync_playwright() as p:
        b = p.chromium.launch(executable_path="/usr/bin/google-chrome-stable", headless=True)
        rec = R.Rec(b)
        if os.path.exists(mf):
            rec.manifest = json.load(open(mf))
        for gid, gname, screens in GROUPS:
            if GROUPS_ONLY and gid not in GROUPS_ONLY:
                continue
            if not any(x[4] for x in screens):
                continue
            rec.item(gid, gname, tour(gid, gname, screens))
            json.dump(rec.manifest, open(mf, "w"), indent=1)
        b.close()
    R.shutil.rmtree(rec.tmp, ignore_errors=True)
