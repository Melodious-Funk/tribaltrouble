#!/usr/bin/env python3
"""Shows the training progress of the neural AI in a browser.

    python lab/neural/dashboard.py            # opens http://localhost:8642
    python lab/neural/dashboard.py --run first --port 9000 --no-browser

It only reads: `log.jsonl`, `status.json` and `args.json` of the training runs under aisim/neural/ (train.py writes
them), and the result rows of the batch a trainer is waiting for. The page (dashboard.html, next to this file) asks
this server for them every few seconds, so it follows a training run as it goes. Standard library only.
"""

import argparse
import json
import os
import sys
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[2]
NEURAL = ROOT / "aisim" / "neural"
RUNS = ROOT / "aisim" / "runs"
PAGE = Path(__file__).resolve().with_name("dashboard.html")
# A trainer that has said nothing for this long (and whose process cannot be asked) counts as gone.
QUIET_SECONDS = 1800


def read_json(file: Path):
    try:
        return json.loads(file.read_text())
    except (OSError, ValueError):
        return None


def read_log(file: Path, since: int) -> list[dict]:
    """The log's lines from line `since` on; a torn last line (being written) is left for the next call."""
    try:
        lines = file.read_text().splitlines()
    except OSError:
        return []
    out = []
    for line in lines[since:]:
        try:
            out.append(json.loads(line))
        except ValueError:
            break
    return out


def alive(pid) -> bool | None:
    """Whether process `pid` runs; None where that cannot be asked without harm (signal 0 kills on Windows)."""
    if os.name != "posix" or not isinstance(pid, int):
        return None
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def run_names() -> list[dict]:
    """The training runs, the one written to last first."""
    runs = []
    if NEURAL.is_dir():
        for folder in NEURAL.iterdir():
            log = folder / "log.jsonl"
            status = folder / "status.json"
            times = [file.stat().st_mtime for file in (log, status) if file.exists()]
            if times:
                runs.append({"name": folder.name, "modified": max(times)})
    return sorted(runs, key=lambda run: -run["modified"])


def status_of(folder: Path) -> dict:
    """What the run's trainer does now: its status file, whether it still runs, and how far its batch is."""
    status = read_json(folder / "status.json") or {}
    now = time.time()
    running = False
    if status and status.get("phase") != "stopped":
        asked = alive(status.get("pid"))
        running = asked if asked is not None else now - status.get("time", 0) < QUIET_SECONDS
    status["running"] = bool(running)
    status["now"] = now
    status["stop_requested"] = (folder / "STOP").exists()
    batch = status.get("batch")
    if running and isinstance(batch, str) and "/" not in batch and "\\" not in batch:
        try:
            with open(RUNS / batch / "results.jsonl", "rb") as rows:
                status["batch_done"] = sum(1 for _ in rows)
        except OSError:
            status["batch_done"] = 0
    return status


def kept_games(run: str) -> list[dict]:
    """The harness runs of this training run whose folders were kept: the games there are to watch."""
    kept = []
    if RUNS.is_dir():
        for folder in RUNS.iterdir():
            results = folder / "results.jsonl"
            if folder.name.startswith(run + "-g") and results.exists():
                with open(results, "rb") as rows:
                    kept.append({"name": folder.name, "games": sum(1 for _ in rows),
                                 "modified": results.stat().st_mtime})
    return sorted(kept, key=lambda entry: entry["name"])


def state(run: str | None, since: int) -> dict:
    runs = run_names()
    names = [entry["name"] for entry in runs]
    if run not in names:
        run = names[0] if names else None
        since = 0
    out = {"runs": runs, "run": run, "root": str(ROOT)}
    if run is not None:
        folder = NEURAL / run
        out["log"] = read_log(folder / "log.jsonl", since)
        out["since"] = since
        out["status"] = status_of(folder)
        out["args"] = read_json(folder / "args.json")
        out["nets"] = len(list((folder / "nets").glob("g*.bin"))) if (folder / "nets").is_dir() else 0
        out["kept"] = kept_games(run)
    return out


class Handler(BaseHTTPRequestHandler):
    default_run = None

    def do_GET(self):  # noqa: N802 (the base class's name)
        url = urlparse(self.path)
        if url.path == "/":
            self.send(PAGE.read_bytes(), "text/html; charset=utf-8")
        elif url.path == "/api/state":
            query = parse_qs(url.query)
            run = query.get("run", [self.default_run])[0]
            try:
                since = max(0, int(query.get("since", ["0"])[0]))
            except ValueError:
                since = 0
            self.send(json.dumps(state(run, since)).encode(), "application/json")
        else:
            self.send_error(404)

    def send(self, body: bytes, kind: str) -> None:
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):  # a request every few seconds: nothing worth a line
        pass


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", help="the training run to show first (default: the one written to last)")
    p.add_argument("--port", type=int, default=8642)
    p.add_argument("--no-browser", action="store_true", help="do not open the page in a browser")
    args = p.parse_args()
    Handler.default_run = args.run
    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)  # this machine only
    except OSError as e:
        sys.exit(f"cannot listen on port {args.port} ({e}): is a dashboard running already? try --port")
    url = f"http://localhost:{args.port}/"
    print(f"training dashboard at {url} (Ctrl+C stops it); runs are read from {NEURAL}")
    if not args.no_browser:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
