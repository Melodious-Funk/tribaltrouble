package com.oddlabs.tt.player.neural;

/**
 * The decisions the policy makes every decision step, one choice per head. The skill layer ({@link Body}) carries
 * them out. The trainer reads the head sizes from the trajectory files, so a head may be added or resized here
 * without touching it; nets trained before the change no longer fit.
 */
final class Heads {
    /** What to build next: nothing, a quarters, an armory or a tower. */
    static final int BUILD = 0;
    /** How the gatherers split over wood, rock, iron and rubber: an index of {@link #GATHER_MIXES}. */
    static final int GATHER_MIX = 1;
    /** The share of all peons that gather: an index of {@link #GATHER_SHARES}. */
    static final int GATHER_LEVEL = 2;
    /** The share of the peons inside buildings that staff the armory: an index of {@link #ARMORY_SHARES}. */
    static final int ARMORY_SHARE = 3;
    /** The weapon the armory makes: rock, iron or rubber. */
    static final int WEAPON = 4;
    /** The warriors to keep, as a share of all units: an index of {@link #ARMY_SHARES}. */
    static final int ARMY = 5;
    /** What the army does: defend the base, attack, or pull back to it. */
    static final int POSTURE = 6;
    /** Whether to have a chieftain. */
    static final int CHIEF = 7;

    static final int COUNT = 8;
    /** The choices of each head. */
    private static final int[] SIZES = {4, 5, 5, 4, 3, 5, 3, 2};
    /** The sum of {@link #SIZES}: the policy's logits. */
    static final int LOGITS = 31;

    static final int BUILD_NONE = 0;
    static final int BUILD_QUARTERS = 1;
    static final int BUILD_ARMORY = 2;
    static final int BUILD_TOWER = 3;

    static final int POSTURE_DEFEND = 0;
    static final int POSTURE_ATTACK = 1;
    static final int POSTURE_RETREAT = 2;

    /** Gatherer weights for wood, rock, iron and rubber. */
    private static final int[][] GATHER_MIXES = {{5, 3, 2, 0}, {5, 1, 4, 0}, {7, 2, 1, 0}, {4, 0, 6, 0}, {4, 1, 3, 2}};
    private static final float[] GATHER_SHARES = {.1f, .2f, .3f, .45f, .6f};
    private static final float[] ARMORY_SHARES = {.25f, .45f, .65f, .85f};
    private static final float[] ARMY_SHARES = {0f, .15f, .3f, .5f, .7f};

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

    /**
     * The gatherers wanted on resource {@code resource} (wood, rock, iron, rubber) for the two gather choices, of
     * {@code peons} peons in all.
     */
    static int gatherers(int mix, int level, int resource, int peons) {
        int[] weights = GATHER_MIXES[mix];
        int sum = weights[0] + weights[1] + weights[2] + weights[3];
        return Math.round(GATHER_SHARES[level] * peons * weights[resource] / sum);
    }

    static float armoryShare(int choice) {
        return ARMORY_SHARES[choice];
    }

    static float armyShare(int choice) {
        return ARMY_SHARES[choice];
    }
}
