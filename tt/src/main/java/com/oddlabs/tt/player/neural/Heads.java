package com.oddlabs.tt.player.neural;

/**
 * The decisions the policy makes, one choice per head. The skill layer ({@link Body}) carries them out, and
 * {@link Teacher} has a scripted choice for each, which the policy starts from. The trainer reads the head sizes
 * from the trajectory files, so a head may be added or resized here without touching it; nets trained before the
 * change no longer fit.
 */
final class Heads {
    /** What to build next: nothing, a quarters, an armory or a tower. */
    static final int BUILD = 0;
    /** The peons each quarters keeps inside to breed: an index of {@link #QUARTERS_KEEPS}. The rest leave. */
    static final int QUARTERS_KEEP = 1;
    /** The peons the armory keeps inside to make weapons: an index of {@link #ARMORY_KEEPS}. The rest take arms. */
    static final int ARMORY_KEEP = 2;
    /**
     * The gatherers of wood, rock, iron and rubber: one head each, which moves that resource's target by one of
     * {@link #GATHER_STEPS} whenever it chooses. The targets start at {@link #FIRST_GATHERERS}.
     */
    static final int GATHER_WOOD = 3;
    static final int GATHER_ROCK = 4;
    static final int GATHER_IRON = 5;
    static final int GATHER_RUBBER = 6;
    /** The weapons the armory makes, WEAPONS_*: of those it has the materials for it makes all at once. */
    static final int WEAPON = 7;
    /** What the army does: defend the base, attack, or pull back to it. */
    static final int POSTURE = 8;
    /** Whether to have a chieftain. */
    static final int CHIEF = 9;

    static final int COUNT = 10;
    /** The choices of each head. */
    private static final int[] SIZES = {4, 5, 5, 3, 3, 3, 3, 4, 3, 2};
    /** The sum of {@link #SIZES}: the policy's logits. */
    static final int LOGITS = 35;
    /**
     * How often, in game seconds, each head may choose anew; in between it keeps its choice. A quarters or an armory
     * that is told something else every few seconds starts over each time and never finishes anything, and an army
     * that is sent out and called back every few seconds never arrives.
     */
    private static final int[] PERIODS = {5, 15, 15, 10, 10, 10, 10, 30, 20, 60};

    static final int BUILD_NONE = 0;
    static final int BUILD_QUARTERS = 1;
    static final int BUILD_ARMORY = 2;
    static final int BUILD_TOWER = 3;

    static final int GATHER_FEWER = 0;
    static final int GATHER_SAME = 1;
    static final int GATHER_MORE = 2;

    static final int WEAPONS_IRON = 0;
    static final int WEAPONS_IRON_RUBBER = 1;
    static final int WEAPONS_RUBBER = 2;
    static final int WEAPONS_ALL = 3;

    static final int POSTURE_DEFEND = 0;
    static final int POSTURE_ATTACK = 1;
    static final int POSTURE_RETREAT = 2;

    /**
     * A quarters breeds at the cube root of the peons inside (an empty one as if it held half a peon), so the first
     * few count most and many quarters with few peons breed faster than few with many.
     */
    private static final int[] QUARTERS_KEEPS = {1, 4, 10, 18, 30};
    /** The armory makes weapons in proportion to the peons inside. */
    private static final int[] ARMORY_KEEPS = {2, 6, 12, 20, 32};
    /** What a gather head's choice adds to its resource's target. */
    private static final int[] GATHER_STEPS = {-2, 0, 2};
    /** The gatherer targets of wood, rock, iron and rubber at the start: the first waves are met with iron. */
    private static final int[] FIRST_GATHERERS = {10, 0, 8, 0};
    /** The most gatherers on one resource. */
    static final int MAX_GATHERERS = 60;

    private Heads() {
    }

    static int size(int head) {
        return SIZES[head];
    }

    /** The index of {@code head}'s first logit. */
    static int offset(int head) {
        int offset = 0;
        for (int i = 0; i < head; i++) {
            offset += SIZES[i];
        }
        return offset;
    }

    /** The game seconds {@code head} keeps a choice for. */
    static int period(int head) {
        return PERIODS[head];
    }

    /** The gather head of resource {@code resource} (wood, rock, iron, rubber). */
    static int gatherHead(int resource) {
        return GATHER_WOOD + resource;
    }

    static int gatherStep(int choice) {
        return GATHER_STEPS[choice];
    }

    static int firstGatherers(int resource) {
        return FIRST_GATHERERS[resource];
    }

    static int quartersKeep(int choice) {
        return QUARTERS_KEEPS[choice];
    }

    static int armoryKeep(int choice) {
        return ARMORY_KEEPS[choice];
    }
}
