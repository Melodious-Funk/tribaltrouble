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
STOCK = ["easy", "normal", "hard"]
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
    p.add_argument("--games", type=int, default=120, help="games per generation (two per map: both starts)")
    p.add_argument("--minutes", type=int, default=30, help="the time limit of a training game, in game minutes")
    p.add_argument("--workers", default="auto", help="the harness's --workers")
    p.add_argument("--record-generations", type=int, default=100,
                   help="keep the run folders and full game recordings of every Nth generation only")
    p.add_argument("--snapshot-generations", type=int, default=10,
                   help="every Nth generation's net joins the league of past opponents")
    p.add_argument("--eval-generations", type=int, default=10,
                   help="every Nth generation, measure the net (choosing greedily) against the stock AIs")
    p.add_argument("--eval-games", type=int, default=20, help="games per stock AI in an evaluation")
    p.add_argument("--hidden", default="128,128", help="hidden layer sizes of a new net")
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
    p.add_argument("--stock", default="hard", choices=[*STOCK, "auto"],
                   help="the stock AI of the training games; auto: the easiest one not yet beaten 9 times in 10")
    p.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    p.add_argument("--seed", type=int, default=1)
    return p.parse_args()


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
            self.stock_score = state["stock_score"]
            self.random.setstate(state["random"])
            print(f"resuming {args.run} at generation {self.generation}")
        self.session_start = time.time()
        self.session_first = self.generation
        described = {**vars(args), "prior": self.policy.prior,
                     "heads": [{"name": name, "choices": choices} for name, choices in HEADS]}
        (self.dir / "args.json").write_text(json.dumps(described, indent=1))

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

    def spec(self, generation: int, trajectories: Path | None, seed: int, greedy: bool = False) -> str:
        params = [f"net={self.net_file(generation).relative_to(ROOT).as_posix()}", f"seed={seed}"]
        if trajectories is not None:
            params.append(f"traj={trajectories.relative_to(ROOT).as_posix()}")
        if greedy:
            params.append("greedy=1")
        return "neural:" + ",".join(params)

    def batch(self, name: str, players: str, first_seed: int, maps: int, record: bool, minutes: int) -> list[dict]:
        """Plays `maps` maps from both starts and returns the result rows."""
        run_dir = ROOT / "aisim" / "runs" / name
        shutil.rmtree(run_dir, ignore_errors=True)
        self.status("evaluating" if "-eval-" in name else "playing", batch=name, games=2 * maps, players=players)
        command = ["bash", str(ROOT / "aisim.sh"), "batch", "--name", name, "--players", players, "--seeds",
                   f"{first_seed}..{first_seed + maps - 1}", "--rng", str(self.generation), "--minutes", str(minutes),
                   "--workers", self.args.workers, "--record-every", "1" if record else "1000000", *MAP]
        done = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
        results = run_dir / "results.jsonl"
        if not results.exists():
            raise RuntimeError(f"the harness played nothing for {name}:\n{done.stdout[-2000:]}\n{done.stderr[-2000:]}")
        rows = [json.loads(line) for line in results.read_text().splitlines()]
        if not record:
            shutil.rmtree(run_dir, ignore_errors=True)
        return rows

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
            stock = self.args.stock
            if stock == "auto":
                open_stock = [name for name in STOCK if self.stock_score[name] < 0.9]
                stock = open_stock[0] if open_stock else STOCK[-1]
            lineups.append((stock, f"{me} vs {stock}", stock_maps))
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
        first_seed = self.random.randrange(TRAIN_SEEDS - 400)
        for kind, players, maps in self.lineups(trajectories):
            rows = self.batch(f"{self.args.run}-g{g:06d}-{kind}", players, first_seed, maps, record, self.args.minutes)
            first_seed += maps
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
        """The current net, choosing greedily, against each stock AI and the teacher on maps training never plays."""
        scores = {}
        results = {}
        maps = max(1, self.args.eval_games // 2)
        for name in [*STOCK, "teacher"]:
            opponent = "neural:greedy=1" if name == "teacher" else name
            players = f"{self.spec(self.generation, None, 0, greedy=True)} vs {opponent}"
            rows = self.batch(f"{self.args.run}-eval-{name}", players, EVAL_SEED, maps, False, 40)
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

    def run(self) -> None:
        args = self.args
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
    def interrupt(*_):
        raise KeyboardInterrupt  # as Ctrl+C: the batch it waits for is ended too

    signal.signal(signal.SIGTERM, interrupt)  # the dashboard's "stop now"
    sys.stdout.reconfigure(line_buffering=True)  # into a file (the dashboard's train.out), line by line
    trainer = Trainer(args)
    try:
        trainer.run()
    except KeyboardInterrupt:
        print(f"stopped in generation {trainer.generation}: it is played again when the run resumes")
    finally:
        trainer.status("stopped")


if __name__ == "__main__":
    main()
