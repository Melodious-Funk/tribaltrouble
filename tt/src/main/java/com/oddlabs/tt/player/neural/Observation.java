package com.oddlabs.tt.player.neural;

import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;

/**
 * What the policy is given each decision: the numbers of {@link Sight}, scaled to about 0..1, and the action it chose
 * last. The trainer reads the size from the trajectory files; changing the list here means training a new net.
 */
final class Observation {
    /** The numbers of {@link #of} before the last action's one-hot code. */
    private static final int NUMBERS = 60;
    static final int SIZE = NUMBERS + Heads.LOGITS;
    /** The numbers of {@link #outcome}. */
    static final int OUTCOME_SIZE = 8;

    private Observation() {
    }

    static float @NonNull [] of(@NonNull Player me, @NonNull Sight see, int @NonNull [] last_action, boolean pending,
            int max_units, int max_buildings) {
        float[] o = new float[SIZE];
        int i = 0;
        float map_cells = me.getWorld().getHeightMap().getGridUnitsPerWorld();
        o[i++] = Math.min(1f, (float) GameTime.seconds(me.getWorld()) / 1800f);
        o[i++] = (float) see.units / max_units;
        o[i++] = see.idle_peons.size() / 30f;
        o[i++] = see.builders.size() / 30f;
        o[i++] = see.busy_peons / 30f;
        for (int gatherers : see.gatherers) {
            o[i++] = gatherers / 30f;
        }
        o[i++] = see.in_quarters / 100f;
        o[i++] = see.in_armories / 100f;
        for (int warriors : see.warrior_kinds) {
            o[i++] = warriors / 100f;
        }
        o[i++] = see.idle_army.size() / 100f;
        o[i++] = see.garrison / 6f;
        o[i++] = see.quarters.size() / 6f;
        o[i++] = see.armories.size() / 2f;
        o[i++] = see.towers.size() / 6f;
        for (int sites : see.site_kinds) {
            o[i++] = sites;
        }
        o[i++] = pending ? 1f : 0f;
        o[i++] = (float) me.getBuildingCountContainer().getNumSupplies() / max_buildings;
        for (int stock : see.stock) {
            o[i++] = Math.min(2f, stock / 40f);
        }
        for (int weapons : see.weapons) {
            o[i++] = Math.min(2f, weapons / 40f);
        }
        o[i++] = see.chieftain != null ? 1f : 0f;
        o[i++] = me.isTrainingChieftain() ? 1f : 0f;
        o[i++] = me.getUnitsKilled() / 250f;
        o[i++] = me.getUnitsLost() / 250f;
        o[i++] = me.getBuildingsDestroyed() / 10f;
        o[i++] = me.getBuildingsLost() / 10f;
        o[i++] = me.getTreeHarvested() / 2000f;
        o[i++] = me.getRockHarvested() / 1000f;
        o[i++] = me.getIronHarvested() / 1000f;
        o[i++] = me.getRubberHarvested() / 200f;

        o[i++] = (float) see.enemy_units / max_units;
        o[i++] = see.enemy_peons / 100f;
        for (int warriors : see.enemy_warrior_kinds) {
            o[i++] = warriors / 100f;
        }
        o[i++] = see.enemy_chieftains;
        o[i++] = see.enemy_quarters / 6f;
        o[i++] = see.enemy_armories / 2f;
        o[i++] = see.enemy_towers / 6f;
        o[i++] = see.enemy_sites / 3f;
        o[i++] = see.enemy_kills / 250f;
        o[i++] = see.enemy_lost / 250f;

        o[i++] = (float) Math.sqrt((double) Sight.squared(see.enemy_home_x - see.home_x,
                see.enemy_home_y - see.home_y)) / map_cells;
        o[i++] = see.enemy_army_distance / map_cells;
        o[i++] = see.threats / 50f;
        o[i++] = see.army_at_enemy / 50f;
        o[i++] = (see.warriors() - see.enemyWarriors()) / 100f;
        o[i++] = (see.units - see.enemy_units) / (float) max_units;
        o[i++] = see.sites.isEmpty() ? 0f : 1f;
        if (i != NUMBERS) {
            throw new IllegalStateException("the observation has " + i + " numbers, not " + NUMBERS);
        }
        for (int head = 0; head < Heads.COUNT; head++) {
            if (last_action[head] >= 0) {
                o[NUMBERS + Heads.offset(head) + last_action[head]] = 1f;
            }
        }
        return o;
    }

    /**
     * The plain counts the trainer shapes its reward from: own units, warriors, buildings and kills, then the
     * enemies' (their kills are the units this player lost to them).
     */
    static float @NonNull [] outcome(@NonNull Player me, @NonNull Sight see) {
        return new float[]{see.units, see.warriors() + see.garrison, see.quarters.size() + see.armories.size() + see.towers.size(), me.getUnitsKilled(), see.enemy_units, see.enemyWarriors(), see.enemy_quarters + see.enemy_armories + see.enemy_towers, see.enemy_kills};
    }
}
