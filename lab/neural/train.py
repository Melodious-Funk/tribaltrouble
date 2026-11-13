#!/usr/bin/env python3
"""Trains the neural AI by self-play, generation after generation.

A generation: export the current net, play batches of harness games with it (against itself, against the scripted
teacher it starts from, against earlier nets of this run, against a stock AI), read the trajectories the AI wrote,
and improve the net with PPO. Every game is 1 vs 1 on `--size medium --terrain tropical --hills 0 --trees 10
--supplies 10`.

The net does not start from nothing: the AI adds `--prior` to the logit of every choice its scripted teacher advises
(Teacher.java), so generation 0 plays the teacher's plan with a little noise. The reward is made for decided games:
a game that reaches the time limit counts as `--timeout-result` for both sides (a loss by default), a win counts more
the earlier it comes, and kills and razed buildings pay on the way.

    python lab/neural/train.py --run first --generations 200

Games are played by one `./aisim.sh serve` for the whole session, whose worker JVMs stay warm from generation to
generation, and a generation's batches are sent together, so they share the workers rather than wait for one another.
Training maps come from the fixed pool of fair maps in lab/neural/maps.json (lab/neural/fairmaps.py chose them), and
evaluation plays its held-out maps. A new run can start from an earlier run's net of another size (`--init-from`):
the new net first learns to choose as that net does, on games that net plays.

Run it from the repository root, after `./aisim.sh build`, with AISIM_JDK pointing at a JDK 26. State is kept under
aisim/neural/RUN/ (git-ignored): `latest.pt` to resume from, `nets/` the exported nets, `log.jsonl` a line per
generation, `status.json` what it is doing now and `args.json` its settings. Stop it with Ctrl+C or by creating
aisim/neural/RUN/STOP; start it again to go on. `python lab/neural/dashboard.py` shows its progress in a browser.
lab/neural/NOTES.md explains the choices made here.
"""

import argparse
import json
import math
import os
import random
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ttnet import Policy, read_trajectory  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
MAP = ["--size", "medium", "--terrain", "tropical", "--hills", "0", "--trees", "10", "--supplies", "10"]
# The stock opponents by name, and the --players of their team: hard2 is two allied Hard AIs (1 vs 2).
STOCK = {"easy": "easy", "normal": "normal", "hard": "hard", "hard2": "hard*2"}
AUTO_LADDER = ["easy", "normal", "hard"]
# Training maps are seeds 0..TRAIN_SEEDS-1; evaluation uses the seeds from EVAL_SEED on, which training never plays.
TRAIN_SEEDS = 38000
EVAL_SEED = 39000
# The AI of this build (Observation.java, Heads.java); every trajectory read is checked against these.
OBSERVATION_SIZE = 136
HEADS = [
    ("Build", ["nothing", "quarters", "armory", "tower"]),
    ("Peons kept in each quarters", ["1", "4", "10", "18", "30"]),
    ("Peons kept at the forge", ["2", "6", "12", "20", "32"]),
    ("Wood gatherers", ["2 fewer", "same", "2 more"]),
    ("Rock gatherers", ["2 fewer", "same", "2 more"]),
    ("Iron gatherers", ["2 fewer", "same", "2 more"]),
    ("Rubber gatherers", ["2 fewer", "same", "2 more"]),
    ("Weapons made", ["iron", "iron and rubber", "rubber", "all three"]),
    ("Army", ["defend", "attack", "pull back"]),
    ("Chieftain", ["no", "train"]),
]
HEAD_SIZES = [len(choices) for _, choices in HEADS]
# Outcome numbers (Observation.outcome): own units, warriors, buildings, kills, then the enemies' (their kills are
# our losses), then the buildings we razed and lost.
SHAPING_SCALE = np.array([250, 100, 10, 250], dtype=np.float32)
SHAPING_WEIGHT = np.array([0.2, 0.4, 0.2, 0.2], dtype=np.float32)
KILLS, LOSSES, RAZED, RAZED_OURS = 3, 7, 8, 9


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", required=True, help="the training run's name: its folder is aisim/neural/RUN")
    p.add_argument("--generations", type=int, default=100, help="generations to play in this session")
    p.add_argument("--games", type=int, default=120,
                   help="games per generation (a map is a game per start: two, three against hard2)")
    p.add_argument("--minutes", type=int, default=30, help="the time limit of a training game, in game minutes")
    p.add_argument("--workers", default="auto", help="serve's --workers: the pool of worker JVMs")
    p.add_argument("--java-opts", default="", help="extra JVM options of the workers (AISIM_JAVA_OPTS)")
    p.add_argument("--maps", type=Path, default=ROOT / "lab" / "neural" / "maps.json",
                   help="the pool of maps (fairmaps.py): training plays its train maps, evaluation its eval maps; "
                        "without the file, training plays any seed below 38000 and evaluation seeds from 39000")
    p.add_argument("--record-generations", type=int, default=100,
                   help="keep the run folders and full game recordings of every Nth generation only")
    p.add_argument("--snapshot-generations", type=int, default=10,
                   help="every Nth generation's net joins the league of past opponents")
    p.add_argument("--eval-generations", type=int, default=10,
                   help="every Nth generation, measure the net (choosing greedily) against the stock AIs")
    p.add_argument("--eval-games", type=int, default=40,
                   help="games per opponent in an evaluation (two per map, at most two per eval map of the pool)")
    p.add_argument("--hidden", default="256,256,256", help="hidden layer sizes of a new net")
    p.add_argument("--init-from", default=None,
                   help="a new run only: an earlier run whose net (its latest.pt) the new one first learns to imitate")
    p.add_argument("--init-games", type=int, default=240, help="games that net plays for --init-from")
    p.add_argument("--init-epochs", type=int, default=40, help="passes over those decisions")
    p.add_argument("--lr", type=float, default=3e-4)
    p.add_argument("--gamma", type=float, default=0.997, help="discount per decision (5 game seconds)")
    p.add_argument("--gae-lambda", type=float, default=0.95)
    p.add_argument("--clip", type=float, default=0.2)
    p.add_argument("--epochs", type=int, default=4, help="passes over a generation's decisions")
    p.add_argument("--minibatch", type=int, default=1024)
    p.add_argument("--entropy", type=float, default=0.003,
                   help="the entropy bonus: it also pulls the policy away from the teacher's advice")
    p.add_argument("--value-coefficient", type=float, default=0.5)
    p.add_argument("--prior", type=float, default=4.5,
                   help="what a new net adds to the logit of each choice the scripted teacher advises (0: no teacher)")
    p.add_argument("--timeout-result", type=float, default=-1.0,
                   help="what a game that reaches the time limit counts as, for both sides (a loss is -1, a win 1)")
    p.add_argument("--win-speed", type=float, default=0.5,
                   help="what a win at the first second adds to its 1; a win at the time limit adds nothing")
    p.add_argument("--kill-reward", type=float, default=0.002, help="reward per enemy unit killed")
    p.add_argument("--loss-penalty", type=float, default=0.001, help="penalty per unit lost: below --kill-reward, so "
                   "an even fight pays")
    p.add_argument("--raze-reward", type=float, default=0.05, help="reward per enemy building destroyed")
    p.add_argument("--razed-penalty", type=float, default=0.03, help="penalty per building lost")
    p.add_argument("--shaping", type=float, default=0.2,
                   help="weight of the shaped reward (the change of the strength margin), which sums to nothing over "
                        "a game")
    p.add_argument("--self-share", type=float, default=0.3, help="share of a generation played against itself")
    p.add_argument("--teacher-share", type=float, default=0.2,
                   help="share played against the scripted teacher alone (the plan the net starts from)")
    p.add_argument("--stock-share", type=float, default=0.2, help="share played against a stock AI")
    p.add_argument("--stock", default="hard",
                   help="the stock AI of the training games (easy, normal, hard, hard2: two allied Hards), or a "
                        "ladder of them, comma-separated: the first whose running score is below --ladder-score; "
                        "auto: easy,normal,hard")
    p.add_argument("--ladder-score", type=float, default=0.9,
                   help="the running score (wins 1, draws 0.5) against a stock AI of the ladder that moves on")
    p.add_argument("--eval-stock", default="hard",
                   help="the stock AIs an evaluation plays, comma-separated (easy, normal, hard, hard2), besides the "
                        "teacher")
    p.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    p.add_argument("--seed", type=int, default=1)
    return p.parse_args()


def seats(players: str) -> int:
    """The number of players of a --players text: a map is as many games."""
    return sum(int(token.split("*")[1]) if "*" in token else 1 for token in players.split() if token != "vs")


def read_maps(file: Path) -> tuple[list[int], list[int]]:
    """The training and evaluation maps of the pool file; none without the file."""
    if not file.exists():
        print(f"note: no map pool {file}: training plays any seed below {TRAIN_SEEDS}")
        return [], []
    pool = json.loads(file.read_text())
    if set(pool["train"]) & set(pool["eval"]):
        sys.exit(f"{file}: a map is both a training and an evaluation map")
    return list(pool["train"]), list(pool["eval"])


class Harness:
    """One `./aisim.sh serve` for the session: its worker JVMs stay warm from batch to batch."""

    def __init__(self, workers: str, java_opts: str, log: Path):
        env = dict(os.environ)
        if java_opts:
            env["AISIM_JAVA_OPTS"] = java_opts
        self.log = log
        self.log_file = open(log, "a")
        self.process = subprocess.Popen(["bash", str(ROOT / "aisim.sh"), "serve", "--workers", workers], cwd=ROOT,
                                        env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log_file,
                                        text=True)
        self.next_id = 0

    def play(self, requests: list[tuple[str, list[str]]]) -> None:
        """Sends batch requests (name, options) at once and returns when every one is played."""
        waiting = {}
        for name, options in requests:
            self.next_id += 1
            waiting[str(self.next_id)] = name
            self.process.stdin.write(json.dumps({"id": str(self.next_id), "args": options}) + "\n")
        self.process.stdin.flush()
        while waiting:
            line = self.process.stdout.readline()
            if not line:
                tail = self.log.read_text()[-3000:] if self.log.exists() else ""
                raise RuntimeError(f"aisim serve ended (exit {self.process.poll()}); its log {self.log} ends:\n{tail}")
            if not line.startswith("@@"):
                continue  # the JVM's own messages
            answer = json.loads(line[2:])
            name = waiting.pop(answer["id"], None)
            if "error" in answer:
                raise RuntimeError(f"aisim serve refused {name}: {answer['error']}")

    def close(self) -> None:
        """Lets serve end after what it plays; kills it if it does not end within a minute."""
        try:
            self.process.stdin.close()
            self.process.wait(60)
        except (OSError, subprocess.TimeoutExpired):
            self.process.kill()
        self.log_file.close()

    def kill(self) -> None:
        """Stops serve at once; its workers die with it."""
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(30)
            except subprocess.TimeoutExpired:
                self.process.kill()
        self.log_file.close()


class Trainer:
    def __init__(self, args):
        self.args = args
        self.dir = ROOT / "aisim" / "neural" / args.run
        self.nets = self.dir / "nets"
        self.nets.mkdir(parents=True, exist_ok=True)
        self.device = torch.device(args.device)
        self.generation = 0
        self.league = []  # {"generation", "games", "wins"}: past nets, and how the current one did against each
        self.stock_score = {name: 0.0 for name in STOCK}  # running score against each stock AI
        hidden = tuple(int(n) for n in args.hidden.split(","))
        self.policy = Policy(OBSERVATION_SIZE, HEAD_SIZES, hidden, args.prior).to(self.device)
        self.optimizer = torch.optim.Adam(self.policy.parameters(), lr=args.lr, eps=1e-5)
        self.random = random.Random(args.seed)
        checkpoint = self.dir / "latest.pt"
        if checkpoint.exists():
            state = torch.load(checkpoint, map_location=self.device, weights_only=False)
            if state.get("observation_size") != OBSERVATION_SIZE or state.get("head_sizes") != HEAD_SIZES:
                sys.exit(f"{args.run} was trained for another build of the AI (its observation or heads differ): "
                         "start a run under a new name")
            self.policy = Policy(OBSERVATION_SIZE, HEAD_SIZES, tuple(state["hidden"]), state["prior"]).to(self.device)
            self.policy.load_state_dict(state["policy"])
            self.optimizer = torch.optim.Adam(self.policy.parameters(), lr=args.lr, eps=1e-5)
            self.optimizer.load_state_dict(state["optimizer"])
            self.generation = state["generation"]
            self.league = state["league"]
            self.stock_score = {**self.stock_score, **state["stock_score"]}
            self.random.setstate(state["random"])
            print(f"resuming {args.run} at generation {self.generation}")
        self.train_maps, self.eval_maps = read_maps(args.maps)
        self.session_start = time.time()
        self.session_first = self.generation
        described = {**vars(args), "prior": self.policy.prior,
                     "heads": [{"name": name, "choices": choices} for name, choices in HEADS]}
        (self.dir / "args.json").write_text(json.dumps(described, indent=1, default=str))
        self.harness = Harness(args.workers, args.java_opts, self.dir / "serve.log")  # last: nothing fails after it

    def status(self, phase: str, **more) -> None:
        """Writes what the trainer does now, for the dashboard: the phase, and the batch it waits for."""
        status = {"pid": os.getpid(), "phase": phase, "generation": self.generation, "time": time.time(),
                  "session_start": self.session_start, "session_first": self.session_first,
                  "session_generations": self.args.generations, **more}
        temporary = self.dir / "status.tmp"
        temporary.write_text(json.dumps(status))
        temporary.replace(self.dir / "status.json")

    # ------------------------------------------------------------ the harness

    def net_file(self, generation: int) -> Path:
        return self.nets / f"g{generation:06d}.bin"

    def spec(self, generation: int | Path, trajectories: Path | None, seed: int, greedy: bool = False) -> str:
        net = generation if isinstance(generation, Path) else self.net_file(generation)
        params = [f"net={net.relative_to(ROOT).as_posix()}", f"seed={seed}"]
        if trajectories is not None:
            params.append(f"traj={trajectories.relative_to(ROOT).as_posix()}")
        if greedy:
            params.append("greedy=1")
        return "neural:" + ",".join(params)

    def batches(self, batches: list[tuple[str, str, list[int]]], record: bool, minutes: int) -> dict[str, list[dict]]:
        """Plays the batches (name, --players, maps) at once, each map from every start; returns their result rows."""
        requests = []
        for name, players, seeds in batches:
            shutil.rmtree(ROOT / "aisim" / "runs" / name, ignore_errors=True)
            requests.append((name, ["--name", name, "--players", players, "--seeds", ",".join(map(str, seeds)),
                                    "--rng", str(self.generation), "--minutes", str(minutes),
                                    "--record-every", "1" if record else "1000000", *MAP]))
        evaluating = any("-eval-" in name for name, _, _ in batches)
        self.status("evaluating" if evaluating else "playing", batches=[name for name, _, _ in batches],
                    games=sum(seats(players) * len(seeds) for _, players, seeds in batches), players=batches[0][1])
        self.harness.play(requests)
        rows = {}
        for name, _, _ in batches:
            run_dir = ROOT / "aisim" / "runs" / name
            results = run_dir / "results.jsonl"
            if not results.exists():
                raise RuntimeError(f"the harness played nothing for {name}: see {self.dir / 'serve.log'}")
            rows[name] = [json.loads(line) for line in results.read_text().splitlines()]
            if not record:
                shutil.rmtree(run_dir, ignore_errors=True)
        return rows

    def training_maps(self, count: int) -> list[int]:
        """`count` different training maps, at random: from the pool, or any seed below TRAIN_SEEDS without one."""
        if self.train_maps:
            return sorted(self.random.sample(self.train_maps, min(count, len(self.train_maps))))
        first = self.random.randrange(TRAIN_SEEDS - count)
        return list(range(first, first + count))

    def lineups(self, trajectories: Path) -> list[tuple[str, str, int]]:
        """This generation's batches: (kind, --players, maps)."""
        g = self.generation
        maps = max(4, self.args.games // 2)
        stock_maps = round(maps * self.args.stock_share)
        teacher_maps = round(maps * self.args.teacher_share)
        self_maps = max(1, round(maps * self.args.self_share))
        past_maps = max(0, maps - self_maps - stock_maps - teacher_maps) if self.league else 0
        if not self.league:
            self_maps = max(1, maps - stock_maps - teacher_maps)
        me = self.spec(g, trajectories, g)
        lineups = [("self", f"{me} vs {self.spec(g, trajectories, g + 500_000)}", self_maps)]
        if teacher_maps > 0:
            # no net and the most likely choice: the teacher's advice and nothing else
            lineups.append(("teacher", f"{me} vs neural:greedy=1", teacher_maps))
        if stock_maps > 0:
            ladder = AUTO_LADDER if self.args.stock == "auto" else self.args.stock.split(",")
            open_stock = [name for name in ladder if self.stock_score.get(name, 0.0) < self.args.ladder_score]
            stock = open_stock[0] if open_stock else ladder[-1]
            lineups.append((stock, f"{me} vs {STOCK[stock]}", stock_maps))
        if past_maps > 0:
            # earlier nets it still loses to are met more often
            weights = [(1 - entry["wins"] / max(1, entry["games"])) ** 2 + 0.05 for entry in self.league]
            entry = self.random.choices(self.league, weights)[0]
            lineups.append((f"g{entry['generation']}", f"{me} vs {self.spec(entry['generation'], None, g)}", past_maps))
        return lineups

    # ------------------------------------------------------------ one generation

    @staticmethod
    def tally(rows: list[dict]) -> dict:
        """How team A did in the counted games `rows`: games, wins, losses, draws, and those at the time limit."""
        return {"games": len(rows), "wins": sum(row["result"] == "win" for row in rows),
                "losses": sum(row["result"] == "loss" for row in rows),
                "draws": sum(row["result"] == "draw" for row in rows),
                "timeouts": sum(row.get("end") == "timeout" for row in rows),
                "minutes": round(sum(row["t"] for row in rows) / max(1, len(rows)) / 60, 1)}

    def play(self, record: bool) -> tuple[list[dict], dict, dict]:
        """Plays the generation's games; returns its trajectories with their results, and per-kind scores and tallies."""
        g = self.generation
        trajectories = self.dir / "traj" / f"g{g:06d}"
        shutil.rmtree(trajectories, ignore_errors=True)
        trajectories.mkdir(parents=True)  # the AI writes only into a folder that is there
        games = []
        scores = {}
        results = {}
        lineups = [(kind, f"{self.args.run}-g{g:06d}-{kind}", players, self.training_maps(maps))
                   for kind, players, maps in self.lineups(trajectories)]
        played = self.batches([(name, players, seeds) for _, name, players, seeds in lineups], record,
                              self.args.minutes)
        for kind, name, _, _ in lineups:
            rows = played[name]
            counted = [row for row in rows if row.get("result") is not None]
            if counted:
                scores[kind] = sum(row["score"] for row in counted) / len(counted)
                results[kind] = self.tally(counted)
            for row in counted:
                for team in row["teams"]:
                    for slot in team["slots"]:
                        file = trajectories / f"{row['key']}-s{slot}.traj"
                        trajectory = read_trajectory(file) if file.exists() else None
                        if trajectory is not None:
                            self.check(trajectory, file)
                            trajectory["result"] = self.result(row, team)
                            trajectory["length"] = row["t"]
                            trajectory["kind"] = kind
                            games.append(trajectory)
            self.note(kind, counted)
        shutil.rmtree(trajectories, ignore_errors=True)
        return games, scores, results

    @staticmethod
    def check(trajectory: dict, file: Path) -> None:
        if trajectory["head_sizes"] != HEAD_SIZES or trajectory["observation"].shape[1] != OBSERVATION_SIZE:
            sys.exit(f"{file} is from another build of the AI: {trajectory['observation'].shape[1]} observations and "
                     f"heads {trajectory['head_sizes']}, train.py expects {OBSERVATION_SIZE} and {HEAD_SIZES}")

    def result(self, row: dict, team: dict) -> float:
        """What the game counts as for `team`: about 1 for a win, -1 for a loss, and the time limit as set."""
        if row.get("end") == "timeout":
            return self.args.timeout_result  # whoever was ahead: nobody finished it
        result = 2 * team["score"] - 1  # 1 for first place alone, -1 for last, 0 for a shared one
        if result > 0:
            limit = 60.0 * self.args.minutes
            result += self.args.win_speed * max(0.0, 1.0 - row["t"] / limit)
        return result

    def note(self, kind: str, rows: list[dict]) -> None:
        """Keeps the running scores that choose later opponents."""
        if not rows:
            return
        score = sum(row["score"] for row in rows) / len(rows)
        if kind in self.stock_score:
            self.stock_score[kind] = 0.7 * self.stock_score[kind] + 0.3 * score
        for entry in self.league:
            if kind == f"g{entry['generation']}":
                entry["games"] = 0.8 * entry["games"] + len(rows)
                entry["wins"] = 0.8 * entry["wins"] + sum(row["score"] for row in rows)

    def advantages(self, games: list[dict]) -> dict:
        """Rewards, advantages and returns for every decision of the generation, as tensors."""
        args = self.args
        columns = {name: [] for name in ("observation", "action", "allowed", "advantage", "target", "java_log_p")}
        with torch.no_grad():
            for game in games:
                observation = torch.from_numpy(game["observation"]).to(self.device)
                value = self.policy(observation)[1].cpu().numpy()
                outcome = game["outcome"]
                # the strength margin: own minus enemy units, warriors, buildings and kills, scaled
                margin = ((outcome[:, :4] - outcome[:, 4:8]) / SHAPING_SCALE * SHAPING_WEIGHT).sum(1)
                steps = len(value)
                reward = np.zeros(steps, dtype=np.float32)
                reward[:-1] = args.shaping * (args.gamma * margin[1:] - margin[:-1])
                # damage pays where it is done and is never taken back: what happened until the next decision
                damage = (args.kill_reward * outcome[:, KILLS] - args.loss_penalty * outcome[:, LOSSES]
                          + args.raze_reward * outcome[:, RAZED] - args.razed_penalty * outcome[:, RAZED_OURS])
                reward[:-1] += damage[1:] - damage[:-1]
                game["damage"] = float(damage[-1] - damage[0])
                reward[-1] = game["result"] - args.shaping * margin[-1]  # the game ends: the margin is spent
                advantage = np.zeros(steps, dtype=np.float32)
                next_value, running = 0.0, 0.0  # nothing after the last decision
                for t in reversed(range(steps)):
                    delta = reward[t] + args.gamma * next_value - value[t]
                    running = delta + args.gamma * args.gae_lambda * running
                    advantage[t] = running
                    next_value = value[t]
                columns["observation"].append(game["observation"])
                columns["action"].append(game["action"])
                columns["allowed"].append(game["allowed"])
                columns["advantage"].append(advantage)
                columns["target"].append(advantage + value)
                columns["java_log_p"].append(game["log_probability"])
        return {name: torch.from_numpy(np.concatenate(parts)).to(self.device) for name, parts in columns.items()}

    def improve(self, data: dict) -> dict:
        """PPO on the generation's decisions."""
        args = self.args
        with torch.no_grad():
            old_log_p, _, _ = self.policy.evaluate(data["observation"], data["action"], data["allowed"])
        # the Java net and this one must agree on what was chosen and how likely it was
        parity = (old_log_p - data["java_log_p"]).abs().max().item()
        advantage = (data["advantage"] - data["advantage"].mean()) / (data["advantage"].std() + 1e-8)
        count = old_log_p.shape[0]
        stats = {"policy_loss": 0.0, "value_loss": 0.0, "entropy": 0.0, "kl": 0.0, "clipped": 0.0}
        updates = 0
        for _ in range(args.epochs):
            order = torch.randperm(count, device=self.device)
            for start in range(0, count, args.minibatch):
                rows = order[start:start + args.minibatch]
                log_p, entropy, value = self.policy.evaluate(data["observation"][rows], data["action"][rows],
                                                             data["allowed"][rows])
                ratio = (log_p - old_log_p[rows]).exp()
                clipped = ratio.clamp(1 - args.clip, 1 + args.clip)
                policy_loss = -torch.min(ratio * advantage[rows], clipped * advantage[rows]).mean()
                value_loss = 0.5 * (value - data["target"][rows]).pow(2).mean()
                loss = policy_loss + args.value_coefficient * value_loss - args.entropy * entropy.mean()
                self.optimizer.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(self.policy.parameters(), 0.5)
                self.optimizer.step()
                stats["policy_loss"] += policy_loss.item()
                stats["value_loss"] += value_loss.item()
                stats["entropy"] += entropy.mean().item()
                stats["kl"] += (old_log_p[rows] - log_p).mean().item()
                stats["clipped"] += ((ratio - 1).abs() > args.clip).float().mean().item()
                updates += 1
        stats = {name: round(total / max(1, updates), 4) for name, total in stats.items()}
        stats["parity"] = round(parity, 6)
        return stats

    def evaluate(self) -> tuple[dict, dict]:
        """The current net, choosing greedily, against the stock AIs of --eval-stock and the teacher, on maps training
        never plays, with the time limit of the training games."""
        scores = {}
        results = {}
        maps = max(1, self.args.eval_games // 2)
        seeds = self.eval_maps[:maps] if self.eval_maps else list(range(EVAL_SEED, EVAL_SEED + maps))
        stock = [name for name in self.args.eval_stock.split(",") if name]
        me = self.spec(self.generation, None, 0, greedy=True)
        batches = [(f"{self.args.run}-eval-{name}", f"{me} vs {STOCK.get(name, 'neural:greedy=1')}", seeds)
                   for name in [*stock, "teacher"]]
        played = self.batches(batches, False, self.args.minutes)
        for name in [*stock, "teacher"]:
            rows = played[f"{self.args.run}-eval-{name}"]
            counted = [row for row in rows if row.get("result") is not None]
            scores[name] = round(sum(row["score"] for row in counted) / max(1, len(counted)), 3)
            results[name] = self.tally(counted)
        return scores, results

    def save(self) -> None:
        state = {"policy": self.policy.state_dict(), "optimizer": self.optimizer.state_dict(),
                 "hidden": [layer.out_features for layer in self.policy.hidden], "prior": self.policy.prior,
                 "observation_size": OBSERVATION_SIZE, "head_sizes": HEAD_SIZES, "generation": self.generation,
                 "league": self.league, "stock_score": self.stock_score, "random": self.random.getstate()}
        temporary = self.dir / "latest.tmp"
        torch.save(state, temporary)
        temporary.replace(self.dir / "latest.pt")

    def initialize(self, source_run: str) -> None:
        """Teaches the new net to choose as `source_run`'s net does (its policy and value), on games that net plays."""
        args = self.args
        state = torch.load(ROOT / "aisim" / "neural" / source_run / "latest.pt", map_location=self.device,
                           weights_only=False)
        if state.get("observation_size") != OBSERVATION_SIZE or state.get("head_sizes") != HEAD_SIZES:
            sys.exit(f"--init-from {source_run}: its net is for another build of the AI")
        source = Policy(OBSERVATION_SIZE, HEAD_SIZES, tuple(state["hidden"]), state["prior"]).to(self.device)
        source.load_state_dict(state["policy"])
        source.eval()
        net = self.nets / "init.bin"
        source.export(net)
        trajectories = self.dir / "traj" / "init"
        shutil.rmtree(trajectories, ignore_errors=True)
        trajectories.mkdir(parents=True)
        maps = max(4, args.init_games // 2)
        me = self.spec(net, trajectories, 1)
        kinds = [("self", f"{me} vs {self.spec(net, trajectories, 500_001)}", maps // 2),
                 ("hard", f"{me} vs hard", maps // 4), ("teacher", f"{me} vs neural:greedy=1", maps - maps // 2 - maps // 4)]
        batches = [(f"{args.run}-init-{kind}", players, self.training_maps(count)) for kind, players, count in kinds]
        print(f"--init-from {source_run}: its net plays {2 * maps} games")
        self.batches(batches, False, args.minutes)
        games = [read_trajectory(file) for file in sorted(trajectories.glob("*.traj"))]
        games = [game for game in games if game is not None]
        shutil.rmtree(trajectories, ignore_errors=True)
        observation = torch.from_numpy(np.concatenate([game["observation"] for game in games])).to(self.device)
        allowed = torch.from_numpy(np.concatenate([game["allowed"] for game in games])).to(self.device)
        with torch.no_grad():
            source_logits, source_value = source(observation)
            target = [torch.log_softmax(part, -1) for part in self.head_logits(source_logits, allowed)]
        self.status("initializing")
        optimizer = torch.optim.Adam(self.policy.parameters(), lr=1e-3)
        count = observation.shape[0]
        for epoch in range(args.init_epochs):
            order = torch.randperm(count, device=self.device)
            for start in range(0, count, args.minibatch):
                rows = order[start:start + args.minibatch]
                logits, value = self.policy(observation[rows])
                kl = sum((part.exp() * (part - mine)).sum(-1).mean() for part, mine in
                         zip((t[rows] for t in target), (torch.log_softmax(h, -1) for h in
                                                          self.head_logits(logits, allowed[rows]))))
                loss = kl + 0.5 * (value - source_value[rows]).pow(2).mean()
                optimizer.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(self.policy.parameters(), 0.5)
                optimizer.step()
        with torch.no_grad():
            logits, value = self.policy(observation)
            mine = [torch.log_softmax(part, -1) for part in self.head_logits(logits, allowed)]
            kl = [float((t.exp() * (t - m)).sum(-1).mean()) for t, m in zip(target, mine)]
            agree = [float((t.argmax(-1) == m.argmax(-1)).float().mean()) for t, m in zip(target, mine)]
            value_error = float((value - source_value).pow(2).mean().sqrt())
        report = {"from": source_run, "source_generation": state["generation"], "source_hidden": state["hidden"],
                  "decisions": count, "epochs": args.init_epochs, "kl": [round(x, 5) for x in kl],
                  "agree": [round(x, 4) for x in agree], "value_rmse": round(value_error, 4)}
        (self.dir / "init.json").write_text(json.dumps(report, indent=1) + "\n")
        print(f"--init-from {source_run}: {count} decisions, KL per head {report['kl']}, most likely choice the "
              f"same {report['agree']}, value error {report['value_rmse']}")

    def head_logits(self, logits: torch.Tensor, allowed: torch.Tensor) -> list[torch.Tensor]:
        """Each head's logits, the choices it does not allow far below the rest (as Net.choose leaves them out)."""
        parts = []
        offset = 0
        for size in HEAD_SIZES:
            parts.append(logits[:, offset:offset + size].masked_fill(~allowed[:, offset:offset + size], -1e9))
            offset += size
        return parts

    def run(self) -> None:
        args = self.args
        if args.init_from:
            if not (self.dir / "latest.pt").exists():
                self.initialize(args.init_from)
                self.save()  # a run stopped in generation 0 resumes from the imitating net
            else:
                print(f"note: {args.run} has begun already, so --init-from is ignored")
        for _ in range(args.generations):
            if (self.dir / "STOP").exists():
                print("STOP file found")
                break
            g = self.generation
            started = time.time()
            self.policy.export(self.net_file(g))
            record = args.record_generations > 0 and g % args.record_generations == 0
            games, scores, results = self.play(record)
            played = time.time()
            line = {"generation": g, "games": len(games), "scores": {k: round(v, 3) for k, v in scores.items()},
                    "results": results}
            if games:
                self.status("learning")
                data = self.advantages(games)
                line["decisions"] = int(data["advantage"].shape[0])
                line["minutes"] = round(sum(game["length"] for game in games) / len(games) / 60, 1)
                # the learner's last-seen counts, averaged over its games: does it grow and arm more over time?
                last = np.stack([game["outcome"][-1] for game in games])
                line["units"] = round(float(last[:, 0].mean()), 1)
                line["warriors"] = round(float(last[:, 1].mean()), 1)
                line["kills"] = round(float(last[:, 3].mean()), 1)
                line["buildings"] = round(float(last[:, 2].mean()), 1)
                line["lost"] = round(float(last[:, 7].mean()), 1)
                # the strength margin the shaped reward follows, at the last decision
                margin = ((last[:, :4] - last[:, 4:8]) / SHAPING_SCALE * SHAPING_WEIGHT).sum(1)
                line["margin"] = round(float(margin.mean()), 4)
                # how often each choice of each head was taken, and how well the value net foresaw the returns
                actions = data["action"].cpu().numpy()
                line["choices"] = [[round(float(share), 4) for share in
                                    np.bincount(actions[:, head], minlength=size) / max(1, len(actions))]
                                   for head, size in enumerate(self.policy.head_sizes)]
                target_variance = data["target"].var().item()
                line["explained_variance"] = round(1 - data["advantage"].var().item() / target_variance, 4) \
                    if target_variance > 0 else 0.0
                # how often each head chose what the teacher advised (the advice is the observation's last numbers)
                advice = data["observation"][:, -sum(HEAD_SIZES):].cpu().numpy()
                offsets = np.cumsum([0, *HEAD_SIZES[:-1]])
                line["follows"] = [round(float(advice[np.arange(len(actions)), offset + actions[:, head]].mean()), 4)
                                   for head, offset in enumerate(offsets)]
                # what the games counted as, and the damage reward in them, on average
                line["result_mean"] = round(float(np.mean([game["result"] for game in games])), 4)
                line["damage_mean"] = round(float(np.mean([game["damage"] for game in games])), 4)
                counted = [tally for tally in results.values()]
                total = sum(tally["games"] for tally in counted)
                line["decided"] = round(1 - sum(tally["timeouts"] for tally in counted) / max(1, total), 4)
                line.update(self.improve(data))
            if args.eval_generations > 0 and g % args.eval_generations == 0:
                line["eval"], line["eval_results"] = self.evaluate()
            if args.snapshot_generations > 0 and g % args.snapshot_generations == 0:
                self.league.append({"generation": g, "games": 0.0, "wins": 0.0})
            else:
                keep = {entry["generation"] for entry in self.league}
                if g not in keep:
                    self.net_file(g).unlink(missing_ok=True)
            line["league"] = [{"generation": entry["generation"],
                               "score": round(entry["wins"] / entry["games"], 3) if entry["games"] > 0 else None}
                              for entry in self.league]
            line["stock_score"] = {name: round(score, 3) for name, score in self.stock_score.items()}
            line["play_s"] = round(played - started, 1)
            line["learn_s"] = round(time.time() - played, 1)
            line["time"] = round(time.time(), 1)
            self.generation += 1
            self.save()
            with open(self.dir / "log.jsonl", "a") as log:
                log.write(json.dumps(line) + "\n")
            print(json.dumps(line), flush=True)


def main():
    args = parse_args()
    if not (ROOT / "aisim" / "snap" / "latest").exists():
        sys.exit("no build yet: run ./aisim.sh build first")
    if "AISIM_JDK" not in os.environ and "JAVA_HOME" not in os.environ:
        print("note: neither AISIM_JDK nor JAVA_HOME is set; aisim.sh must find a JDK 26 by itself")
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    if not math.isfinite(args.lr) or args.lr <= 0:
        sys.exit("--lr must be positive")
    for option, names in (("--eval-stock", args.eval_stock), ("--stock", "" if args.stock == "auto" else args.stock)):
        unknown = [name for name in names.split(",") if name and name not in STOCK]
        if unknown:
            sys.exit(f"{option}: no stock AI {', '.join(unknown)} ({', '.join(STOCK)})")
    if not args.stock:
        sys.exit("--stock: name a stock AI, a ladder of them or auto")
    def interrupt(*_):
        raise KeyboardInterrupt  # as Ctrl+C: the batch it waits for is ended too

    signal.signal(signal.SIGTERM, interrupt)  # the dashboard's "stop now"
    sys.stdout.reconfigure(line_buffering=True)  # into a file (the dashboard's train.out), line by line
    trainer = Trainer(args)
    try:
        trainer.run()
        trainer.harness.close()
    except KeyboardInterrupt:
        print(f"stopped in generation {trainer.generation}: it is played again when the run resumes")
    finally:
        trainer.harness.kill()
        trainer.status("stopped")


if __name__ == "__main__":
    main()
