#!/bin/bash
# Copy recordings into docs/uat-evidence, make poster frames, build gallery + PDF.
set -e
S="$(cd "$(dirname "$0")" && pwd)"
D="$S/../../docs/uat-evidence"
rm -rf "$D" && mkdir -p "$D/posters"
cp -r $S/rec/out/videos $S/rec/out/screens $S/rec/out/manifest.json "$D/"
if [ -d $S/rec/add_out/videos ]; then
  cp $S/rec/add_out/videos/*.mp4 "$D/videos/"; cp -r $S/rec/add_out/screens/* "$D/screens/"
  cp $S/rec/add_out/manifest.json "$D/addendum_manifest.json"
fi
for v in "$D"/videos/*.mp4; do
  d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$v")
  ffmpeg -loglevel error -y -ss "$(python3 -c "print(max(1, float('$d')*0.55))")" -i "$v" -frames:v 1 -vf scale=640:-1 -q:v 4 "$D/posters/$(basename "${v%.mp4}").jpg"
done
cd $S
UAT_EVID_DIR="$D" venv/bin/python -I build_report.py results.json "/home/arun/sme/docs/TPTS UAT Compliance Evidence Report.pdf"
venv/bin/python -I make_index.py "$D" bc_pcts.json
