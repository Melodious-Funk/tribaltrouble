#!/usr/bin/env python3
"""Trains the neural AI by self-play, generation after generation.

A generation: export the current net, play batches of harness games with it (against itself, against earlier nets
of this run, against the stock AIs), read the trajectories the AI wrote, and improve the net with PPO. Every game is
1 vs 1 on `--size medium --terrain tropical --hills 0 --trees 10 --supplies 10`.

    python lab/neural/train.py --run first --generations 200

Run it from the repository root, after `./aisim.sh build`, with AISIM_JDK pointing at a JDK 26. State is kept under
aisim/neural/RUN/ (git-ignored): `latest.pt` to resume from, `nets/` the exported nets, `log.jsonl` a line per
generation. Stop it with Ctrl+C or by creating aisim/neural/RUN/STOP; start it again to go on.
lab/neural/NOTES.md explains the choices made here.
"""

import argparse
import json
import math
import os
import random
import shutil
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
# The sizes of the AI of this build; a first trajectory confirms them.
OBSERVATION_SIZE = 91
HEAD_SIZES = [4, 5, 5, 4, 3, 5, 3, 2]
# Outcome numbers (Observation.outcome): own units, warriors, buildings, kills, then the enemies'.
SHAPING_SCALE = np.array([250, 100, 10, 250], dtype=np.float32)
SHAPING_WEIGHT = np.array([0.3, 0.3, 0.3, 0.1], dtype=np.float32)


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
    p.add_argument("--entropy", type=float, default=0.01)
    p.add_argument("--value-coefficient", type=float, default=0.5)
    p.add_argument("--shaping", type=float, default=0.5,
                   help="weight of the shaped reward (the change of the strength margin) next to winning")
    p.add_argument("--self-share", type=float, default=0.4, help="share of a generation played against itself")
    p.add_argument("--stock-share", type=float, default=0.2, help="share played against the stock AIs")
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
        self.policy = Policy(OBSERVATION_SIZE, HEAD_SIZES, hidden).to(self.device)
        self.optimizer = torch.optim.Adam(self.policy.parameters(), lr=args.lr, eps=1e-5)
        self.random = random.Random(args.seed)
        checkpoint = self.dir / "latest.pt"
        if checkpoint.exists():
            state = torch.load(checkpoint, map_location=self.device, weights_only=False)
            self.policy = Policy(OBSERVATION_SIZE, HEAD_SIZES, tuple(state["hidden"])).to(self.device)
            self.policy.load_state_dict(state["policy"])
            self.optimizer = torch.optim.Adam(self.policy.parameters(), lr=args.lr, eps=1e-5)
            self.optimizer.load_state_dict(state["optimizer"])
            self.generation = state["generation"]
            self.league = state["league"]
            self.stock_score = state["stock_score"]
            self.random.setstate(state["random"])
            print(f"resuming {args.run} at generation {self.generation}")

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
        maps = max(3, self.args.games // 2)
        self_maps = max(1, round(maps * self.args.self_share))
        stock_maps = max(1, round(maps * self.args.stock_share))
        past_maps = maps - self_maps - stock_maps if self.league else 0
        if not self.league:
            self_maps = maps - stock_maps
        me = self.spec(g, trajectories, g)
        lineups = [("self", f"{me} vs {self.spec(g, trajectories, g + 500_000)}", self_maps)]
        # the stock AI it does worst against that it has not yet mastered, else the hardest
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

    def play(self, record: bool) -> tuple[list[dict], dict]:
        """Plays the generation's games; returns its trajectories with their results, and per-kind scores."""
        g = self.generation
        trajectories = self.dir / "traj" / f"g{g:06d}"
        shutil.rmtree(trajectories, ignore_errors=True)
        games = []
        scores = {}
        first_seed = self.random.randrange(TRAIN_SEEDS - 400)
        for kind, players, maps in self.lineups(trajectories):
            rows = self.batch(f"{self.args.run}-g{g:06d}-{kind}", players, first_seed, maps, record, self.args.minutes)
            first_seed += maps
            counted = [row for row in rows if row.get("result") is not None]
            if counted:
                scores[kind] = sum(row["score"] for row in counted) / len(counted)
            for row in counted:
                for team in row["teams"]:
                    for slot in team["slots"]:
                        file = trajectories / f"{row['key']}-s{slot}.traj"
                        trajectory = read_trajectory(file) if file.exists() else None
                        if trajectory is not None:
                            # 1 for first place alone, -1 for last, 0 for a shared place (a draw at the time limit)
                            trajectory["result"] = 2 * team["score"] - 1
                            trajectory["length"] = row["t"]
                            games.append(trajectory)
            self.note(kind, counted)
        shutil.rmtree(trajectories, ignore_errors=True)
        return games, scores

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
                margin = ((outcome[:, :4] - outcome[:, 4:]) / SHAPING_SCALE * SHAPING_WEIGHT).sum(1)
                steps = len(value)
                reward = np.zeros(steps, dtype=np.float32)
                reward[:-1] = args.shaping * (args.gamma * margin[1:] - margin[:-1])
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

    def evaluate(self) -> dict:
        """The current net, choosing greedily, against each stock AI on maps training never plays."""
        scores = {}
        maps = max(1, self.args.eval_games // 2)
        for name in STOCK:
            players = f"{self.spec(self.generation, None, 0, greedy=True)} vs {name}"
            rows = self.batch(f"{self.args.run}-eval-{name}", players, EVAL_SEED, maps, False, 40)
            counted = [row for row in rows if row.get("result") is not None]
            scores[name] = round(sum(row["score"] for row in counted) / max(1, len(counted)), 3)
        return scores

    def save(self) -> None:
        state = {"policy": self.policy.state_dict(), "optimizer": self.optimizer.state_dict(),
                 "hidden": [layer.out_features for layer in self.policy.hidden], "generation": self.generation,
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
            games, scores = self.play(record)
            played = time.time()
            line = {"generation": g, "games": len(games), "scores": {k: round(v, 3) for k, v in scores.items()}}
            if games:
                data = self.advantages(games)
                line["decisions"] = int(data["advantage"].shape[0])
                line["minutes"] = round(sum(game["length"] for game in games) / len(games) / 60, 1)
                # the learner's last-seen counts, averaged over its games: does it grow and arm more over time?
                last = np.stack([game["outcome"][-1] for game in games])
                line["units"] = round(float(last[:, 0].mean()), 1)
                line["warriors"] = round(float(last[:, 1].mean()), 1)
                line["kills"] = round(float(last[:, 3].mean()), 1)
                line.update(self.improve(data))
            if args.eval_generations > 0 and g % args.eval_generations == 0:
                line["eval"] = self.evaluate()
            if args.snapshot_generations > 0 and g % args.snapshot_generations == 0:
                self.league.append({"generation": g, "games": 0.0, "wins": 0.0})
            else:
                keep = {entry["generation"] for entry in self.league}
                if g not in keep:
                    self.net_file(g).unlink(missing_ok=True)
            line["play_s"] = round(played - started, 1)
            line["learn_s"] = round(time.time() - played, 1)
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
    Trainer(args).run()


if __name__ == "__main__":
    main()
