#!/usr/bin/env bash
# Regenerates docs/plans/fapi2-spec-pack.pdf from the markdown source.
# Requires: pandoc, google-chrome-stable (headless print), pdftoppm (optional, for review).
set -euo pipefail
cd "$(dirname "$0")"
pandoc fapi2-spec-pack.md -f gfm -t html -s \
  --metadata title="FAPI 2.0 Compliance — the complete spec pack" \
  --include-in-header=/tmp/fapi2-style.html --include-before-body=/tmp/fapi2-titlemet.html -o /tmp/fapi2-pack.html
python3 - <<'PY'
import re
s = open('/tmp/fapi2-pack.html').read()
s = re.sub(r'<h1 class="title">.*?</h1>', '', s, flags=re.S)
s = s.replace('✅', '<span style="color:#14804a;font-weight:bold">&#10004; ok</span>')
s = s.replace('⏳', '<span style="color:#b06a00;font-weight:bold">&#9679; todo</span>')
def tables(m):
    t = m.group(0)
    ncols = t.count('<th>')
    widths = {3:(24,18,58), 4:(6,34,22,38), 5:(5,26,11,42,16)}.get(ncols)
    if not widths: return t
    col = '<colgroup>' + ''.join(f'<col style="width:{w}%">' for w in widths) + '</colgroup>'
    return t.replace('<thead>', col + '<thead>', 1)
s = re.sub(r'<table.*?</table>', tables, s, flags=re.S)
open('/tmp/fapi2-pack.html','w').write(s)
PY
google-chrome-stable --headless --disable-gpu --no-sandbox --no-pdf-header-footer \
  --print-to-pdf=fapi2-spec-pack.pdf file:///tmp/fapi2-pack.html
echo "written: docs/plans/fapi2-spec-pack.pdf"
