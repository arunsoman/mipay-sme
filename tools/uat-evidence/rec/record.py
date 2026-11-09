"""Record a captioned video (and key screenshots) of every BC p.18 UAT item on the live sandbox.

Usage: python record.py <out_dir> [item_id ...]
Every step is attempted for real; if a step cannot be completed the video shows that, with a red caption.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

from playwright.sync_api import sync_playwright

BASE = "http://localhost:4200"
API = "http://localhost:8080"
OUT = os.path.abspath(sys.argv[1])
ONLY = set(sys.argv[2:])
os.makedirs(OUT, exist_ok=True)
VW, VH = 1280, 800

CREDS = {"MERCHANT": ("0811111111", None), "SUPPLIER": ("0852222222", "supplier123"),
         "LENDER": ("0813333333", "lender123"), "TPTS_OPS": ("0827777777", "ops123"),
         "MINDLABS_ADMIN": ("0819999999", "admin123")}
DEMO_MERCHANT = {"phone": "0819" + str(int(time.time()))[-6:], "businessName": "UAT Demo Cafe (fictional)", "ownerName": "Selma (fictional)",
                 "ownerIdNumber": "UAT-0001", "businessType": "CAFE", "registrationNumber": "UAT/2026/001"}
ROLE_NAME = {"MERCHANT": "Merchant", "SUPPLIER": "Supplier", "LENDER": "Lender", "TPTS_OPS": "TPTS Ops",
             "MINDLABS_ADMIN": "Mindlabs Admin", "PUBLIC": "Customer (no login)", "DEMO_MERCHANT": "New merchant (UAT Demo Cafe)"}

CAPTION_JS = """
([title, text, kind]) => {
  let el = document.getElementById('__uat_cap');
  if (!el) {
    el = document.createElement('div'); el.id = '__uat_cap';
    el.style.cssText = 'position:fixed;left:16px;right:16px;bottom:16px;z-index:2147483647;padding:12px 16px;' +
      'border-radius:10px;font:600 15px/1.4 system-ui,sans-serif;color:#fff;box-shadow:0 6px 24px rgba(0,0,0,.35);pointer-events:none';
    document.body.appendChild(el);
  }
  el.style.background = kind === 'fail' ? 'rgba(179,38,30,.95)' : kind === 'pass' ? 'rgba(11,92,138,.95)' : 'rgba(17,24,39,.92)';
  el.innerHTML = '<div style="font:700 12px system-ui;opacity:.75;letter-spacing:.04em;text-transform:uppercase">' + title + '</div>' + text;
}
"""
HL_JS = """
(el) => { el.scrollIntoView({block:'center'}); el.style.outline = '3px solid #f59e0b'; el.style.outlineOffset = '2px';
          setTimeout(() => { el.style.outline = ''; }, 1600); }
"""


def api(method, path, body=None, token=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"content-type": "application/json", **({"authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


def wait_ready(limit=600):
    t0 = time.time()
    while time.time() - t0 < limit:
        try:
            with urllib.request.urlopen(BASE + "/api/env", timeout=5) as r:
                if r.status == 200 and b"sandbox" in r.read():
                    with urllib.request.urlopen(BASE + "/login", timeout=5) as r2:
                        if r2.status == 200:
                            time.sleep(3)
                            return
        except Exception:
            pass
        time.sleep(5)
    raise RuntimeError("app not reachable")


class Seg:
    """One role's browser session = one video segment."""

    def __init__(self, rec, role, label):
        self.rec, self.role, self.label = rec, role, label
        self.ctx = rec.browser.new_context(viewport={"width": VW, "height": VH},
                                           record_video_dir=rec.tmp, record_video_size={"width": VW, "height": VH})
        self.page = self.ctx.new_page()
        self.page.set_default_timeout(12000)
        self.ok = True

    # ---- captions & helpers
    def cap(self, text, kind="info", hold=2200):
        try:
            self.page.evaluate(CAPTION_JS, [f"{self.rec.item_id} · {ROLE_NAME[self.role]}", text, kind])
        except Exception:
            pass
        self.page.wait_for_timeout(hold)

    def shot(self, name):
        p = os.path.join(self.rec.item_dir, f"{len(os.listdir(self.rec.item_dir)) + 1:02d}_{name}.png")
        self.page.screenshot(path=p)
        self.rec.shots.append(os.path.relpath(p, OUT))

    def hl(self, loc):
        try:
            loc.first.evaluate(HL_JS)
        except Exception:
            pass
        self.page.wait_for_timeout(700)

    def step(self, caption, fn, shot=None):
        """Run one user action; record failure honestly."""
        self.cap(caption, hold=1200)
        try:
            fn()
            self.page.wait_for_timeout(1200)
            if shot:
                self.shot(shot)
            return True
        except Exception as e:
            self.ok = False
            msg = re.sub(r"\s+", " ", str(e)).split("Call log")[0][:160]
            self.cap(f"Step could not be completed in the live app: {msg}", "fail", 3500)
            self.shot("step_failed")
            self.rec.notes.append(f"{ROLE_NAME[self.role]}: '{caption}' could not be completed ({msg})")
            return False

    def goto(self, route, caption=None, shot=None):
        def f():
            self.page.goto(BASE + route)
            self.page.wait_for_load_state("networkidle")
        return self.step(caption or f"Open {BASE}{route}", f, shot)

    def click(self, loc, caption, shot=None):
        def f():
            self.hl(loc)
            loc.first.click()
            self.page.wait_for_load_state("networkidle")
        return self.step(caption, f, shot)

    def login(self, phone=None, pw=None):
        p = self.page
        if self.role == "PUBLIC":
            return
        phone, pw = (phone, pw) if phone else CREDS.get(self.role, (None, None))
        p.goto(BASE + "/login")
        p.wait_for_load_state("networkidle")
        self.cap(f"Log in as {ROLE_NAME[self.role]} ({phone})", hold=900)
        if pw:
            p.get_by_role("button", name="Staff login instead").click()
            p.get_by_placeholder("Phone (e.g. 0811111111)").fill(phone)
            p.get_by_placeholder("Password").fill(pw)
            p.get_by_role("button", name="Sign in").click()
        else:
            p.get_by_placeholder("Phone (e.g. 0811111111)").fill(phone)
            p.get_by_placeholder("OTP").fill("000000")
            p.get_by_role("button", name="Sign in").click()
            p.get_by_text("Dev OTP").wait_for()
            p.get_by_placeholder("OTP").fill("123456")
            p.get_by_role("button", name="Sign in").click()
        p.wait_for_function("() => !!localStorage.getItem('mp.token')")
        p.wait_for_timeout(800)

    def check_failed(self, what):
        self.page.wait_for_timeout(1500)
        body = self.page.inner_text("body")
        m = re.search(r"(FAILED[^\n]*\n?[^\n]*|Unknown scene[^\n]*)", body)
        if m:
            self.ok = False
            msg = re.sub(r"\s+", " ", m.group(0))[:160]
            self.rec.notes.append(f"{ROLE_NAME[self.role]}: {what} failed in the live app ({msg})")
            self.cap(f"{what} FAILED in the live app: {msg}", "fail", 4000)
            self.shot("failed")

    def text(self):
        return self.page.locator("main, body").first.inner_text()

    def close(self):
        self.page.wait_for_timeout(600)
        v = self.page.video
        self.ctx.close()
        return v.path() if v else None


class Rec:
    def __init__(self, browser):
        self.browser = browser
        self.tmp = os.path.join(OUT, "_raw")
        os.makedirs(self.tmp, exist_ok=True)
        self.manifest = {}

    def item(self, item_id, title, fn):
        if ONLY and item_id not in ONLY:
            return
        wait_ready()
        self.item_id, self.title = item_id, title
        slug = re.sub(r"[^a-z0-9]+", "-", f"{item_id} {title}".lower()).strip("-")
        self.item_dir = os.path.join(OUT, "screens", slug)
        shutil.rmtree(self.item_dir, ignore_errors=True)
        os.makedirs(self.item_dir)
        self.shots, self.notes, self.segs = [], [], []
        t0 = time.time()
        try:
            fn(self)
        except Exception as e:  # unexpected: keep whatever was recorded
            self.notes.append(f"recording aborted: {str(e)[:200]}")
        parts = [p for p in self.segs if p and os.path.exists(p)]
        mp4 = os.path.join(OUT, "videos", f"{slug}.mp4")
        os.makedirs(os.path.dirname(mp4), exist_ok=True)
        if parts:
            lst = os.path.join(self.tmp, "list.txt")
            # normalise each part then concat
            norm = []
            for i, p in enumerate(parts):
                n = os.path.join(self.tmp, f"{slug}_{i}.mp4")
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", p, "-r", "25", "-c:v", "libx264", "-preset", "veryfast",
                                "-crf", "26", "-pix_fmt", "yuv420p", "-an", n], check=True)
                norm.append(n)
            open(lst, "w").write("".join(f"file '{n}'\n" for n in norm))
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy",
                            "-movflags", "+faststart", mp4], check=True)
        dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", mp4],
                                   capture_output=True, text=True).stdout.strip() or 0) if parts else 0
        self.manifest[item_id] = {"title": title, "video": os.path.relpath(mp4, OUT) if parts else None, "duration": round(dur, 1),
                                  "screens": self.shots, "notes": self.notes, "seconds_to_record": round(time.time() - t0)}
        print(f"[{item_id}] {title}: {dur:.0f}s video, {len(self.shots)} shots, notes={self.notes}", flush=True)

    def seg(self, role, label=""):
        s = Seg(self, role, label)
        return s

    def end(self, s):
        self.segs.append(s.close())


# ======================================================================= UAT items
def u21(r):
    st, body = api("POST", "/api/onboarding/merchant", DEMO_MERCHANT)
    r.notes.append(f"setup (not recorded): registered fictional merchant {DEMO_MERCHANT['phone']} via POST /api/onboarding/merchant -> HTTP {st}")
    s = r.seg("TPTS_OPS"); s.login()
    s.goto("/ops/approvals", "Ops opens the approval queues: merchants, suppliers and bank-account changes", "approval_queues")
    row = s.page.get_by_role("row", name=re.compile("UAT Demo Cafe"))
    s.step("The new merchant is waiting for TPTS approval and cannot trade yet", lambda: s.hl(row), "merchant_pending")
    s.click(s.page.get_by_role("button", name="Approve"), "Ops approves the merchant (provisions balance account, QR and short code)", "merchant_approved")
    r.end(s)

    s = r.seg("SUPPLIER"); s.login()
    s.goto("/supplier/accounts", "Supplier opens its receiving bank accounts", "supplier_accounts")
    def chg():
        s.page.locator("select").first.select_option(label="FNB Namibia")
        tb = s.page.get_by_role("textbox")
        tb.nth(0).fill("62" + str(int(time.time()))[-8:])
        tb.nth(1).fill("Nam Wholesale CC")
        tb.nth(2).fill("UAT: change of receiving account")
    s.step("Supplier requests a change of receiving account", chg)
    s.click(s.page.get_by_role("button", name="Submit account change"), "Submit: the change must stay PENDING until someone else approves it", "change_pending")
    r.end(s)

    s = r.seg("TPTS_OPS"); s.login()
    s.goto("/ops/approvals", "Ops sees the pending bank-account change and who requested it")
    s.click(s.page.get_by_role("row").filter(has_text=re.compile("pending", re.I)).last, "Select the pending change (requester is the supplier, approver is Ops)", "bank_change_selected")
    if s.page.get_by_text(re.compile(r"already PENDING_APPROVAL")).count():
        s.ok = False
        r.notes.append("TPTS Ops: the pending bank-account change cannot be approved: the page shows "
                       "'Selected request is already PENDING_APPROVAL' and no Approve/Reject buttons")
        s.hl(s.page.get_by_text(re.compile(r"already PENDING_APPROVAL")))
        s.cap("DEFECT: the change is PENDING_APPROVAL but no Approve button is shown, so Ops cannot approve it", "fail", 4500)
        s.shot("bank_change_cannot_approve")
    else:
        card = s.page.locator("mp-card").filter(has_text="Bank account changes")
        s.click(card.get_by_role("button", name="Approve"), "A different person (Ops) approves it: independent approval", "bank_change_approved")
    s.cap("Check (tester decides): unapproved parties were blocked until approval and the account change needed a different approver.", "pass", 3000)
    r.end(s)


def u22(r):
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/balance", "Merchant balance before: note the Available and Pending tiles", "balance_before")
    s.goto("/merchant/accept", "Accept payment screen")
    def mk(amount, channel):
        s.page.get_by_role("spinbutton").first.fill(str(amount))
        s.page.locator("select").first.select_option(value=channel)
        s.page.get_by_placeholder("customer phone / card mask").fill("0815550001")
        s.page.get_by_role("button", name="Create payment").click()
        s.page.wait_for_load_state("networkidle")
    s.step("Create a N$60 QR payment", lambda: mk(60, "QR"), "qr_created")
    s.click(s.page.get_by_role("button", name="CONFIRM"), "The bank confirms it (simulated callback): status CONFIRMED, exactly once", "qr_confirmed")
    s.goto("/merchant/balance", "Available has gone up by N$60", "balance_after_qr")
    s.goto("/merchant/accept", "Now a N$120 CARD sale")
    s.step("Create a N$120 CARD payment and do NOT confirm it", lambda: mk(120, "CARD"), "card_pending")
    s.goto("/merchant/balance", "Pending shows the card sale; Available must NOT include it yet", "balance_card_pending")
    s.goto("/merchant/accept", "Known gap check: an unconfirmed QR payment")
    s.step("Create a N$15 QR payment and do NOT confirm it", lambda: mk(15, "QR"))
    s.goto("/merchant/balance", "Known gap (finding #2): the unconfirmed QR amount is not shown in Pending (it is correctly not spendable)", "qr_pending_gap")
    s.page.goto(BASE + "/merchant/accept"); s.page.wait_for_load_state("networkidle")
    s.page.get_by_text(re.compile(r"Short code:\s*MP\d+")).first.wait_for()
    m = re.search(r"Short code:\s*(MP\d+)", s.page.inner_text("body"))
    r.short_code = m.group(1) if m else None
    s.cap(f"Merchant short code for customers: {r.short_code}", hold=1500)
    r.end(s)

    s = r.seg("PUBLIC")
    s.goto("/customer", "A customer pays the merchant's short code from a basic phone / bank app")
    def pay():
        s.page.get_by_placeholder(re.compile("QR payload")).fill(r.short_code or "MP000000")
        s.page.get_by_placeholder("Your phone number").fill("0815550002")
        s.page.get_by_placeholder("Amount (N$)").fill("25")
        s.page.get_by_role("button", name="Pay now").click()
        s.page.wait_for_timeout(1500)
    s.step("Pay N$25 by short code", pay, "customer_paid")
    s.cap("Check (tester decides): each payment is confirmed once and pending card money never raised Available early.", "pass", 3000)
    r.end(s)


def _order_and_invoice(r, merchant_seg_factory):
    s = merchant_seg_factory()
    s.goto("/merchant/orders", "Merchant opens Stock & orders (catalogue of the onboarded supplier)", "catalogue")
    def order():
        sel = s.page.locator("select").first
        opts = [o for o in sel.locator("option").all_inner_texts() if "Choose" not in o]
        sel.select_option(label=opts[0])
        s.page.get_by_role("button", name="Order 1 unit").click()
        s.page.wait_for_load_state("networkidle")
    s.step("Order 1 unit from the supplier", order, "order_placed")
    r.end(s)
    s = r.seg("SUPPLIER"); s.login()
    s.goto("/supplier/orders", "Supplier sees the new order")
    s.click(s.page.get_by_role("row").filter(has_text=re.compile("submitted", re.I)).first, "Open the submitted order")
    s.click(s.page.get_by_role("button", name=re.compile("Issue invoice")), "Issue an invoice with a unique reference", "invoice_issued")
    r.end(s)


def u23(r):
    def ms():
        s = r.seg("MERCHANT"); s.login(); return s
    _order_and_invoice(r, ms)
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/balance", "Balance before paying the supplier", "balance_before")
    s.goto("/merchant/orders", "Pay supplier invoice from balance")
    s.click(s.page.get_by_role("row").filter(has_text=re.compile("issued", re.I)).first, "Select the open invoice (unique reference shown)", "invoice_selected")
    s.click(s.page.get_by_role("button", name="Pay via EFT"), "Pay from balance via EFT (book transfer / EFT only; native IPP B2B excluded)", "paid")
    s.goto("/merchant/balance", "One DEBIT for the invoice amount in Wallet movement; Available reduced once", "balance_after")
    s.cap("Check (tester decides): one debit, one supplier credit, invoice PAID, all on one reference.", "pass", 3000)
    r.end(s)


def _ensure_demo_merchant(r):
    st, _ = api("POST", "/api/onboarding/merchant", DEMO_MERCHANT)
    _, tok = api("POST", "/api/auth/login", {"phone": "0827777777", "password": "ops123"})
    t = tok["token"] if isinstance(tok, dict) else None
    _, q = api("GET", "/api/ops/approvals/merchants", token=t)
    for row in (q if isinstance(q, list) else []):
        if row.get("ownerPhone") == DEMO_MERCHANT["phone"]:
            api("POST", f"/api/ops/approvals/merchants/{row['id']}", {"approve": True, "sanctionsResult": "CLEAR"}, token=t)
    r.notes.append(f"setup (not recorded): fictional merchant {DEMO_MERCHANT['phone']} registered and approved via API (register HTTP {st})")


def u24(r):
    _ensure_demo_merchant(r)
    def ms():
        s = r.seg("DEMO_MERCHANT"); s.login(DEMO_MERCHANT["phone"]); return s
    _order_and_invoice(r, ms)
    s = r.seg("DEMO_MERCHANT"); s.login(DEMO_MERCHANT["phone"])
    s.goto("/merchant/balance", "The new merchant has N$0 available", "balance_zero")
    s.goto("/merchant/orders", "Try to pay an invoice larger than the balance")
    s.click(s.page.get_by_role("row").filter(has_text=re.compile("issued", re.I)).first, "Select the open invoice")
    s.click(s.page.get_by_role("button", name="Pay via EFT"), "Pay via EFT: it must be refused with a shortfall, no overdraft", "refused")
    s.goto("/merchant/balance", "Available still N$0: no hold, no debit, nothing negative", "balance_unchanged")
    s.cap("Check (tester decides): the purchase was refused and the balance never went negative.", "pass", 3000)
    r.end(s)


def u25(r):
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/balance", "Available / Pending / Held / Owed are kept apart; a hold reduces Available while a supplier payment runs", "tiles")
    s.cap("Two purchases racing for the same money cannot be shown by clicking one at a time. "
          "This is proven only by the automated test TC-RACE-02, which currently FAILS (concurrent invoices can overdraw).", "fail", 6000)
    s.shot("concurrency_note")
    r.end(s)


def u26(r):
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/accept", "Accept a payment, then replay bank callbacks")
    def mk():
        s.page.get_by_role("spinbutton").first.fill("33")
        s.page.locator("select").first.select_option(value="QR")
        s.page.get_by_placeholder("customer phone / card mask").fill("0815550003")
        s.page.get_by_role("button", name="Create payment").click()
        s.page.wait_for_load_state("networkidle")
    s.step("Create a N$33 QR payment", mk)
    s.click(s.page.get_by_role("button", name="TIMEOUT"), "Bank TIMEOUT: the payment must stay PENDING", "timeout_pending")
    s.click(s.page.get_by_role("button", name="CONFIRM"), "Final answer arrives: CONFIRMED", "confirmed")
    s.click(s.page.get_by_role("button", name="DUPLICATE"), "A DUPLICATE callback arrives: it must be ignored", "duplicate_ignored")
    s.goto("/merchant/balance", "Wallet movement: exactly one N$33 credit", "single_credit")
    s.goto("/merchant/payments", "Payments list")
    s.click(s.page.get_by_role("row").filter(has_text="N$ 33.00").first, "Open the payment: event trail shows one confirmation and the ignored duplicate", "event_trail")
    s.cap("Check (tester decides): pending was preserved and the duplicate did not credit twice.", "pass", 3000)
    r.end(s)


def u27(r):
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/advance", "Cash advance: eligibility from verified sales")
    s.step("Rules applied: advance proceeds, self-transfers and circular B2B are excluded",
           lambda: s.hl(s.page.get_by_text(re.compile("rules applied"))), "eligibility_rules")
    r.end(s)
    s = r.seg("MINDLABS_ADMIN"); s.login()
    s.goto("/admin/config", "Admin configuration")
    s.step("Business rules: the exclusion rules are configured (and audited when toggled)",
           lambda: s.hl(s.page.get_by_text(re.compile("exclude_", re.I))), "rules_config")
    s.cap("Check (tester decides): loan proceeds, own transfers and circular B2B are excluded from verified sales.", "pass", 3000)
    r.end(s)


def u28(r):
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/balance", "Available before any advance funding", "balance_before")
    s.goto("/merchant/advance", "Current advance: an ACCEPTED offer has added no money", "accepted_not_funded")
    r.end(s)
    s = r.seg("MINDLABS_ADMIN"); s.login()
    s.goto("/admin/simulator", "There is no 'Fund' button for lender or merchant, so funding is shown with the simulator")
    s.click(s.page.get_by_role("button", name="Run advance flow"),
            "Run advance flow: consent, assess, offer, accept, lender transfer, issuer confirms, sweep", "advance_flow")
    s.check_failed("‘Run advance flow’")
    s.cap("Check (tester decides): money became available only at the funded + issuer-confirmed step.", "pass", 3000)
    r.end(s)


def u29(r):
    s = r.seg("LENDER"); s.login()
    s.goto("/lender/collections", "Lender collections")
    s.click(s.page.get_by_role("button", name=re.compile("Run daily sweep")), "Run today's sweep: collect 10% of eligible daily sales only", "sweep_1")
    s.click(s.page.get_by_role("button", name=re.compile("Run daily sweep")), "Run it again the same day: nothing more must be collected", "sweep_2")
    r.end(s)
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/advance", "Merchant sees repayments and the remaining amount owed", "repayments")
    s.cap("Check (tester decides): only eligible receipts were used and the same day was not collected twice.", "pass", 3000)
    r.end(s)


def u210(r):
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/advance", "Data consent: named lender, purpose and status", "consent")
    r.end(s)
    s = r.seg("LENDER"); s.login()
    s.goto("/lender", "Lender sees consents and records only for merchants who consented", "lender_records")
    s.click(s.page.get_by_role("row").filter(has_text=re.compile("active", re.I)).first, "Open the consented merchant's records: no customer-level Khata", "records_open")
    s.cap("Check (tester decides): data is shared only under consent and Khata is never in the lender feed.", "pass", 3000)
    r.end(s)


def u211(r):
    s = r.seg("TPTS_OPS"); s.login()
    s.goto("/ops/recon", "Three-ledger reconciliation")
    s.click(s.page.get_by_role("button", name=re.compile("Run reconciliation")), "Run: issuer, subledger and credit ledger should match (0 breaks)", "recon_clean")
    r.end(s)
    s = r.seg("MINDLABS_ADMIN"); s.login()
    s.goto("/admin/simulator", "Inject an unexplained difference (simulated)")
    def inj():
        s.page.locator("select").filter(has=s.page.locator("option", has_text="Missing credit")).first.select_option(label="Missing credit")
        s.page.get_by_role("spinbutton").first.fill("50")
        tb = s.page.get_by_role("textbox")
        if tb.count():
            tb.first.fill("UAT-BREAK")
        s.page.get_by_role("button", name="Inject").click()
    s.step("Inject a N$50 missing-credit break", inj, "break_injected")
    r.end(s)
    s = r.seg("TPTS_OPS"); s.login()
    s.goto("/ops/recon", "Re-run reconciliation")
    s.click(s.page.get_by_role("button", name=re.compile("Run reconciliation")), "The break is flagged: withdrawals are now BLOCKED", "break_flagged")
    r.end(s)
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/balance", "Merchant tries to withdraw while a break is open")
    def wd():
        s.page.get_by_role("spinbutton").first.fill("10")
        s.page.get_by_role("button", name="Request withdrawal").click()
        s.page.wait_for_timeout(1500)
    s.step("Request a N$10 withdrawal: it must be refused", wd, "withdrawal_blocked")
    r.end(s)
    s = r.seg("MINDLABS_ADMIN"); s.login()
    s.goto("/admin/simulator", "Clean-up: clear the injected break")
    s.click(s.page.get_by_role("button", name="Clear all breaks"), "Clear all breaks", "cleared")
    r.end(s)
    s = r.seg("TPTS_OPS"); s.login()
    s.goto("/ops/recon", "Final run")
    s.click(s.page.get_by_role("button", name=re.compile("Run reconciliation")), "Clean again: no unexplained difference", "recon_clean_again")
    s.cap("Check (tester decides): a difference was flagged, blocked release until resolved, and ledgers match otherwise.", "pass", 3000)
    r.end(s)


def u212(r):
    s = r.seg("TPTS_OPS"); s.login()
    s.goto("/ops/reports", "Reports")
    s.click(s.page.get_by_role("button", name="Load"), "Today's settlement and fee report: each fee once, external cost and contribution separate", "fee_report")
    r.end(s)
    s = r.seg("MINDLABS_ADMIN"); s.login()
    s.goto("/admin/config", "Fee tariffs")
    s.step("Tariffs: switching N$0.40 = PROVISION, sponsor N$0.10 = UNQUOTED",
           lambda: s.hl(s.page.get_by_role("row", name=re.compile("ips_switching"))), "tariffs")
    s.step("Check: card_service is 1.5% here, but BC p.16 says 2.50%",
           lambda: s.hl(s.page.get_by_role("row", name=re.compile("card_service"))), "card_fee")
    s.cap("Check (tester decides): the agreed tariff is applied once and provisions are labelled. Confirm the card fee (1.50% vs 2.50%).", "fail", 4000)
    r.end(s)


def u213(r):
    s = r.seg("MERCHANT"); s.login()
    s.step("Merchant tries to open an Ops page directly", lambda: (s.page.goto(BASE + "/ops/approvals"), s.page.wait_for_load_state("networkidle")))
    s.shot("role_gate")
    s.cap(f"Current URL: {s.page.url.replace(BASE, '')} (ops pages are blocked for merchants)", hold=2500)
    r.end(s)
    s = r.seg("TPTS_OPS"); s.login()
    s.goto("/ops/audit", "Audit trail: privileged and simulated actions with actor and time", "audit")
    s.click(s.page.get_by_role("checkbox", name="Simulated events only"), "Show all events, not only simulated ones", "audit_all")
    r.end(s)
    s = r.seg("MINDLABS_ADMIN"); s.login()
    s.goto("/admin/hygiene", "Backups and restore tests")
    s.click(s.page.get_by_role("button", name="Backup now"), "Take a backup now", "backup")
    s.cap("Note: this page has no button to run a restore test (RTO/RPO is shown only by automated test TC-SEC-03).", "fail", 4000)
    r.end(s)


def uexit(r):
    s = r.seg("MINDLABS_ADMIN"); s.login()
    s.goto("/admin", "Health tiles: database, load-test TPS gate, open critical incidents", "health")
    r.end(s)
    s = r.seg("TPTS_OPS"); s.login()
    s.goto("/ops/incidents", "Incidents: critical incidents must be acknowledged within 15 minutes")
    def log():
        s.page.get_by_role("textbox").first.fill("UAT critical incident drill")
        s.page.locator("select").first.select_option(label="CRITICAL")
        s.page.get_by_role("button", name="Log incident").click()
        s.page.wait_for_load_state("networkidle")
    s.step("Log a CRITICAL incident", log, "incident_logged")
    s.click(s.page.get_by_role("row", name=re.compile("UAT critical incident drill")), "Select it")
    s.click(s.page.get_by_role("button", name="Acknowledge"), "Acknowledge immediately: not flagged late", "incident_ack")
    s.click(s.page.get_by_role("button", name="Resolve"), "Resolve the drill", "incident_resolved")
    r.end(s)


def u214(r):
    """BC p.17 demonstration script, five steps, end to end."""
    def intro(s, n, text):
        s.cap(f"Demo step {n} of 5 (BC p.17): {text}", hold=3200)

    # ---- Step 1: onboard fictional merchant + supplier
    st, _ = api("POST", "/api/onboarding/merchant", DEMO_MERCHANT)
    r.notes.append(f"setup (not recorded): registered fictional merchant {DEMO_MERCHANT['phone']} "
                   f"(‘UAT Demo Cafe’, stands in for Selma's café) via POST /api/onboarding/merchant -> HTTP {st}")
    s = r.seg("TPTS_OPS"); s.login()
    s.page.goto(BASE + "/ops/approvals"); s.page.wait_for_load_state("networkidle")
    intro(s, 1, "onboard the shop, the café and the Kamati Wholesale supplier, all labelled fictional")
    s.step("Ops approval queue: the fictional ‘UAT Demo Cafe’ is waiting for approval",
           lambda: s.hl(s.page.get_by_role("row", name=re.compile("UAT Demo Cafe"))), "s1_pending")
    card = s.page.locator("mp-card").filter(has_text="Merchant approvals")
    rows = card.get_by_role("row").filter(has_text=re.compile(r"\d{10}"))
    idx = next((i for i, t in enumerate(rows.all_inner_texts()) if DEMO_MERCHANT["phone"] in t), 0)
    s.step("This is the café just registered (" + DEMO_MERCHANT["phone"] + ")", lambda: s.hl(rows.nth(idx)))
    s.click(card.get_by_role("button", name="Approve").nth(idx),
            "Approve the café: it gets a balance account, QR and short code", "s1_approved")
    r.end(s)
    s = r.seg("SUPPLIER"); s.login()
    s.goto("/supplier/catalogue", "Kamati Wholesale (synthetic supplier) is onboarded with a stock catalogue", "s1_supplier")
    r.end(s)

    # ---- Step 2: accept a verified payment, available vs pending
    s = r.seg("MERCHANT"); s.login()
    s.page.goto(BASE + "/merchant/accept"); s.page.wait_for_load_state("networkidle")
    intro(s, 2, "accept a verified customer payment and show available and pending funds separately")
    def mk(amount, channel, phone):
        s.page.get_by_role("spinbutton").first.fill(str(amount))
        s.page.locator("select").first.select_option(value=channel)
        s.page.get_by_placeholder("customer phone / card mask").fill(phone)
        s.page.get_by_role("button", name="Create payment").click()
        s.page.wait_for_load_state("networkidle")
    s.step("Create a N$80 QR payment from a customer", lambda: mk(80, "QR", "0815550010"))
    s.click(s.page.get_by_role("button", name="CONFIRM"), "The bank verifies it (simulated): CONFIRMED", "s2_confirmed")
    s.goto("/merchant/accept", "Now a N$50 card sale that the bank has not settled yet")
    s.step("Create a N$50 CARD payment, not confirmed", lambda: mk(50, "CARD", "0815550011"))
    s.goto("/merchant/balance", "Available includes the N$80; the N$50 card sale sits in Pending, not spendable", "s2_balance")
    r.end(s)

    # ---- Step 3: pay supplier invoice from balance, incl. timeout and duplicate
    s = r.seg("MERCHANT"); s.login()
    s.page.goto(BASE + "/merchant/orders"); s.page.wait_for_load_state("networkidle")
    intro(s, 3, "pay a supplier invoice from the balance, including a timeout and a duplicate callback")
    def order():
        sel = s.page.locator("select").first
        opts = [o for o in sel.locator("option").all_inner_texts() if "Choose" not in o]
        sel.select_option(label=opts[0])
        s.page.get_by_role("button", name="Order 1 unit").click()
        s.page.wait_for_load_state("networkidle")
    s.step("Order stock from Kamati Wholesale", order, "s3_order")
    r.end(s)
    s = r.seg("SUPPLIER"); s.login()
    s.goto("/supplier/orders", "Supplier receives the order")
    s.click(s.page.get_by_role("row").filter(has_text=re.compile("submitted", re.I)).first, "Open the order")
    s.click(s.page.get_by_role("button", name=re.compile("Issue invoice")), "Issue the invoice (unique reference)", "s3_invoice")
    r.end(s)
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/orders", "Merchant pays the invoice from the platform balance")
    s.click(s.page.get_by_role("row").filter(has_text=re.compile("issued", re.I)).first, "Select the invoice")
    s.click(s.page.get_by_role("button", name="Pay via EFT"), "Pay via EFT: one debit, one supplier credit, invoice PAID", "s3_paid")
    s.cap("Note: the demo invoice is the catalogue price of 1 unit, not N$6,000; the amount does not change the controls being shown.", hold=3200)
    s.goto("/merchant/accept", "Timeout and duplicate callback case")
    s.step("Create a N$33 QR payment", lambda: mk(33, "QR", "0815550012"))
    s.click(s.page.get_by_role("button", name="TIMEOUT"), "Bank TIMEOUT: stays PENDING, nothing credited", "s3_timeout")
    s.click(s.page.get_by_role("button", name="CONFIRM"), "Final answer: CONFIRMED once")
    s.click(s.page.get_by_role("button", name="DUPLICATE"), "DUPLICATE callback: ignored, no second credit", "s3_duplicate")
    r.end(s)

    # ---- Step 4: synthetic history, eligibility, consent, lender review
    s = r.seg("MINDLABS_ADMIN"); s.login()
    s.page.goto(BASE + "/admin/simulator"); s.page.wait_for_load_state("networkidle")
    intro(s, 4, "show 90–120 days of synthetic trading, the eligibility calculation, consent and lender review")
    s.click(s.page.get_by_role("button", name="Generate 120d history"), "Generate 120 days of synthetic (SIMULATED) trading data", "s4_history")
    r.end(s)
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/advance", "Merchant: eligibility from verified sales, with the exclusion rules applied", "s4_eligibility")
    s.step("Data consent: named lender, purpose and status",
           lambda: s.hl(s.page.get_by_text(re.compile("consent", re.I))), "s4_consent")
    r.end(s)
    s = r.seg("LENDER"); s.login()
    s.goto("/lender", "Lender review: only consented merchants and their verified-data summary", "s4_lender")
    r.end(s)

    # ---- Step 5: fund an advance, spend on stock, collect only the authorised share
    s = r.seg("MINDLABS_ADMIN"); s.login()
    s.page.goto(BASE + "/admin/simulator"); s.page.wait_for_load_state("networkidle")
    intro(s, 5, "fund an example advance, spend it on stock and collect only the authorised share of later sales")
    s.click(s.page.get_by_role("button", name="Run advance flow"),
            "Run advance flow: consent, assess, offer, accept, lender transfer, issuer confirms, sweep", "s5_advance_flow")
    s.check_failed("‘Run advance flow’")
    r.end(s)
    s = r.seg("LENDER"); s.login()
    s.goto("/lender/collections", "Lender collections")
    s.click(s.page.get_by_role("button", name=re.compile("Run daily sweep")), "Daily sweep: collects only the authorised 10% of eligible sales", "s5_sweep")
    r.end(s)
    s = r.seg("MERCHANT"); s.login()
    s.goto("/merchant/advance", "Merchant: repayments and remaining amount owed", "s5_repayments")
    s.cap("Check (tester decides): each of the five BC p.17 demo steps was shown end to end on the sandbox.", "pass", 3500)
    r.end(s)


ITEMS = [("2.1", "Merchant and supplier approval", u21), ("2.2", "Acceptance and availability", u22),
         ("2.3", "Pay from balance", u23), ("2.4", "Insufficient balance", u24), ("2.5", "Concurrent orders", u25),
         ("2.6", "Timeout and duplicate", u26), ("2.7", "Advance eligibility", u27), ("2.8", "Advance funding", u28),
         ("2.9", "Repayment mandate", u29), ("2.10", "Consent and Khata", u210), ("2.11", "Reconciliation", u211),
         ("2.12", "Fees and billing", u212), ("2.13", "Security and recovery", u213), ("2.14", "Demo script", u214), ("2.15", "Exit-criteria checks", uexit)]

if __name__ == "__main__":
    mf = os.path.join(OUT, "manifest.json")
    with sync_playwright() as p:
        b = p.chromium.launch(executable_path="/usr/bin/google-chrome-stable", headless=True)
        rec = Rec(b)
        if os.path.exists(mf):
            rec.manifest = json.load(open(mf))
        for iid, title, fn in ITEMS:
            rec.item(iid, title, fn)
            json.dump(rec.manifest, open(mf, "w"), indent=1)
        b.close()
    shutil.rmtree(rec.tmp, ignore_errors=True)
