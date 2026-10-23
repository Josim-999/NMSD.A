#!/usr/bin/env python3
"""
site_health_check.py — Load & resilience checker for sites YOU OWN.

Two modes, run together or separately:
  1. Load test   : measure throughput, latency percentiles and the point where
                   your server starts to degrade (its practical capacity).
  2. Domain check: inspect TLS certificate, security headers and redirects —
                   the things that actually make a domain "strong".

This is a diagnostic tool for your OWN infrastructure. Pointing load at systems
you do not own or have written permission to test is illegal (CFAA, UK CMA,
and equivalents). The script makes you confirm ownership before it runs and
keeps load conservative by design.

No third-party dependencies — standard library only.

Usage:
    python3 site_health_check.py
    (it will prompt for the URL and options interactively)

    or non-interactively:
    python3 site_health_check.py --url https://example.com --mode both \
        --concurrency 20 --requests 500
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import socket
import ssl
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# ----------------------------------------------------------------------------
# Safety limits. These are intentionally conservative. A capacity check does
# not need to be a flood — a few hundred requests tells you plenty, and keeps
# you from accidentally knocking over your own production box.
# ----------------------------------------------------------------------------
MAX_CONCURRENCY = 100          # hard ceiling on parallel workers
MAX_TOTAL_REQUESTS = 5000      # hard ceiling on total requests per run
DEFAULT_CONCURRENCY = 10
DEFAULT_REQUESTS = 200
REQUEST_TIMEOUT = 15           # seconds
USER_AGENT = "site-health-check/1.0 (self-test; +owner-operated)"

# Hosts we simply refuse to touch, regardless of confirmation. These are not
# yours to test from a script like this.
BLOCKED_SUFFIXES = (".onion",)
BLOCKED_HOSTS = {
    "localhost",  # allowed below via explicit opt-in; see is_blocked()
}


def eprint(*args, **kwargs):
    print(*args, file=sys.stderr, **kwargs)


def is_blocked(host: str) -> str | None:
    """Return a reason string if the host must not be tested, else None."""
    host = host.lower().strip(".")
    for suffix in BLOCKED_SUFFIXES:
        if host.endswith(suffix):
            return f"Refusing: {suffix} / hidden-service targets are out of scope for this tool."
    return None


def normalize_url(raw: str) -> str:
    raw = raw.strip()
    if not raw:
        raise ValueError("empty URL")
    if "://" not in raw:
        raw = "https://" + raw
    parsed = urllib.parse.urlparse(raw)
    if parsed.scheme not in ("http", "https"):
        raise ValueError(f"unsupported scheme: {parsed.scheme!r}")
    if not parsed.netloc:
        raise ValueError("could not parse a host from the URL")
    return raw


def confirm_ownership(url: str) -> bool:
    host = urllib.parse.urlparse(url).hostname or ""
    reason = is_blocked(host)
    if reason:
        eprint(reason)
        return False

    print()
    print("=" * 70)
    print("  OWNERSHIP CONFIRMATION")
    print("=" * 70)
    print(f"  Target: {url}")
    print(f"  Host:   {host}")
    print()
    print("  Only run this against a site you own or are explicitly authorized")
    print("  to test. Running load against others' systems is a crime.")
    print()
    answer = input(f'  Type the host name "{host}" to confirm you own it: ').strip().lower()
    if answer != host.lower():
        eprint("  Host name did not match. Aborting.")
        return False
    return True


# ----------------------------------------------------------------------------
# Mode 1: load test
# ----------------------------------------------------------------------------
def _single_request(url: str) -> tuple[float, int | None, str | None]:
    """Perform one GET. Returns (elapsed_seconds, status_code, error)."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT}, method="GET")
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as resp:
            resp.read()  # drain body so timing reflects a full response
            return (time.perf_counter() - start, resp.status, None)
    except urllib.error.HTTPError as e:
        return (time.perf_counter() - start, e.code, None)
    except Exception as e:  # noqa: BLE001 - we want to record any failure
        return (time.perf_counter() - start, None, type(e).__name__)


def load_test(url: str, concurrency: int, total_requests: int) -> None:
    concurrency = max(1, min(concurrency, MAX_CONCURRENCY))
    total_requests = max(1, min(total_requests, MAX_TOTAL_REQUESTS))

    print()
    print("-" * 70)
    print(f"LOAD TEST  ->  {url}")
    print(f"  concurrency={concurrency}  total_requests={total_requests}")
    print(f"  (capped at {MAX_CONCURRENCY} workers / {MAX_TOTAL_REQUESTS} requests)")
    print("-" * 70)

    latencies: list[float] = []
    statuses: dict[int, int] = {}
    errors: dict[str, int] = {}

    wall_start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(_single_request, url) for _ in range(total_requests)]
        done = 0
        for fut in concurrent.futures.as_completed(futures):
            elapsed, status, err = fut.result()
            latencies.append(elapsed)
            if status is not None:
                statuses[status] = statuses.get(status, 0) + 1
            if err is not None:
                errors[err] = errors.get(err, 0) + 1
            done += 1
            if done % max(1, total_requests // 10) == 0:
                print(f"  ...{done}/{total_requests}")
    wall = time.perf_counter() - wall_start

    latencies.sort()
    ok = sum(c for s, c in statuses.items() if 200 <= s < 400)
    fail = total_requests - ok

    def pct(p: float) -> float:
        if not latencies:
            return 0.0
        idx = min(len(latencies) - 1, int(round(p / 100 * len(latencies))) - 1)
        return latencies[max(0, idx)] * 1000

    print()
    print("RESULTS")
    print(f"  wall time        : {wall:.2f} s")
    print(f"  throughput       : {total_requests / wall:.1f} req/s")
    print(f"  success (2xx/3xx): {ok}")
    print(f"  failures         : {fail}")
    if latencies:
        print(f"  latency min      : {latencies[0] * 1000:.1f} ms")
        print(f"  latency p50      : {pct(50):.1f} ms")
        print(f"  latency p90      : {pct(90):.1f} ms")
        print(f"  latency p99      : {pct(99):.1f} ms")
        print(f"  latency max      : {latencies[-1] * 1000:.1f} ms")
        print(f"  latency mean     : {statistics.mean(latencies) * 1000:.1f} ms")
    if statuses:
        print("  status codes     : " + ", ".join(f"{s}×{c}" for s, c in sorted(statuses.items())))
    if errors:
        print("  errors           : " + ", ".join(f"{e}×{c}" for e, c in sorted(errors.items())))
    print()
    print("  Reading it: if p99 and max latency climb far above p50, or failures")
    print("  appear as concurrency rises, that's where your server starts to")
    print("  saturate. Re-run with higher --concurrency to find the knee.")


# ----------------------------------------------------------------------------
# Mode 2: domain strength / security posture
# ----------------------------------------------------------------------------
SECURITY_HEADERS = {
    "strict-transport-security": "HSTS — forces HTTPS, protects against downgrade",
    "content-security-policy": "CSP — mitigates XSS and injection",
    "x-content-type-options": "blocks MIME sniffing (expect: nosniff)",
    "x-frame-options": "clickjacking protection (or use CSP frame-ancestors)",
    "referrer-policy": "controls referrer leakage",
    "permissions-policy": "restricts powerful browser features",
}


def check_tls(host: str, port: int = 443) -> None:
    print()
    print("-" * 70)
    print(f"TLS CERTIFICATE  ->  {host}:{port}")
    print("-" * 70)
    ctx = ssl.create_default_context()
    try:
        with socket.create_connection((host, port), timeout=REQUEST_TIMEOUT) as sock:
            with ctx.wrap_socket(sock, server_hostname=host) as ssock:
                cert = ssock.getpeercert()
                print(f"  protocol         : {ssock.version()}")
                print(f"  cipher           : {ssock.cipher()[0]}")
                subject = dict(x[0] for x in cert.get("subject", []))
                issuer = dict(x[0] for x in cert.get("issuer", []))
                print(f"  subject CN       : {subject.get('commonName', '?')}")
                print(f"  issuer           : {issuer.get('organizationName', issuer.get('commonName', '?'))}")
                not_after = cert.get("notAfter")
                if not_after:
                    expires = dt.datetime.strptime(not_after, "%b %d %H:%M:%S %Y %Z")
                    days = (expires - dt.datetime.utcnow()).days
                    flag = "  <-- RENEW SOON" if days < 21 else ""
                    print(f"  expires          : {not_after} ({days} days){flag}")
    except Exception as e:  # noqa: BLE001
        eprint(f"  TLS check failed : {type(e).__name__}: {e}")


def check_headers(url: str) -> None:
    print()
    print("-" * 70)
    print(f"SECURITY HEADERS  ->  {url}")
    print("-" * 70)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT}, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as resp:
            headers = {k.lower(): v for k, v in resp.headers.items()}
            final_url = resp.geturl()
    except urllib.error.HTTPError as e:
        headers = {k.lower(): v for k, v in e.headers.items()}
        final_url = url
    except Exception as e:  # noqa: BLE001
        eprint(f"  header check failed: {type(e).__name__}: {e}")
        return

    if final_url != url:
        print(f"  redirected to    : {final_url}")
    if "server" in headers:
        print(f"  server banner    : {headers['server']}  (consider hiding the version)")

    present, missing = [], []
    for h, why in SECURITY_HEADERS.items():
        (present if h in headers else missing).append((h, why))

    if present:
        print("  present:")
        for h, _ in present:
            print(f"    [ok]   {h}: {headers[h][:60]}")
    if missing:
        print("  missing (consider adding):")
        for h, why in missing:
            print(f"    [gap]  {h} — {why}")

    score = len(present)
    total = len(SECURITY_HEADERS)
    print(f"  header score     : {score}/{total}")


def domain_check(url: str) -> None:
    parsed = urllib.parse.urlparse(url)
    host = parsed.hostname or ""
    if parsed.scheme == "https":
        check_tls(host, parsed.port or 443)
    else:
        print("\n  (http:// target — skipping TLS check; consider moving to HTTPS)")
    check_headers(url)


# ----------------------------------------------------------------------------
# Entry point
# ----------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser(description="Load & resilience checker for sites you own.")
    ap.add_argument("--url", help="target URL (will prompt if omitted)")
    ap.add_argument("--mode", choices=("load", "domain", "both"), default="both")
    ap.add_argument("--concurrency", type=int, default=DEFAULT_CONCURRENCY)
    ap.add_argument("--requests", type=int, default=DEFAULT_REQUESTS)
    ap.add_argument("--yes", action="store_true",
                    help="skip the interactive ownership prompt (use only in your own automation)")
    args = ap.parse_args()

    raw = args.url or input("Enter the URL of YOUR site to check: ")
    try:
        url = normalize_url(raw)
    except ValueError as e:
        eprint(f"Invalid URL: {e}")
        return 2

    host = urllib.parse.urlparse(url).hostname or ""
    reason = is_blocked(host)
    if reason:
        eprint(reason)
        return 2

    if not args.yes:
        if not confirm_ownership(url):
            return 1

    if args.mode in ("domain", "both"):
        domain_check(url)
    if args.mode in ("load", "both"):
        load_test(url, args.concurrency, args.requests)

    print("\nDone.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        eprint("\nInterrupted.")
        sys.exit(130)
