# neural: working notes

A learned computer player, trained by self-play. Branch `neural` off `headless`. Nothing of the AI itself exists yet;
this file is the handover for the next session.

## Scope (decided 2026-11-10)

- 1v1 only, `--size medium --terrain tropical --hills 0 --trees 10 --supplies 10`.
- Training on an RTX 4070 Ti machine (its CPU and OS are not known yet). The simulation is CPU-bound, so its core
  count sets the training rate.
- Stock Hard is not the standard: humans beat 4 Hards. Measure against the scripted AIs (gauntlet, Expert, Ultra,
  Fable) and 1-vs-N-Hard ladders.

## Done on this branch

- `Landscape.generateHeadless` (ported idea from the seed finder's generator): harness workers skip the texture-only
  work. `lab/neural/LandscapeCheck.java`: 42 maps identical (3 sizes, both terrains, 2-6 players). Runs
  mg-base-* against mg-new-* (58 games): all identical, target setting -12.7% +- 2.5% CPU per game. Map generation at
  the target setting: 134 -> 90 ms.

## Measured (this laptop, Ryzen 5 4500U, one core)

- Target setting, hard vs hard: 0.55 s CPU median per game once warm; the first three games of a worker cost ~2 s
  (JIT), so workers must live for a whole training session.
- Profile shares before the generator change: engine 61.5% (pathfinding ~26%), map generation 27.5%, recorder 5.4%.
- The seed finder generates the same medium map in 34 ms (54 ms without SIMD). The rest of its gain is rewritten
  kernels (placeSupplies, FastRandom, SIMD erosion), not ported: they rewrite generation code.
- Java against C on copies of the hot kernels: erosion 1.4-1.6x faster in C (clang), grid search 1.8x from flat arrays
  in Java and 1.2x more in C. A native rewrite of the simulator is not worth its cost.

## Next, in order

1. `batch --record-every N` (started, no code yet): `Job` gets a nullable `record`; an unrecorded game uses a
   recorder that is never registered and writes no file (`GameRecorder`), its row gets `recorded: false`;
   `Game.counted` skips such rows; `Summary` and `Compare` must cope with no recorded game; `Replay` verifies an
   unrecorded game by its final checksum. Check with compare that recorded and unrecorded runs are identical games.
2. The AI package `tt/src/main/java/com/oddlabs/tt/player/neural/`: an observation builder, a small scripted skill
   layer (economy split, building, weapons, deploy, attack/defend), a policy that picks macro actions every few game
   seconds, network inference in plain Java (deterministic, for lockstep), weights and trajectory paths as spec
   params (`neural:w=FILE,traj=DIR`), action sampling from `new Random(seed)`.
3. `lab/neural/` trainer in Python (PyTorch, PPO): each generation runs `./aisim.sh batch` with the current weights
   against itself, past checkpoints and scripted AIs, reads the trajectories, updates, and exports the weights.
   Full game recordings only every 100th generation.
4. Later: keep workers alive across generations, a map cache, a socket bridge to GPU inference when the network
   outgrows Java inference.

## Environment

- JDK 26 is only at `~/.gradle/jdks/eclipse_adoptium-26-amd64-linux.2`: set `AISIM_JDK` to it.
- This laptop has no NVIDIA GPU, PyTorch or NumPy.
