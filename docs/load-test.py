#!/usr/bin/env python3
"""
TC-PERF-01 — Peak load: 20 TPS sustained soak against the UAT sandbox (BC p.18).

Usage:
    python3 docs/load-test.py --base http://localhost:8080 --tps 20 --duration 1800

The script:
  1. Logs the seeded merchant in (phone + OTP + password fallback).
  2. Fires accept-payment initiations at the requested rate (simulator stays
     in MANUAL so every confirmation is explicitly driven - the bank callback
     fan-out is measured by the TC-PERF tests, not here), plus a small read
     mix (dashboard + balance).
  3. Prints a summary: achieved TPS, p50/p95 latency, HTTP error count.

No failed-commit / ledger-drift assertion is made here: after the soak, run
POST /api/ops/recon/run (TPTS ops) and verify the three-ledger match (TC-RCN-01).
"""
import argparse
import json
import statistics
import threading
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--tps", type=int, default=20)
parser.add_argument("--duration", type=int, default=1800, help="seconds")
parser.add_argument("--phone", default="0811111111", help="seeded merchant phone")
parser.add_argument("--password", default=None, help="staff-style password login instead of OTP")
args = parser.parse_args()


def call(path, payload=None, token=None, method=None):
    url = args.base + path
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method or ("POST" if data else "GET"))
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def login():
    if args.password:
        status, login = call("/api/auth/login", {"phone": args.phone, "password": args.password})
    else:
        status, _ = call("/api/auth/otp", {"phone": args.phone})
        status, login = call("/api/auth/otp/verify", {"phone": args.phone, "code": "123456"})
    if status != 200 or "token" not in login:
        raise SystemExit(f"login failed ({status}): {login}")
    return login["token"]


token = login()
print("logged in; starting soak at", args.tps, "TPS for", args.duration, "s")

latencies, statuses, lock = [], [], threading.Lock()
stop_at = time.time() + args.duration
interval = 1.0 / args.tps


def worker():
    t0 = time.time()
    dice = uuid.uuid4().bytes[0]
    if dice % 5 == 0:  # ~20% read mix
        code, _ = call("/api/merchant/dashboard", token=token)
    elif dice % 10 == 1:
        code, _ = call("/api/merchant/balance", token=token)
    else:
        amount = 10 + (dice % 190)  # N$10..N$199
        code, body = call("/api/merchant/payments",
                          {"channel": "QR", "amount": amount, "payerRef": "load-" + uuid.uuid4().hex[:8]},
                          token=token)
    with lock:
        latencies.append((time.time() - t0) * 1000.0)
        statuses.append(code)


threads = []
while time.time() < stop_at:
    t = threading.Thread(target=worker)
    t.start()
    threads.append(t)
    time.sleep(interval)

for t in threads:
    t.join(timeout=15)

ok = [l for l, s in zip(latencies, statuses) if s == 200]
errs = [(s, l) for s, l in zip(statuses, latencies) if s != 200]
achieved = len(statuses) / args.duration
lat_sorted = sorted(ok)
p50 = lat_sorted[len(lat_sorted) // 2] if lat_sorted else 0
p95 = lat_sorted[int(len(lat_sorted) * 0.95)] if lat_sorted else 0

print(f"requests={len(statuses)} ok={len(ok)} errors={len(errs)}")
print(f"achieved_tps={achieved:.2f} (target {args.tps})")
print(f"latency_ms p50={p50:.0f} p95={p95:.0f}")
if errs:
    print("error statuses:", sorted({s for s, _ in errs}))
print("next: POST /api/ops/recon/run -> three ledgers must match (TC-RCN-01)")
