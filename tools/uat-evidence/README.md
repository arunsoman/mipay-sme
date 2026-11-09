# UAT evidence tooling

Builds `docs/TPTS UAT Compliance Evidence Report.pdf` and the video gallery `docs/uat-evidence/index.html`.

Needs the app stack running (`docker compose up`, frontend on :4200), Python with `playwright` and `reportlab`, Google Chrome and ffmpeg.

```
python -m venv venv && venv/bin/pip install playwright reportlab pypdf
venv/bin/python -I collect.py ...              # JUnit XML -> results.json (test results)
venv/bin/python -I rec/record.py rec/out        # record the business-case UAT videos (2.1-2.15)
venv/bin/python -I rec/addendum_rec.py rec/add_out   # record the Addendum 1 screen tours
./assemble.sh                                   # copy evidence, build the PDF and the gallery
```
