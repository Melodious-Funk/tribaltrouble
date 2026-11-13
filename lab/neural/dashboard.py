#!/usr/bin/env python3
"""Shows the training progress of the neural AI in a browser.

    python lab/neural/dashboard.py            # opens http://localhost:8642
    python lab/neural/dashboard.py --run first --port 9000 --no-browser

It reads `log.jsonl`, `status.json` and `args.json` of the training runs under aisim/neural/ (train.py writes
them), and the result rows of the batch a trainer is waiting for. The page (dashboard.html, next to this file) asks
this server for them every few seconds, so it follows a training run as it goes. From the page you can also start a
training run (it runs train.py, detached: it goes on when the dashboard stops), stop one (after its generation, by
its STOP file, or at once) and open a recorded game in the viewer (`./aisim.sh watch`). Standard library only.

The server listens on this machine only, and it starts programs only for a page it served itself (a token in the
page, and the Host header): another web page open in the same browser cannot use it.
"""

import argparse
import json
import os
import re
import secrets
import signal
import subprocess
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
# What the page must send back to start or stop anything: only a page this server served knows it.
TOKEN = secrets.token_urlsafe(24)
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,59}")
# The settings of train.py the page may set: its option (with _ for -), how to read it, and its range.
SETTINGS = {
    "generations": (int, 1, 1_000_000), "games": (int, 2, 100_000), "minutes": (int, 1, 600),
    "lr": (float, 1e-7, 1.0), "epochs": (int, 1, 100), "minibatch": (int, 16, 1_000_000),
    "entropy": (float, 0.0, 1.0), "shaping": (float, 0.0, 10.0), "self_share": (float, 0.0, 1.0),
    "stock_share": (float, 0.0, 1.0), "teacher_share": (float, 0.0, 1.0), "prior": (float, 0.0, 20.0),
    "timeout_result": (float, -2.0, 1.0), "win_speed": (float, 0.0, 5.0), "kill_reward": (float, 0.0, 1.0),
    "loss_penalty": (float, 0.0, 1.0), "raze_reward": (float, 0.0, 5.0), "razed_penalty": (float, 0.0, 5.0), "gamma": (float, 0.5, 1.0), "gae_lambda": (float, 0.0, 1.0),
    "clip": (float, 0.01, 1.0), "eval_generations": (int, 0, 1_000_000), "eval_games": (int, 2, 1000),
    "record_generations": (int, 0, 1_000_000), "snapshot_generations": (int, 0, 1_000_000),
    "ladder_score": (float, 0.0, 1.0), "seed": (int, 0, 2 ** 31 - 1),
}
TEXT_SETTINGS = {"hidden": re.compile(r"\d{1,5}(,\d{1,5}){0,7}"), "workers": re.compile(r"auto|\d{1,3}"),
                 "stock": re.compile(r"auto|(easy|normal|hard2?)(,(easy|normal|hard2?)){0,3}"),
                 "eval_stock": re.compile(r"(easy|normal|hard2?)(,(easy|normal|hard2?)){0,3}"),
                 "init_from": NAME}
FILTERS = ("all", "timeout", "decided")


class Refused(Exception):
    """What the page asked for cannot be done; its text is for the page."""


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
    batches = status.get("batches")
    if running and isinstance(batches, list):
        status["batch_done"] = 0
        for batch in batches:
            if isinstance(batch, str) and NAME.fullmatch(batch):
                try:
                    with open(RUNS / batch / "results.jsonl", "rb") as rows:
                        status["batch_done"] += sum(1 for _ in rows)
                except OSError:
                    pass
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


def training_now() -> str | None:
    """The training run whose trainer runs now, or None."""
    for entry in run_names():
        if status_of(NEURAL / entry["name"])["running"]:
            return entry["name"]
    return None


def child_environment() -> dict:
    """This process's environment, with a JDK 26 for aisim.sh where neither AISIM_JDK nor JAVA_HOME names one."""
    environment = dict(os.environ)
    if not environment.get("AISIM_JDK") and not environment.get("JAVA_HOME"):
        found = sorted((Path.home() / ".gradle" / "jdks").glob("*-26-*/bin/java"))  # the JDKs Gradle fetched
        if found:
            environment["AISIM_JDK"] = str(found[-1].parent.parent)
    return environment


def launch(command: list[str], log: Path, seconds: float) -> None:
    """Starts `command` detached, its output appended to `log`; refuses if it fails within `seconds`."""
    log.parent.mkdir(parents=True, exist_ok=True)
    start = log.stat().st_size if log.exists() else 0
    with open(log, "ab") as out:
        process = subprocess.Popen(command, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT,
                                   stdin=subprocess.DEVNULL, start_new_session=True, env=child_environment())
    try:
        status = process.wait(timeout=seconds)
    except subprocess.TimeoutExpired:
        return
    if status != 0:
        with open(log, "rb") as written:
            written.seek(start)
            said = written.read().decode(errors="replace").strip()
        raise Refused(f"it ended at once (exit code {status}): {said[-1500:] or 'it said nothing'}")


def start_training(body: dict) -> str:
    run = str(body.get("run", ""))
    if not NAME.fullmatch(run):
        raise Refused("a run's name is 1 to 60 letters, digits, - _ or .")
    busy = training_now()
    if busy is not None:
        raise Refused(f"run {busy} is training: stop it first (the trainers would share the processor)")
    if not (ROOT / "aisim" / "snap" / "latest").exists():
        raise Refused("no build yet: run ./aisim.sh build first")
    python = ROOT / "aisim" / "venv" / "bin" / "python"
    command = [str(python) if python.exists() else sys.executable, str(ROOT / "lab" / "neural" / "train.py"),
               "--run", run]
    for name, value in (body.get("settings") or {}).items():
        if value is None or value == "":
            continue  # train.py's own default
        if name in SETTINGS:
            kind, low, high = SETTINGS[name]
            try:
                number = kind(value)
            except (TypeError, ValueError):
                raise Refused(f"--{name.replace('_', '-')} needs a number, not {value!r}") from None
            if not low <= number <= high:
                raise Refused(f"--{name.replace('_', '-')} must be {low} to {high}")
            value = number
        elif name in TEXT_SETTINGS:
            if not TEXT_SETTINGS[name].fullmatch(str(value)):
                raise Refused(f"--{name} cannot be {value!r}")
        else:
            raise Refused(f"no setting {name}")
        command += ["--" + name.replace("_", "-"), str(value)]
    folder = NEURAL / run
    resumes = (folder / "latest.pt").exists()
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "STOP").unlink(missing_ok=True)  # an old request to stop would end the new session at once
    launch(command, folder / "train.out", 3.0)
    return f"{'resumed' if resumes else 'started'} {run}; its output is in aisim/neural/{run}/train.out"


def descendants(pid: int) -> list[int]:
    """The processes started by `pid`, and by those (Linux: from /proc); empty where that cannot be read."""
    children: dict[int, list[int]] = {}
    for stat in Path("/proc").glob("[0-9]*/stat"):
        try:
            fields = stat.read_text().rsplit(")", 1)[1].split()  # after the name, which may hold spaces
            children.setdefault(int(fields[1]), []).append(int(stat.parent.name))
        except (OSError, ValueError, IndexError):
            continue
    found, queue = [], [pid]
    while queue:
        for child in children.get(queue.pop(), []):
            found.append(child)
            queue.append(child)
    return found


def stop_training(body: dict) -> str:
    run = str(body.get("run", ""))
    folder = NEURAL / run
    if not NAME.fullmatch(run) or not folder.is_dir():
        raise Refused(f"no training run {run}")
    how = body.get("how")
    if how == "cancel":
        (folder / "STOP").unlink(missing_ok=True)
        return f"{run} goes on after this generation"
    status = status_of(folder)
    if not status["running"]:
        raise Refused(f"{run} is not training")
    if how == "after":
        (folder / "STOP").touch()
        return f"{run} stops when its generation is done"
    if how != "now":
        raise Refused("stop how: after, now or cancel")
    pid = status.get("pid")
    if os.name != "posix" or not isinstance(pid, int):
        raise Refused("stopping at once works on Linux and macOS only: stop it after its generation")
    command_line = Path(f"/proc/{pid}/cmdline")
    if command_line.exists() and b"train.py" not in command_line.read_bytes():
        raise Refused(f"process {pid} is not the trainer any more")
    # the trainer first (it marks itself stopped), then the harness batch and its worker JVMs it was waiting for
    started = descendants(pid)
    os.kill(pid, signal.SIGTERM)
    for child in started:
        try:
            os.kill(child, signal.SIGTERM)
        except OSError:
            pass
    return f"stopped {run}: the generation it was in is lost, and is played again when it resumes"


def recordings(training: str | None, every: bool) -> list[dict]:
    """The harness runs with results: all of them, or those of training run `training`; the newest first."""
    out = []
    if RUNS.is_dir():
        for folder in RUNS.iterdir():
            results = folder / "results.jsonl"
            if not results.is_file() or not (every or (training and folder.name.startswith(training + "-g"))):
                continue
            try:
                rows = results.read_text(errors="replace")
            except OSError:
                continue
            players = re.search(r'"players":"((?:[^"\\]|\\.)*)"', rows)
            out.append({"name": folder.name, "modified": results.stat().st_mtime,
                        "players": players.group(1) if players else "",
                        "games": sum(1 for line in rows.splitlines() if line.strip()),
                        "wins": rows.count('"result":"win"'), "losses": rows.count('"result":"loss"'),
                        "draws": rows.count('"result":"draw"'), "timeouts": rows.count('"end":"timeout"')})
    return sorted(out, key=lambda entry: -entry["modified"])


def games_of(run: str) -> list[dict]:
    """The games of harness run `run`, from its result rows."""
    folder = RUNS / run
    if not NAME.fullmatch(run) or not (folder / "results.jsonl").is_file():
        raise Refused(f"no run {run} in aisim/runs")
    games = []
    for line in (folder / "results.jsonl").read_text(errors="replace").splitlines():
        try:
            row = json.loads(line)
        except ValueError:
            continue
        teams = row.get("teams") or []
        games.append({"key": row.get("key"), "result": row.get("result") or "failed", "end": row.get("end") or "",
                      "seconds": row.get("t") or 0, "seed": row.get("seed") or 0, "side": row.get("side") or 0,
                      "units": " - ".join(str(team.get("units", "")) for team in teams),
                      "kills": " - ".join(str(team.get("kills", "")) for team in teams),
                      "file": (folder / "g" / f"{row.get('key')}.jsonl").is_file()})
    return sorted(games, key=lambda game: (game["seed"], game["side"]))


def watch(body: dict) -> str:
    run = str(body.get("run", ""))
    if not NAME.fullmatch(run) or not (RUNS / run / "run.json").is_file():
        raise Refused(f"no run {run} in aisim/runs")
    if os.name == "posix" and sys.platform != "darwin" and not (
            os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
        raise Refused("the dashboard was started without a desktop session: start it from a terminal on the desktop")
    command = ["bash", str(ROOT / "aisim.sh"), "watch", run]
    key = body.get("key")
    if key:
        if not NAME.fullmatch(str(key)):
            raise Refused(f"no game {key}")
        command.append(str(key))
    if body.get("filter") in FILTERS:
        command += ["--filter", body["filter"]]
    launch(command, ROOT / "aisim" / "watch" / "dashboard.log", 4.0)
    return f"opening {run}{' ' + str(key) if key else ''} in the viewer: its window comes in a few seconds"


def state(run: str | None, since: int) -> dict:
    runs = run_names()
    names = [entry["name"] for entry in runs]
    if run not in names:
        run = names[0] if names else None
        since = 0
    training = training_now()
    out = {"runs": runs, "run": run, "root": str(ROOT), "training": training,
           "training_stop": training is not None and (NEURAL / training / "STOP").exists()}
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
    hosts = ()

    def local(self) -> bool:
        """Whether the request names this server as its host: a page of another site, by any name, does not."""
        if self.headers.get("Host") in self.hosts:
            return True
        self.send_error(403, "not this dashboard's address")
        return False

    def do_GET(self):  # noqa: N802 (the base class's name)
        if not self.local():
            return
        url = urlparse(self.path)
        query = parse_qs(url.query)
        try:
            if url.path == "/":
                page = PAGE.read_text(encoding="utf-8").replace("__DASHBOARD_TOKEN__", TOKEN)
                self.send(page.encode(), "text/html; charset=utf-8")
            elif url.path == "/api/state":
                run = query.get("run", [self.default_run])[0]
                try:
                    since = max(0, int(query.get("since", ["0"])[0]))
                except ValueError:
                    since = 0
                self.send(json.dumps(state(run, since)).encode(), "application/json")
            elif url.path == "/api/recordings":
                found = recordings(query.get("training", [None])[0], query.get("all", ["0"])[0] == "1")
                self.send(json.dumps(found).encode(), "application/json")
            elif url.path == "/api/games":
                self.send(json.dumps(games_of(query.get("run", [""])[0])).encode(), "application/json")
            else:
                self.send_error(404)
        except Refused as e:
            self.send(json.dumps({"error": str(e)}).encode(), "application/json", 400)

    def do_POST(self):  # noqa: N802
        if not self.local():
            return
        actions = {"/api/start": start_training, "/api/stop": stop_training, "/api/watch": watch}
        action = actions.get(urlparse(self.path).path)
        if action is None:
            self.send_error(404)
            return
        if not secrets.compare_digest(self.headers.get("X-Dashboard-Token", ""), TOKEN):
            self.send_error(403, "open the dashboard's own page to do this")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(min(length, 65536)) or b"{}")
            if not isinstance(body, dict):
                raise ValueError
        except ValueError:
            self.send_error(400, "not a JSON object")
            return
        try:
            self.send(json.dumps({"ok": action(body)}).encode(), "application/json")
        except Refused as e:
            self.send(json.dumps({"error": str(e)}).encode(), "application/json", 400)
        except OSError as e:
            self.send(json.dumps({"error": f"{type(e).__name__}: {e}"}).encode(), "application/json", 500)

    def send(self, body: bytes, kind: str, status: int = 200) -> None:
        self.send_response(status)
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
    Handler.hosts = (f"localhost:{args.port}", f"127.0.0.1:{args.port}")
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
