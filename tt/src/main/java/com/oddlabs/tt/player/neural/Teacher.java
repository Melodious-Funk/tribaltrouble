package com.oddlabs.tt.player.neural;

import com.oddlabs.tt.aikit.AiParams;
import com.oddlabs.tt.model.Race;
import org.jspecify.annotations.NonNull;

/**
 * A scripted choice for every head ({@link Heads}): the plan the policy starts from. The policy's logits get
 * {@link NeuralAI}'s {@code prior} added on the choices advised here, so a net that has learned nothing plays this
 * plan, and training only has to find where to leave it. The plan: breed in several quarters with few peons each,
 * gather 10 wood and 8 iron, then 3 rock and 8 rubber as well, and more of whatever runs short as the peons grow;
 * make iron weapons, and rubber ones beside them later, with a growing crew; attack as soon as the army is clearly
 * the larger, for good.
 *
 * <p>Its numbers are params ({@code t_...}) so that a batch can compare two values on the same games.
 */
final class Teacher {
    /** Stock in the armory below which a resource gets more gatherers, and above which fewer. */
    private static final int WOOD_SHORT = 12;
    private static final int WOOD_SURPLUS = 60;
    private static final int ORE_SHORT = 5;
    private static final int ORE_SURPLUS = 30;
    /** Gatherers asked for that are not out yet, at most, for more to be asked for. */
    private static final int MISSING_GATHERERS = 4;
    /**
     * The standard gatherers of wood, rock, iron and rubber for {@link #STANDARD_PEONS} peons; with more peons each
     * may grow in proportion. Rock and rubber only for rubber weapons.
     */
    private static final int[] STANDARD_GATHERERS = {10, 3, 8, 8};
    private static final double STANDARD_PEONS = 60;
    /** Seconds before rubber weapons are made that rock and rubber are gathered for them. */
    private static final int RUBBER_LEAD = 120;

    /** The quarters to build before the first towers, and in all. */
    private final int early_quarters;
    private final int late_quarters;
    private final int towers;
    /** {@link Heads#QUARTERS_KEEP}'s choice while the player grows. */
    private final int quarters_keep;
    /** The most gatherers in all, as a share of the peons: the rest breed, build and make weapons. */
    private final double gather_cap;
    /** The weapons made, Heads.WEAPONS_*, from {@link #rubber_second} on; iron alone before. */
    private final int weapons;
    /**
     * The game second from which the weapons of {@link #weapons} are made. A rubber weapon is 120 seconds at the
     * forge and an iron one 80: the first waves are met with iron while the gatherers stock rubber.
     */
    private final int rubber_second;
    /** The share of the peons outside the quarters that the armory keeps at the forge. */
    private final double forge_share;
    /** The warriors an attack needs at least, and how many times the enemy's strength they must be. */
    private final int attack_warriors;
    private final double attack_ratio;
    /** An attack is called off below this many times the enemy's strength. */
    private final double retreat_ratio;
    /** The game second from which a chieftain is trained; negative for never. */
    private final int chieftain_second;

    Teacher(@NonNull AiParams params) {
        early_quarters = params.getInt("t_quarters", 2);
        late_quarters = params.getInt("t_late_quarters", 6);
        towers = params.getInt("t_towers", 2);
        quarters_keep = params.getInt("t_keep", 1);
        gather_cap = params.getDouble("t_gather_cap", 0.5);
        weapons = params.getInt("t_weapons", Heads.WEAPONS_IRON_RUBBER);
        rubber_second = params.getInt("t_rubber", 720);
        forge_share = params.getDouble("t_forge", 0.3);
        attack_warriors = params.getInt("t_attack", 15);
        attack_ratio = params.getDouble("t_ratio", 1.3);
        retreat_ratio = params.getDouble("t_retreat", 0.4);
        chieftain_second = params.getInt("t_chief", 300);
    }

    /** Fills {@code advice} with the scripted choice of each head; {@code plan} holds the choices in force. */
    void advise(@NonNull Sight see, int second, int @NonNull [] plan, int @NonNull [] gather_targets, int max_units,
            int @NonNull [] advice) {
        int quarters = see.buildings(Race.BUILDING_QUARTERS);
        int armories = see.buildings(Race.BUILDING_ARMORY);
        int built_towers = see.buildings(Race.BUILDING_TOWER);
        boolean full = see.units >= max_units - 5;
        int peons = see.peons();

        if (see.armories.isEmpty()) {
            // the skill layer builds the first quarters and armory with every peon
            advice[Heads.BUILD] = armories == 0 ? Heads.BUILD_ARMORY : Heads.BUILD_NONE;
        } else if (quarters < early_quarters) {
            advice[Heads.BUILD] = Heads.BUILD_QUARTERS;
        } else if (built_towers < towers) {
            advice[Heads.BUILD] = Heads.BUILD_TOWER;
        } else if (quarters < late_quarters && !full) {
            advice[Heads.BUILD] = Heads.BUILD_QUARTERS;
        } else {
            advice[Heads.BUILD] = Heads.BUILD_NONE;
        }

        // at the unit limit nothing is bred: everyone to the armory
        advice[Heads.QUARTERS_KEEP] = see.armories.isEmpty() ? 4 : full ? 0 : quarters_keep;
        // an iron weapon is 80 seconds of one peon at the forge, and about 50 of gatherers: the forge is most of the
        // work. Under attack everyone who can get a weapon takes it.
        double forge = forge_share * (peons - see.in_quarters);
        int keep = 0;
        while (keep + 1 < Heads.size(Heads.ARMORY_KEEP) && Heads.armoryKeep(keep + 1) <= forge) {
            keep++;
        }
        advice[Heads.ARMORY_KEEP] = see.threats > 0 ? 0 : keep;
        advice[Heads.WEAPON] = second >= rubber_second ? weapons : Heads.WEAPONS_IRON;
        gather(see, second, peons, gather_targets, advice);

        int mine = see.warriors();
        int theirs = see.enemyWarriors() + 4 * see.enemy_towers + 6 * see.enemy_chieftains;
        if (plan[Heads.POSTURE] == Heads.POSTURE_ATTACK) {
            // an attack that is called back has to walk the whole way again: only when it is lost
            advice[Heads.POSTURE] = mine >= retreat_ratio * theirs
                    || full ? Heads.POSTURE_ATTACK : Heads.POSTURE_DEFEND;
        } else {
            boolean stronger = mine >= attack_warriors && mine >= attack_ratio * theirs;
            advice[Heads.POSTURE] = stronger || (full
                    && mine >= attack_warriors) ? Heads.POSTURE_ATTACK : Heads.POSTURE_DEFEND;
        }
        advice[Heads.CHIEF] = chieftain_second >= 0 && second >= chieftain_second && see.quarters.size() >= 2 ? 1 : 0;
    }

    /**
     * The gather heads: per resource, more gatherers while its stock in the armory runs short, fewer while it piles
     * up, up to {@link #STANDARD_GATHERERS} grown with the peons. More only while the gatherers in all stay within
     * {@link #gather_cap} of the peons, and while the armory has sent out nearly all it was asked for.
     */
    private void gather(@NonNull Sight see, int second, int peons, int @NonNull [] targets,
            int @NonNull [] advice) {
        // an iron weapon takes 2 wood and 1 iron, a rubber one 2 wood, 1 rock, 1 iron and 1 rubber, a rock one 2 wood
        // and 1 rock; towers and repairs take wood
        boolean ores = weapons != Heads.WEAPONS_IRON && second >= rubber_second - RUBBER_LEAD;
        boolean[] used = {true, ores, weapons != Heads.WEAPONS_RUBBER || ores, ores};
        double scale = Math.max(1.0, peons / STANDARD_PEONS);
        int total = 0;
        int missing = 0;
        for (int resource = 0; resource < Sight.RESOURCES; resource++) {
            total += targets[resource];
            missing += Math.max(0, targets[resource] - see.gatherers[resource]);
        }
        int cap = (int) Math.round(gather_cap * peons);
        for (int resource = 0; resource < Sight.RESOURCES; resource++) {
            int head = Heads.gatherHead(resource);
            int stock = see.stock[resource];
            boolean wood = resource == 0;
            int choice = Heads.GATHER_SAME;
            int ceiling = used[resource] ? (int) Math.round(STANDARD_GATHERERS[resource] * scale) : 0;
            int step = Heads.gatherStep(Heads.GATHER_MORE);
            if (targets[resource] > ceiling || stock > (wood ? WOOD_SURPLUS : ORE_SURPLUS)) {
                choice = targets[resource] > 0 ? Heads.GATHER_FEWER : Heads.GATHER_SAME;
            } else if (stock < (wood ? WOOD_SHORT : ORE_SHORT) && targets[resource] + step <= ceiling
                    && total < cap && missing < MISSING_GATHERERS) {
                        choice = Heads.GATHER_MORE;
                        total += Heads.gatherStep(Heads.GATHER_MORE);
                    }
            advice[head] = choice;
        }
    }
}
