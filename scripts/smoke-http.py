#!/usr/bin/env python3
"""Real Office jar; synthetic credentials; optional explicitly isolated Feezit."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ.get("FEEZIT_JAVA_HOME", "/home/stellabot/.local/jdk-25.0.2")) / "bin/java"
USERNAME = "operator-smoke"
PASSWORD = "synthetic-smoke-password-only"
# BCrypt test fixture; never an application default account.
HASH = "$2b$04$.Y2ZtUVqsvGorQWKEXT3S.iD/USZkKLPlnqbWmfdJkNXbm9bRV9T2"


def run(args):
    scratch = Path(os.environ.get("TMPDIR", "/home/stellabot/.hermes/cache/scratch"))
    logs = Path(args.log_dir) if args.log_dir else Path(tempfile.mkdtemp(prefix="office-smoke-", dir=scratch))
    logs.mkdir(parents=True, exist_ok=True)
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    env = {k: v for k, v in os.environ.items() if not k.startswith(("OFFICE_", "FEEZIT_", "SPRING_", "SERVER_"))}
    env.update(OFFICE_OPERATOR_USERNAME=USERNAME, OFFICE_OPERATOR_PASSWORD_HASH=HASH)
    if args.feezit_url:
        url = urllib.parse.urlsplit(args.feezit_url)
        if not args.isolated_feezit or url.scheme != "http" or url.hostname != "127.0.0.1" or url.port in (None, 8087) or url.path not in ("", "/") or url.query or url.fragment or url.username:
            raise SystemExit("Require explicitly isolated loopback Feezit, not production port 8087.")
        env.update(FEEZIT_API_BASE_URL=args.feezit_url, FEEZIT_MANAGEMENT_SECRET=os.environ.get("OFFICE_SMOKE_MANAGEMENT_SECRET", "synthetic-smoke-management-secret"))
    elif args.commands:
        raise SystemExit("Command fixtures require isolated Feezit.")
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    base = f"http://127.0.0.1:{port}"
    checks = []

    def request(method, path, expected, body=None, csrf=None, content_type="application/json"):
        headers = {"Accept": "application/json"}
        if csrf:
            headers[csrf["headerName"]] = csrf["token"]
        data = None
        if body is not None:
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
            headers["Content-Type"] = content_type
        req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            response = opener.open(req, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            if response.status != expected:
                raise AssertionError(f"{method} {path}: expected {expected}, got {response.status}")
            checks.append({"method": method, "path": path, "status": response.status})
            return json.loads(raw) if raw and response.headers.get_content_type() == "application/json" else None

    log_path = logs / "office-jar.log"
    with log_path.open("wb") as output:
        process = subprocess.Popen([str(JAVA), "--enable-native-access=ALL-UNNAMED", "-Xmx256m", "-jar", str(ROOT / "target/feezit-office-backend-0.1.0-SNAPSHOT.jar"), "--spring.profiles.active=test", f"--server.port={port}"], cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 40
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f"Office exited; see {log_path}")
                try:
                    csrf = request("GET", "/office-api/csrf", 200)
                    break
                except (urllib.error.URLError, ConnectionError):
                    if time.monotonic() > deadline:
                        raise RuntimeError(f"Office startup timeout; see {log_path}")
                    time.sleep(0.1)
            assert any(c.name == "SESSION" and "httponly" in {k.lower() for k in getattr(c, "_rest", {})} and any(k.lower() == "samesite" and str(v).lower() == "strict" for k, v in getattr(c, "_rest", {}).items()) for c in jar)
            request("GET", "/office-api/session", 401)
            form = urllib.parse.urlencode({"username": USERNAME, "password": PASSWORD}).encode()
            request("POST", "/office-api/auth/login", 403, form, content_type="application/x-www-form-urlencoded")
            before = next(c.value for c in jar if c.name == "SESSION")
            request("POST", "/office-api/auth/login", 204, form, csrf, "application/x-www-form-urlencoded")
            after = next(c.value for c in jar if c.name == "SESSION")
            assert after is not None and before != after
            assert request("GET", "/office-api/session", 200) == {"username": USERNAME}
            request("POST", "/office-api/auth/logout", 403, csrf=csrf)
            csrf = request("GET", "/office-api/csrf", 200)
            if args.feezit_url:
                for suffix in ("brands", "accounts?page=0&size=20", "promos?page=0&size=20", "incidents?page=0&size=20", "outbox", "email-deliveries", "waitlist?store=smoke-isolated", "waitlist/responses?store=smoke-isolated"):
                    request("GET", "/office-api/management/" + suffix, 200)
                if args.commands:
                    for command in json.loads(Path(args.commands).read_text()):
                        if not command["path"].startswith("/office-api/management/"):
                            raise ValueError("Only management command fixtures accepted")
                        request(command["method"], command["path"], command["expectedStatus"], command.get("body"), csrf)
            else:
                request("GET", "/office-api/management/brands", 503)
            request("GET", "/office-api/management/brands?url=forbidden", 400)
            request("POST", "/office-api/management/jobs/run", 403)
            request("POST", "/office-api/auth/logout", 204, csrf=csrf)
            request("GET", "/office-api/session", 401)
            stale = urllib.request.Request(base + "/office-api/session", headers={"Cookie": "SESSION=" + after})
            try:
                with urllib.request.urlopen(stale, timeout=5) as response:
                    response.read()
                raise AssertionError("Invalidated session accepted")
            except urllib.error.HTTPError as error:
                assert error.code == 401
                error.close()
                checks.append({"method": "GET", "path": "/office-api/session (stale cookie)", "status": 401})
            request("GET", "/office-api/csrf", 200)
            (logs / "checks.json").write_text(json.dumps(checks, indent=2) + "\n")
            print(json.dumps({"checks": len(checks), "logs": str(logs), "upstream": "isolated Feezit" if args.feezit_url else "disabled; business integration not exercised"}))
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--feezit-url")
    parser.add_argument("--isolated-feezit", action="store_true")
    parser.add_argument("--commands", help="JSON fixtures: method/path/body/expectedStatus")
    parser.add_argument("--log-dir")
    run(parser.parse_args())
