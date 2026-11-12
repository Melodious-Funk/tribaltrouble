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
- `./aisim.sh serve` (2026-11-12): batch requests on stdin, one JSON line each, played on one pool of worker JVMs
  that outlives them; each run is the run `batch` writes, answered with an `@@` line when it is done
  (`docs/aisim.md`, Workers). A worker plays 1000 games (`--games-per-worker`) before a fresh one takes its place,
  against a batch's 100. Its games are a batch's, checksum for checksum.

The AI (`player/neural/`, about 1,300 lines):

| File | What it does |
|---|---|
| `NeuralAI` | Entry class. Every `step` (5) game seconds: observe, choose, record; every second: `Body.act`. |
| `Sight` | Reads the game once a second: own units by what they do, buildings, stocks, the enemies' numbers. |
| `Observation` | 136 numbers for the policy: 66 scaled counts and distances, the choices in force and the teacher's advice, one-hot. |
| `Heads` | The 10 choices the policy makes, 35 logits in all, and how often each may change. |
| `Teacher` | A scripted choice for every head: the plan the policy starts from (see below). |
| `Net` | The network file and its evaluation in plain Java (`StrictMath`, fixed order: the same bits everywhere). |
| `Body` | The skill layer: carries the choices out with plain rules, through the player's own orders only. |
| `Trajectory` | Writes observations, actions, allowed choices, log probability and value for the trainer. |

The heads (since 2026-11-12; `Heads.java`):

| head | choices | chooses every |
|---|---|---|
| build | nothing / quarters / armory / tower (up to 3 quarters, 1 armory, 8 towers, 2 sites at a time) | 5 s |
| peons kept in each quarters | 1 / 4 / 10 / 18 / 30; the rest walk to the armory | 15 s |
| peons kept at the forge | 2 / 6 / 12 / 20 / 32; the rest of the armory gathers or takes arms | 15 s |
| wood, rock, iron, rubber gatherers | one head each: 2 fewer / same / 2 more, moving a target that starts at 10/0/8/0 | 10 s |
| weapons made | iron / iron and rubber / rubber / all three | 30 s |
| army | defend / attack / pull back | 20 s |
| chieftain | no / train | 60 s |

Between its choices a head keeps the one in force (`Body.allowed` allows only that one), so a quarters or an armory
is not told something new every 5 s and an attack is not called back before it arrives. `Body.allowed` also masks
what cannot be done.

Scripted in `Body`, not learned: the first quarters and the first armory (with every starting peon, the armory as
soon as the quarters stands), where buildings go, who builds, tower garrisons, how an army fights (it walks at the
enemy fighting, never at a building: an order at a building razes it while warriors cut the unit down), defending
the base while the army is away, when the chieftain casts. The forge crew stays in the armory before gatherers are
sent out once there is stock to forge.

## The teacher (2026-11-12)

A net of random weights played a random plan: every game a time-limit draw with both sides at 250 units, a score
against easy of 0.46 after 178 generations of `first`, and 0 against normal and hard. So the net no longer starts from
nothing. `Teacher` advises one choice per head, and the AI adds `prior` (4.5 by default, stored in the net file) to the
logit of each advised choice, so a new net plays the teacher's plan about 99% of the time and training moves away
from it only where that pays. The advice is part of the observation; `ttnet.Policy` adds the prior the same way.
Without a net (`neural:greedy=1`) the AI is the teacher alone.

The teacher's plan, measured against Hard on seeds 1..20, both starts (40 games, 30-minute limit; `t_...` params):

- the quarters first with all 20 starting peons, then the armory with all of them; 2 quarters, 2 towers, then the
  third and last quarters (3 at most since 2026-11-12; up to 6 in the results below); 1 peon kept in each quarters (breeding goes with the cube root of the peons inside, so many emptier
  quarters breed faster); the forge crew at 30% of the peons outside the quarters;
- gatherers: 10 wood and 8 iron, then 3 rock and 8 rubber from 2 minutes before rubber weapons; more of a resource
  while its armory stock is short (wood below 12, ore below 5), fewer while it piles up (wood above 60, ore above 30);
  each up to 10/3/8/8 scaled by peons/60, all of them within half the peons;
- iron weapons, iron and rubber from 12 minutes;
- attack at 15 warriors and 1.3 times the enemy's strength (warriors, 4 per tower, 6 per chieftain), and stay in the
  attack unless it falls below 0.4 times; at the unit limit attack whatever the odds.

Results along the way, 20 or 40 games each against Hard: the first teacher 2/20; quarters before the armory and
the opening crew kept on the armory 16/20; fighting walks instead of orders at buildings 15/20 -> 17/20 (kills per
loss about even); defence at home while attacking 33/40. Gatherers 10/3/8/8 from the start (the user's standard)
with rubber weapons: 26/40, and 0.2 warriors at 5 minutes against 4.6: early, every peon out gathering rock and
rubber is a warrior less when Hard's first wave comes. Phased in as above: 30/40 with rubber at 12 minutes, 27/40 at
8, 24/40 at 5, 28/40 with iron alone (all within noise of one another). Hard still wins about a quarter of the games,
mostly by a wave at 6-8 minutes against a base still building its third quarters.

## The reward (2026-11-12)

- A game that reaches the time limit counts as `--timeout-result` (-1, a loss) for both sides: a draw is never
  safe. A win is 1 plus `--win-speed` (0.5) times the share of the time limit left; a loss -1.
- Damage pays on the way: `--kill-reward` 0.002 per enemy unit killed, `--loss-penalty` 0.001 per unit lost (an even
  fight pays), `--raze-reward` 0.05 per enemy building, `--razed-penalty` 0.03 per building lost.
- The shaped strength margin stays at a smaller weight (`--shaping` 0.2); it sums to nothing over a game.
- Opponents: itself 30%, the teacher 20%, Hard 20% (`--stock`), earlier nets the rest. Evaluation every 10th
  generation plays the teacher and the stock AIs of `--eval-stock` (Hard alone by default), with the training time
  limit: above 0.5 against the teacher, training has found something better.
- `teacher1` (from 2026-11-12) ran generations 0-21 with 120 games of 30 minutes and up to 6 quarters, then on with
  50 games of 20 minutes, 3 quarters at most, and Hard alone in training and evaluation. Its teacher under those
  limits: 15 of 20 against Hard (2 draws).
- The log line adds `follows` (per head, how often the advice was taken), `result_mean`, `damage_mean` and `decided`
  (the share of games won or lost before the limit); the dashboard charts them, and takes the head names from
  `args.json`.

The trainer (`train.py`, `ttnet.py`): PPO with GAE. A generation exports the net, plays its harness batches, reads the
trajectories, labels them with the results from `results.jsonl`, and updates. The batches go to one `./aisim.sh serve`
that lives as long as the session (its log: `aisim/neural/RUN/serve.log`), all of a generation's at once, so its
worker JVMs stay warm and no batch waits for another's last game. Every 10th generation the net joins the league and
is evaluated greedily on the pool's 20 evaluation maps (40 games per opponent), which training never plays. Run folders and full recordings are
kept for every 100th generation only (`--record-generations`). A run is tied to the build of the AI it started with:
`train.py` refuses to resume one whose observation or heads differ, and trajectories of another build.

## The map pool (2026-11-12)

Training plays the 100 training maps of `maps.json`, evaluation its 20 held-out maps: 120 maps where neither start
has the better of it, as far as could be measured. `fairmaps.py` chose them:

1. The seed finder's scan of every medium 1v1 seed (`../tribaltrouble-seedfinder/results/medium-1v1-sliders-0-10-10-10-every-seed`,
   whose maps are the harness's: the map codes match) at hills 0, trees 10, supplies 10: no defect flag, start
   unfairness at most 0.27 (the median is 0.37), territory balance 0.9 or more, chicken balance 0.7 or more, a rush
   walk of 340-395 m, at least 3 iron and 5 rock within 150 m of both bases, rock within 100 m and iron within 120 m
   of both, no chokepoint narrower than 35 m, little detour, starts on level ground with room to build. 875 of the
   38,000 training seeds pass.
2. The 240 least unfair of those played mirrors, Hard against Hard and the teacher against itself, from both starts
   with 3 world seeds (12 games a map, 20 minutes). A map's bias is the first start's mean result plus its mean
   strength margin, in absolute value; the 120 least biased were kept (the median of the 240 was 0.45, the most
   biased kept 0.44, and over the pool the first start's mean result is +0.01). Every 6th of them, spread over the
   order, is an evaluation map.

A pool this small can be learnt by heart; the evaluation maps are there to show it. Evaluations against the pool are
not comparable with the seeds 39000+ that runs before it were measured on.

## The larger net (2026-11-12)

New runs default to `--hidden 256,256,256` (teacher1: 128,128). What a net costs in a game, per neural player (one
neural player against Hard, profiled): 1.5% of the simulation's CPU at 128x128, 4.7% at 256x256x256, 13% at
512x512x512; self-play has two. So 256x256x256 adds about 6% to a self-play game, 512x512x512 about 25%.

`--init-from RUN` starts a new run from what RUN's net learnt, whatever its size: RUN's net plays `--init-games` (240)
games on the training maps, against itself, Hard and the teacher, and the new net learns to choose as it does (the
KL divergence of each head's choices, among the allowed ones) and to value as it does, for `--init-epochs` (40)
passes; `init.json` in the run's folder has how close it came per head. PPO starts from there with a fresh optimizer.

`big1` (2026-11-12, 256x256x256) started that way from teacher1's generation 500, with teacher1's later settings
(`--games 50 --minutes 20`): on 57,630 decisions of 240 games the new net chose teacher1's most likely choice
99.1-99.97% of the time per head (KL 0.0001-0.0005), value error 0.04. Its greedy evaluation at generation 0, on the
pool's evaluation maps: Hard 39-1 of 40, the teacher 24-2 with 14 draws (0.775); teacher1's own last evaluations on
seeds 39000+ were 0.975 and 0.70. Only generation 0 has been played: go on with
`train.py --run big1 --games 50 --minutes 20 --generations N`.

## How to run

```bash
export AISIM_JDK=~/.gradle/jdks/eclipse_adoptium-26-amd64-linux.2     # this laptop's only JDK 26
./aisim.sh build
python -m venv aisim/venv && aisim/venv/bin/pip install torch numpy  # once; a CUDA build of torch on the GPU machine
aisim/venv/bin/python lab/neural/train.py --run big1 --init-from teacher1 --games 50 --minutes 20 --generations 1000
./aisim.sh batch --players "neural:net=aisim/neural/big1/nets/g000100.bin,greedy=1 vs hard" \
    --seeds "$(python3 -c "import json; print(','.join(map(str, json.load(open('lab/neural/maps.json'))['eval'])))")" \
    --size medium --terrain tropical --hills 0 --trees 10 --supplies 10
python3 lab/neural/fairmaps.py --scan ../tribaltrouble-seedfinder/results/medium-1v1-sliders-0-10-10-10-every-seed  # to choose the pool again
./aisim.sh play --players "neural:greedy=1 vs hard"                  # no net: the teacher alone
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
`bash aisim.sh`, so on Windows start it from Git Bash. Its serve plays the build that was latest when the session
started, and refuses to start on a stale one; rebuild only between sessions.

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
- Training speed (2026-11-12), one teacher1-sized generation (50 games of 20 minutes in 4 batches): 38-50 s of play
  with a batch per kind of opponent, one after another, of which the games themselves need about 9 s of the 6 cores.
  The rest was warming up worker JVMs: a profile of 12 games had 9 s of simulation and 32.7 s of JIT compiling on
  other threads, since each batch started its own workers and each played 2-3 games. With all of a generation's
  batches sent to one serve: 26-30 s for the first generation of a session, then 9.7-11.6 s (auto, 6 or 5 workers
  alike). Tried and dropped: the batches started together as separate processes, 31-41 s; C1 alone
  (`-XX:TieredStopAtLevel=1`) helped short-lived workers (26 s, 23 s with an AOT cache) but makes warm ones slower
  (18 s); an AOT cache would only shorten a session's first generation. All of these play the same games.
- A warm worker, self-play with 256x256x256 nets (40 games): engine 77%, map generation 12%, the two nets 10%; 0.89 s
  of CPU per game.

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
2. **Map cache.** Map generation is about 10% of a warm worker's CPU, and training plays the same 100 maps again
   and again. A cache must not share anything the world changes: the world takes several of the generator's arrays
   as they are (height, ground, build, water, dock grids, plants, starts), so either copy those per game from a cache
   of the extracted map (about 3 MB a map: more than a worker's 256 MB heap holds for 100 maps) or keep them on disk.
   Check it as the generator change was checked: every game identical.
3. **The observation has no map.** No resource positions, no spatial layout. A coarse grid needs a convolution in
   `Net`; when the network outgrows plain Java, serve it from the GPU over a socket for training and keep Java
   inference for the released net.
4. **The skill layer is thin.** No kiting, focus fire, peon militia, swing restart or stun cancel; attack targets are
   the nearest building. `gauntlet` has all of these and could replace `Body`'s rules.
5. **The teacher is the floor, not the ceiling.** It beats Hard about 3 games in 4; the first waves at 6-8 minutes
   decide most of its losses. Training against itself and the teacher should find better timings; look at
   `follows` per head to see where the net leaves the teacher.
6. **Weights are a file.** `freeze` copies only the package's classes, so a frozen or shipped net has to be generated
   into a class of the package.
7. **The seed finder's faster kernels** would cut the 90 ms a map takes (or what a map cache leaves of it).

## Environment

- JDK 26 is only at `~/.gradle/jdks/eclipse_adoptium-26-amd64-linux.2` on this laptop: set `AISIM_JDK` to it.
- The Python environment is `aisim/venv` (git-ignored): CPU PyTorch and NumPy.
- Commits on this branch were made with the name and noreply address of the seed finder's commits, since no git
  identity was configured here; amend them if that is wrong. Nothing was pushed.
