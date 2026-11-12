#!/usr/bin/env python3
"""Chooses the fixed pool of maps the neural AI trains and is evaluated on, and writes it to lab/neural/maps.json.

Two steps:

1. The seed finder's measurements (a scan of every seed of medium 1v1 at hills 0, trees 10, supplies 10) keep the
   maps without defects whose starts look alike: low `start_unfairness`, balanced territory and chicken odds, a usual
   rush distance, rock and iron near both bases, no narrow chokepoint or long detour. The `--candidates` least unfair
   of those go on to step 2.
2. Mirror games show what the measurements cannot: Hard against Hard and the teacher against itself, from both starts,
   with `--rngs` world seeds each. On a fair map neither start wins more often. A map's bias is the mean, from the
   first start's side, of the result (1 win, 0 draw, -1 loss) and of the strength margin at the end. The maps with the
   smallest bias become the pool: the first `--train` for training, the next `--eval` for evaluation only.

    python3 lab/neural/fairmaps.py --scan ../tribaltrouble-seedfinder/results/medium-1v1-sliders-0-10-10-10-every-seed

Run it from the repository root after `./aisim.sh build`, with AISIM_JDK set. It plays about 12 games a candidate.
"""

import argparse
import csv
import gzip
import json
import math
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SETTING = {"size": "medium", "terrain": "tropical", "hills": "0", "vegetation": "10", "supplies": "10"}
MAP = ["--size", "medium", "--terrain", "tropical", "--hills", "0", "--trees", "10", "--supplies", "10"]
MIRRORS = {"hard": "hard vs hard", "teacher": "neural:greedy=1 vs neural:greedy=1"}


def number(row: dict, column: str) -> float:
    try:
        return float(row[column])
    except (KeyError, ValueError):
        return math.nan


def looks_fair(row: dict) -> bool:
    """Step 1: no defect, and starts that the seed finder measures as alike (NaN fails every bound)."""
    if row["flags"] or row["error"]:
        return False
    bounds = [("start_unfairness", None, 0.27), ("territory_balance", 0.9, None), ("chicken_balance", 0.7, None),
              ("start_pair_walk_min_m", 340, 395), ("start_iron_near_min", 3, None), ("start_rock_near_min", 5, None),
              ("start_rock_dist_max_m", None, 100), ("start_iron_dist_max_m", None, 120),
              ("start_chicken_dist_max_m", None, 80), ("choke_width_min_m", 35, None), ("detour_max", None, 1.05),
              ("start_height_spread_m", None, 8), ("start_sites_min", 500, None)]
    for column, low, high in bounds:
        value = number(row, column)
        if math.isnan(value) or (low is not None and value < low) or (high is not None and value > high):
            return False
    return True


def read_scan(folder: Path, below: int) -> list[dict]:
    table = folder / "results.csv.gz"
    opener = gzip.open if table.exists() else open
    table = table if table.exists() else folder / "results.csv"
    with opener(table, "rt", newline="") as file:
        return [row for row in csv.DictReader(file)
                if all(row[key] == value for key, value in SETTING.items()) and row["players"] == "2"
                and int(row["seed"]) < below]


def play(name: str, players: str, seeds: list[int], rng: int, minutes: int) -> subprocess.Popen:
    run = ROOT / "aisim" / "runs" / name
    if (run / "results.jsonl").exists():
        return None  # played already: a stopped selection goes on where it was
    subprocess.run(["rm", "-rf", str(run)])
    command = ["bash", str(ROOT / "aisim.sh"), "batch", "--name", name, "--players", players, "--seeds",
               ",".join(map(str, seeds)), "--rng", str(rng), "--minutes", str(minutes), "--record-every", "1000000",
               *MAP]
    return subprocess.Popen(command, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--scan", type=Path, required=True, help="the seed finder's collection folder")
    p.add_argument("--candidates", type=int, default=240, help="maps of step 1 that step 2 plays")
    p.add_argument("--rngs", type=int, default=3, help="world seeds per start and mirror")
    p.add_argument("--minutes", type=int, default=20, help="the time limit of a mirror game")
    p.add_argument("--train", type=int, default=100)
    p.add_argument("--eval", type=int, default=20)
    p.add_argument("--below", type=int, default=38000, help="only seeds below this")
    p.add_argument("--out", type=Path, default=ROOT / "lab" / "neural" / "maps.json")
    args = p.parse_args()

    rows = read_scan(args.scan, args.below)
    fair = sorted((row for row in rows if looks_fair(row)), key=lambda row: number(row, "start_unfairness"))
    candidates = fair[:args.candidates]
    print(f"step 1: {len(rows)} maps of the setting, {len(fair)} look fair, {len(candidates)} go on")
    if len(candidates) < args.train + args.eval:
        sys.exit("too few candidates for the pool")
    seeds = sorted(int(row["seed"]) for row in candidates)

    started = time.time()
    runs = {(mirror, rng): f"fairmaps-{mirror}-r{rng}" for mirror in MIRRORS for rng in range(1, args.rngs + 1)}
    processes = [play(name, MIRRORS[mirror], seeds, rng, args.minutes) for (mirror, rng), name in runs.items()]
    for process in processes:
        if process is not None and process.wait() != 0:
            sys.exit(f"a mirror batch failed (exit {process.returncode}); see aisim/runs/fairmaps-*")
    print(f"step 2: {len(runs)} batches played in {time.time() - started:.0f} s")

    # bias toward the first start, per map: the result and the margin of whoever sits in slot 0
    games = {seed: [] for seed in seeds}
    for (mirror, rng), name in runs.items():
        for line in (ROOT / "aisim" / "runs" / name / "results.jsonl").read_text().splitlines():
            row = json.loads(line)
            if row.get("result") is None:
                continue
            toward_slot0 = 1 if row["side"] == 0 else -1
            games[row["seed"]].append({"mirror": mirror, "result": toward_slot0 * (2 * row["score"] - 1),
                                       "margin": toward_slot0 * row["margin"], "decided": row.get("end") != "timeout"})
    measured = []
    for row in candidates:
        seed = int(row["seed"])
        played = games[seed]
        if not played:
            continue
        result = sum(game["result"] for game in played) / len(played)
        margin = sum(game["margin"] for game in played) / len(played)
        measured.append({"seed": seed, "mapcode": row["mapcode"], "games": len(played),
                         "bias": round(abs(result) + abs(margin), 4), "result_bias": round(result, 4),
                         "margin_bias": round(margin, 4),
                         "decided": round(sum(game["decided"] for game in played) / len(played), 3),
                         "start_unfairness": number(row, "start_unfairness"),
                         "territory_balance": number(row, "territory_balance"),
                         "rush_m": number(row, "start_pair_walk_min_m")})
    measured.sort(key=lambda entry: (entry["bias"], entry["start_unfairness"]))
    pool = measured[:args.train + args.eval]
    # every pool map is fair; which ones are held out for evaluation is a fixed, spread-out choice
    step = len(pool) / args.eval
    held_out = {pool[int(i * step + step / 2)]["seed"] for i in range(args.eval)}
    out = {"setting": "1v1 medium tropical hills 0 trees 10 supplies 10",
           "made": time.strftime("%Y-%m-%d"),
           "how": f"lab/neural/fairmaps.py: {len(fair)} of {len(rows)} seeds passed the seed finder's fairness bounds; "
                  f"the {len(candidates)} least unfair played {', '.join(MIRRORS)} mirrors from both starts with "
                  f"{args.rngs} world seeds at {args.minutes} minutes; the {len(pool)} least biased kept",
           "train": sorted(entry["seed"] for entry in pool if entry["seed"] not in held_out),
           "eval": sorted(held_out),
           "maps": pool}
    args.out.write_text(json.dumps(out, indent=1) + "\n")
    worst = pool[-1]
    print(f"wrote {args.out}: {len(out['train'])} training and {len(out['eval'])} evaluation maps; the most biased "
          f"kept: seed {worst['seed']} result {worst['result_bias']:+.2f} margin {worst['margin_bias']:+.2f}; "
          f"of the {len(measured)} played, the median bias is {measured[len(measured) // 2]['bias']:.2f}")


if __name__ == "__main__":
    main()
