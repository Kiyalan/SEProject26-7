#!/usr/bin/env python3
"""Mint a RepoPilot app JWT matching backend JwtUtil (HS256 + github_token claim)."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
from pathlib import Path


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def pad_secret(secret: str) -> bytes:
    """Mirror JwtUtil: pad UTF-8 bytes to at least 32 bytes with zeros."""
    key = secret.encode("utf-8")
    if len(key) < 32:
        padded = bytearray(32)
        padded[: len(key)] = key
        return bytes(padded)
    return key


def create_token(username: str, github_token: str, secret: str, expiration_hours: int) -> str:
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": username,
        "github_token": github_token,
        "iat": now,
        "exp": now + expiration_hours * 3600,
    }
    h = b64url(json.dumps(header, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
    p = b64url(json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
    signing_input = f"{h}.{p}".encode("ascii")
    sig = hmac.new(pad_secret(secret), signing_input, hashlib.sha256).digest()
    return f"{h}.{p}.{b64url(sig)}"


def load_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def main() -> int:
    parser = argparse.ArgumentParser(description="Mint RepoPilot JWT for JMeter (bypass frontend OAuth UI)")
    parser.add_argument("--username", default=os.environ.get("GITHUB_USERNAME", ""))
    parser.add_argument("--github-token", default=os.environ.get("GITHUB_TOKEN", ""))
    parser.add_argument("--secret", default=os.environ.get("JWT_SECRET", ""))
    parser.add_argument("--expiration-hours", type=int, default=int(os.environ.get("JWT_EXPIRATION_HOURS", "168")))
    parser.add_argument("--env-file", default="", help="Optional .env path (perf/.env or backend/.env)")
    parser.add_argument("--print-secret-hint", action="store_true")
    args = parser.parse_args()

    env: dict[str, str] = {}
    root = Path(__file__).resolve().parents[2]
    candidates = []
    if args.env_file:
        candidates.append(Path(args.env_file))
    candidates.extend([root / "perf" / ".env", root / "backend" / ".env", root / ".env"])
    for candidate in candidates:
        loaded = load_env_file(candidate)
        if loaded:
            env.update(loaded)
            break

    username = args.username or env.get("GITHUB_USERNAME", "")
    github_token = args.github_token or env.get("GITHUB_TOKEN", "") or env.get("GH_TOKEN", "")
    # Preserve empty JWT_SECRET from .env (backend pads "" to 32 zero bytes).
    # Only fall back to application.yml default when the key is truly absent.
    if args.secret != "":
        secret = args.secret
    elif "JWT_SECRET" in env:
        secret = env["JWT_SECRET"]
    elif os.environ.get("JWT_SECRET") is not None:
        secret = os.environ.get("JWT_SECRET", "")
    else:
        secret = "repopilot-jwt-secret-change-in-production"

    if not username or not github_token:
        print(
            "Missing GITHUB_USERNAME / GITHUB_TOKEN. Set them in perf/.env or pass --username / --github-token.",
            file=sys.stderr,
        )
        return 2

    token = create_token(username, github_token, secret, args.expiration_hours)
    if args.print_secret_hint:
        print(f"# secret_len={len(secret.encode('utf-8'))} padded={len(pad_secret(secret))}", file=sys.stderr)
    print(token)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
