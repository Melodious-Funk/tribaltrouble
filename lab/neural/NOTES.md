# neural: working notes

A computer player whose plan comes from a network trained by self-play. Package
`tt/src/main/java/com/oddlabs/tt/player/neural/`, trainer and tools here, branch `neural` off `headless`. This file is
the handover: read it first in a new session.

## Scope (decided 2026-11-10)

- 1 vs 1 only, `--size medium --terrain tropical --hills 0 --trees 10 --supplies 10`, vikings.
- Training is meant for an RTX 4070 Ti machine (its CPU and OS were not known when this was written). The simulation
  is CPU-bound: the core count sets the training rate, the GPU only does the PPO update.
- Stock Hard is not the standard: humans beat 4 Hards. Measure against the scripted AIs (`gauntlet`, `corsair`, and
  Expert, Ultra and Fable in other checkouts) and 1-vs-N-Hard ladders. None of them is wired in yet.

## What is on this branch

Harness commits (they belong on `headless`; they are here so the branch is one piece to review):

- `Landscape.generateHeadless`: harness workers skip the work that only paints the terrain. Idea from the seed
  finder's generator, applied by hand to the current `Landscape`. `LandscapeCheck.java` here compares every getter
  the world reads: 42 maps identical (3 sizes, both terrains, 2-6 players). Runs `mg-base-*` against `mg-new-*`
  (58 games): all identical; the target setting costs 12.7% +- 2.5% less CPU per game; a map takes 90 ms instead of
  134 ms.
- `batch --record-every N`: only every Nth game gets a game file. 30 games recorded against 30 with every tenth:
  identical games, CPU -2.6% +- 1.5%. `replay` verifies an unrecorded game by its final checksum.
- `AiLog.game()`: the harness's label of the game, so the AI can name its trajectory file after the game key.

The AI (`player/neural/`, about 900 lines):

| File | What it does |
|---|---|
| `NeuralAI` | Entry class. Every `step` (5) game seconds: observe, choose, record; every second: `Body.act`. |
| `Sight` | Reads the game once a second: own units by what they do, buildings, stocks, the enemies' numbers. |
| `Observation` | 91 numbers for the policy: 60 scaled counts and distances, and the last action one-hot. |
| `Heads` | The 8 choices the policy makes, 31 logits in all. |
| `Net` | The network file and its evaluation in plain Java (`StrictMath`, fixed order: the same bits everywhere). |
| `Body` | The skill layer: carries the choices out with plain rules, through the player's own orders only. |
| `Trajectory` | Writes observations, actions, allowed choices, log probability and value for the trainer. |

The heads: build (nothing / quarters / armory / tower), gatherer mix, gatherer share of all peons, armory share of
the peons inside, weapon (rock / iron / rubber), army share of all units, posture (defend / attack / retreat),
chieftain (no / train). `Body.allowed` masks what cannot be done.

Scripted in `Body`, not learned: the first quarters and the first armory, where buildings go, who builds, the split of
peons over several quarters, tower garrisons, target choice (nearest enemy building), when the chieftain casts. A new
weapon choice takes effect every 30 s and a chieftain choice every 60 s: a random policy that changed them every 5 s
reset the quarters' and armory's progress each time and never bred a unit.

The trainer (`train.py`, `ttnet.py`): PPO with GAE. A generation exports the net, plays three harness batches (itself
40%, the easiest stock AI it has not mastered 20%, an earlier net of the run 40%, chosen more often the more it
still loses to it), reads the trajectories, labels them with the results from `results.jsonl`, and updates. Reward:
+1 / -1 / 0 for win / loss / draw at the end, plus a shaped term, the change of a strength margin (own minus enemy
units, warriors, buildings, kills). Every 10th generation the net joins the league and is evaluated greedily against
easy, normal and hard on seeds 39000+, which training never plays. Run folders and full recordings are kept for
every 100th generation only (`--record-generations`).

## How to run

```bash
export AISIM_JDK=~/.gradle/jdks/eclipse_adoptium-26-amd64-linux.2     # this laptop's only JDK 26
./aisim.sh build
python -m venv aisim/venv && aisim/venv/bin/pip install torch numpy  # once; a CUDA build of torch on the GPU machine
aisim/venv/bin/python lab/neural/train.py --run first --generations 200 --games 400
./aisim.sh batch --players "neural:net=aisim/neural/first/nets/g000100.bin,greedy=1 vs hard" --seeds 39000..39029 \
    --size medium --terrain tropical --hills 0 --trees 10 --supplies 10
./aisim.sh play --players "neural vs easy"                           # no net: a uniformly random policy
```

To follow a run in a browser (charts of every number below, and what the trainer is doing right now):

```bash
python3 lab/neural/dashboard.py            # opens http://localhost:8642 and reads aisim/neural/*/ every 5 s
```

From the page you can also start a run or go on with one (it runs `train.py`, detached: the run goes on when the
dashboard stops; its output is in `aisim/neural/RUN/train.out`), stop one after its generation (the `STOP` file)
or at once (the generation it is in is lost and played again), and open a kept game in the viewer ("Watch", which
runs `./aisim.sh watch` and so needs the `neural-watch` branch and a desktop). The server listens on this machine
only and acts only for its own page.

It needs only Python's standard library and reads `log.jsonl` (a line per generation), `status.json` (the phase and
the batch the trainer waits for) and `args.json` (the settings), which `train.py` writes. Besides the scores, a
log line has the results per opponent (`results`: games, wins, losses, draws, those at the time limit), how often
each choice of each head was taken (`choices`), the value net's explained variance, the strength margin at the end
of a game, the league's scores and the usual PPO numbers. Runs logged before 2026-11-11 lack some of these, and the
page says so in their charts.

`train.py` resumes from `aisim/neural/RUN/latest.pt`; `aisim/neural/RUN/STOP` stops it after the generation. It calls
`bash aisim.sh`, so on Windows start it from Git Bash. While it runs, do not edit `.java` files without rebuilding:
every batch refuses a stale build.

## Measured (this laptop, Ryzen 5 4500U, 6 cores, no GPU)

- Target setting, hard vs hard: 0.55 s CPU median per game once a worker is warm; the first three games of a worker
  cost about 2 s each (JIT). Profile before the generator change: engine 61.5% (path finding about 26%), map
  generation 27.5%, recorder 5.4%.
- A random policy (no net) on seeds 1..10, 40 minutes: 0-13-7 against easy, 0-19-1 against normal, 0-20-0 against
  hard. No swallowed errors. Before the staffing fixes it was eliminated by easy in 8-10 minutes.
- Java and PyTorch agree on the log probability of the chosen actions to 2e-6 (`parity` in the log): the export and
  the trajectory format are right.
- The seed finder generates the same medium map in 34 ms (54 ms without SIMD) against 90 ms here: the rest of its
  gain is rewritten kernels (placeSupplies, a faster random, SIMD erosion), not ported, since they rewrite generation
  code that the repository's rules say to leave alone.
- Java against C on copies of the hot kernels: erosion 1.4-1.6x faster in C (clang), a grid search 1.8x faster from
  flat arrays in Java and 1.2x more in C. A native rewrite of the simulator is not worth its cost.

## Trials on this laptop (2026-11-10/11)

Both ran 60 games a generation, 30-minute games, a 128x128 net from random weights. They show that the loop runs and
resumes; they are far too small to show strength.

- `trial` (defaults: lr 3e-4, 4 epochs; 14 generations, about 100 s each): no visible change. Greedy evaluation over
  20 games each: easy 0.43 -> 0.38, normal 0.15 -> 0.05, hard 0.
- `trial2` (`--lr 1e-3 --epochs 6 --minibatch 512 --self-share 0.5 --stock-share 0.5`; 25 generations, about 50 s
  each, about 2,250 player-games in all): a small rise. Score against easy in training (30 games a generation): 0.33
  over the first five generations, 0.40 over the last five, about 1.7 standard errors. Greedy evaluation at
  generations 0 / 5 / 10 / 15 / 20: easy 0.43 / 0.43 / 0.45 / 0.48 / 0.48, normal 0.15 / 0.13 / 0.13 / 0.28 / 0.23,
  hard 0 throughout. The learner's kills per game went from about 11 to 16; the entropy of its choices fell from
  9.10 to 8.83 (uniform is about 9.1).
- Nearly every game reaches the time limit (27-30 of 30 minutes) with both sides near the 250-unit cap, so results
  are mostly draws and the shaped reward carries the learning. Self-play scores are 0.5 by construction.
- A generation of 60 games costs 45-60 s here, of which playing the games is a small part: each of its batches
  starts fresh worker JVMs. Use several hundred games a generation on the training machine.

## Known gaps, roughly in order of value

1. **Opponents.** The league has only the stock AIs and its own past nets. Freeze `gauntlet` (and Expert, Ultra,
   Fable from the other checkouts) with `freeze --from` and add them to `lineups` in `train.py`.
2. **Each generation starts fresh worker JVMs** (three batches), so it pays the JIT warm-up every time. Fine at 400+
   games a generation; a persistent worker pool would fix it properly.
3. **The observation has no map.** No resource positions, no spatial layout. A coarse grid needs a convolution in
   `Net`; when the network outgrows plain Java, serve it from the GPU over a socket for training and keep Java
   inference for the released net.
4. **The skill layer is thin.** No kiting, focus fire, peon militia, swing restart or stun cancel; attack targets are
   the nearest building. `gauntlet` has all of these and could replace `Body`'s rules.
5. **No imitation start.** The net starts random. Cloning a scripted AI needs its decisions expressed in these
   heads.
6. **Timeouts are draws worth 0.** Most early games reach the time limit, so the shaped reward does the teaching.
   Watch `scores` and `minutes` in the log: if games stay at the limit, lengthen `--minutes` or reward the margin at
   the limit.
7. **Weights are a file.** `freeze` copies only the package's classes, so a frozen or shipped net has to be generated
   into a class of the package.
8. **Map cache**, and the seed finder's faster kernels, would cut the remaining 90 ms a map.

## Environment

- JDK 26 is only at `~/.gradle/jdks/eclipse_adoptium-26-amd64-linux.2` on this laptop: set `AISIM_JDK` to it.
- The Python environment is `aisim/venv` (git-ignored): CPU PyTorch and NumPy.
- Commits on this branch were made with the name and noreply address of the seed finder's commits, since no git
  identity was configured here; amend them if that is wrong. Nothing was pushed.
