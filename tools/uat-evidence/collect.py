"""Collect UAT test results: map JUnit XML results to TC IDs using test sources.

Usage: python collect.py <backend_dir> <out_json>
"""
import json
import re
import sys
import glob
import os
import xml.etree.ElementTree as ET

backend = sys.argv[1]
out = sys.argv[2]

src_dir = os.path.join(backend, "src/test/java/com/mipay/sme/uat")
res_dir = os.path.join(backend, "build/test-results/test")

# method -> (class, displayName, line)
methods = {}
for f in sorted(glob.glob(os.path.join(src_dir, "*.java"))):
    cls = os.path.basename(f)[:-5]
    lines = open(f, encoding="utf-8").read().split("\n")
    pending_dn = None
    pending_disabled = False
    for i, ln in enumerate(lines, 1):
        m = re.search(r'@DisplayName\("(.*)"\)', ln)
        if m:
            pending_dn = m.group(1).encode().decode("unicode_escape") if "\\u" in m.group(1) else m.group(1)
        if "@Disabled" in ln or "Disabled(" in ln:
            pending_disabled = True
        m2 = re.search(r'\bvoid\s+(\w+)\s*\(', ln)
        if m2 and not ln.strip().startswith("//") and ("@Test" in "\n".join(lines[max(0, i - 12):i])):
            name = m2.group(1)
            methods[(cls, name)] = {
                "cls": cls, "method": name, "display": pending_dn or name,
                "line": i, "disabled_src": pending_disabled,
            }
            pending_dn = None
            pending_disabled = False

results = []
for f in sorted(glob.glob(os.path.join(res_dir, "TEST-*.xml"))):
    if os.path.getsize(f) == 0:
        continue
    root = ET.parse(f).getroot()
    for tc in root.iter("testcase"):
        cls = tc.get("classname", "").split(".")[-1]
        name = tc.get("name", "")
        mname = re.sub(r"\(.*\)$", "", name)
        info = methods.get((cls, mname))
        if info is None:
            # match by display name
            for (c, mm), v in methods.items():
                if c == cls and (v["display"] == name or v["display"] == mname):
                    info = v
                    break
        if tc.find("failure") is not None or tc.find("error") is not None:
            node = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
            status = "FAIL"
            msg = (node.get("message") or node.text or "").strip().split("\n")[0][:300]
        elif tc.find("skipped") is not None:
            status = "SKIP"
            msg = (tc.find("skipped").get("message") or "").strip().split("\n")[0][:300]
        else:
            status = "PASS"
            msg = ""
        disp = info["display"] if info else name
        tid = re.match(r"\s*((?:TC-[A-Z]+-\d+[a-z]?(?:/[A-Z0-9-]+)*)|(?:Scene \d)|(?:E\d+)|(?:M\d+))", disp)
        results.append({
            "cls": cls, "method": mname, "display": disp, "id": tid.group(1) if tid else disp[:20],
            "line": info["line"] if info else None, "status": status, "msg": msg,
            "time": float(tc.get("time", "0")),
        })

import datetime
snap = os.path.join(backend, "..", "SNAPSHOT_TIME")
meta = {"date": open(snap).read().strip() if os.path.exists(snap) else datetime.datetime.now().isoformat(timespec="seconds")}
json.dump({"results": results, "meta": meta}, open(out, "w"), indent=1)
from collections import Counter
print(Counter(r["status"] for r in results), len(results))
for r in results:
    if r["status"] != "PASS":
        print(r["status"], r["cls"], r["method"], "|", r["msg"][:160])
