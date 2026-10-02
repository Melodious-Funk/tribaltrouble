package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.player.campaign.VikingCampaign;
import com.oddlabs.tt.procedural.Landscape;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static com.oddlabs.tt.mapeditor.ObjectKind.CHIEFTAIN;
import static com.oddlabs.tt.mapeditor.ObjectKind.IRON_WARRIOR;
import static com.oddlabs.tt.mapeditor.ObjectKind.PEON;
import static com.oddlabs.tt.mapeditor.ObjectKind.ROCK_WARRIOR;
import static com.oddlabs.tt.mapeditor.ObjectKind.RUBBER_WARRIOR;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.FIRST_SPELL;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.IRON_WARRIORS;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.SECOND_SPELL;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.all;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.atStart;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.intro;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.lostWith;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.moreAtStart;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.victory;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.wave;
import static com.oddlabs.tt.mapeditor.OriginalLevel.EASY;
import static com.oddlabs.tt.mapeditor.OriginalLevel.HARD;
import static com.oddlabs.tt.mapeditor.OriginalLevel.NORMAL;
import static com.oddlabs.tt.mapeditor.OriginalLevel.act;
import static com.oddlabs.tt.mapeditor.OriginalLevel.when;
import static com.oddlabs.tt.mapeditor.Scenario.NEUTRAL_TEAM;
import static com.oddlabs.tt.mapeditor.Scenario.Role.NEUTRAL;
import static com.oddlabs.tt.mapeditor.Scenario.Role.OPPONENT_EASY;
import static com.oddlabs.tt.mapeditor.Scenario.Role.OPPONENT_HARD;
import static com.oddlabs.tt.mapeditor.Scenario.Role.OPPONENT_NORMAL;
import static com.oddlabs.tt.mapeditor.Scenario.Role.PASSIVE;

/**
 * The Viking campaign's islands (player.campaign.VikingIsland0 to 14), as {@link OriginalCampaign} writes them out.
 * Each method follows its island's init and start: the generator inputs and tribes first, then what start places
 * and sets off, in its order.
 */
final class OriginalVikingIslands {
    private static final int MAX_UNITS = VikingCampaign.MAX_UNITS;
    private static final Landscape.TerrainType NATIVE = Landscape.TerrainType.NATIVE;

    // The army the campaign carries to each island, every captive freed: 10 peons to start with, the captives of
    // islands 1, 6 and 12, and 5 rock warriors from island 5.
    private static final OriginalLevel.Army ARMY_0 = OriginalLevel.Army.of(10, 0, 0, 0);
    private static final OriginalLevel.Army ARMY_2 = OriginalLevel.Army.of(20, 0, 0, 0);
    private static final OriginalLevel.Army ARMY_6 = OriginalLevel.Army.of(20, 5, 0, 0);
    private static final OriginalLevel.Army ARMY_7 = OriginalLevel.Army.of(30, 5, 0, 0);
    private static final OriginalLevel.Army ARMY_13 = OriginalLevel.Army.of(40, 5, 0, 0);
    /** The units of the island 7 army, all of which some islands turn into warriors. */
    private static final int ARMY_7_UNITS = 35;

    private OriginalVikingIslands() {
    }

    static @NonNull List<CampaignFile.@NonNull Level> levels(OriginalCampaign.@NonNull Progress progress) {
        return OriginalCampaign.levels(progress, List.of(OriginalVikingIslands::island0,
                OriginalVikingIslands::island1, OriginalVikingIslands::island2, OriginalVikingIslands::island3,
                OriginalVikingIslands::island4, OriginalVikingIslands::island5, OriginalVikingIslands::island6,
                OriginalVikingIslands::island7, OriginalVikingIslands::island8, OriginalVikingIslands::island9,
                OriginalVikingIslands::island10, OriginalVikingIslands::island11, OriginalVikingIslands::island12,
                OriginalVikingIslands::island13, OriginalVikingIslands::island14));
    }

    private static @NonNull OriginalLevel level(int number, int meters, float hills, float vegetation,
            float supplies, int seed, int tribes) {
        return new OriginalLevel("VikingIsland", number, new OriginalLevel.Island(meters, NATIVE, hills, vegetation,
                supplies, seed, tribes, MAX_UNITS), false);
    }

    /** The first island: free the chieftain the natives hold. The player has no chieftain of their own here. */
    private static @NonNull OriginalLevel island0() {
        OriginalLevel l = level(0, 256, .5f, 1f, .1f, 45363, 3);
        int you = l.vikings(0, Scenario.Role.HUMAN);
        int chieftain = l.vikings(NEUTRAL_TEAM, NEUTRAL);
        int enemy = l.natives(1, PASSIVE);
        l.playerStart(ARMY_0);
        l.base(enemy, true, true, 0, 0, OriginalLevel.Army.of(0, 10, 5, 0));
        intro(l).dialog("header0", "dialog0", 1);
        victory(l).dialog("header1", "dialog1", 0).then(act(ActionKind.VICTORY));
        int[] prison = l.prison(enemy);
        l.place(chieftain, CHIEFTAIN, prison[0], prison[1]);
        l.trigger("original_setup", when(ConditionKind.GAME_STARTED))
                .then(act(ActionKind.REFILL_ARMORY, Param.PLAYER, enemy));
        // Gathering supplies sets the natives off, once for each kind, unless the level is played on easy.
        int[][] gathered = {{30, 30, 30}, {20, 15, 15}};
        int[] difficulties = {NORMAL, HARD};
        for (int d = 0; d < difficulties.length; d++) {
            for (int supply = 0; supply < 3; supply++) {
                l.trigger("original_gathered", when(ConditionKind.SUPPLIES_GATHERED, Param.PLAYER, you,
                        Param.SUPPLY_TYPE, supply, Param.COUNT, gathered[d][supply]),
                        Param.SUPPLY_TYPE.getChoices()[supply], OriginalLevel.difficultyName(difficulties[d]))
                        .only(difficulties[d])
                        .then(act(ActionKind.DEPLOY, Param.PLAYER, enemy, Param.DEPLOY_TYPE, IRON_WARRIORS,
                                Param.COUNT, 10))
                        .then(act(ActionKind.ATTACK_PLAYER, Param.PLAYER, enemy, Param.TARGET_PLAYER, you,
                                Param.COUNT, 10));
            }
        }
        lostWith(l, chieftain);
        return l;
    }

    /** The captives the natives hold join the army once the natives are beaten. */
    private static @NonNull OriginalLevel island1() {
        OriginalLevel l = level(1, 256, .75f, 1f, .5f, 97455, 3);
        l.vikings(0, Scenario.Role.HUMAN);
        int captives = l.vikings(NEUTRAL_TEAM, NEUTRAL);
        int enemy = l.natives(1, PASSIVE);
        l.playerStart(ARMY_0.withChieftain());
        l.base(enemy, true, true, 0, 0, OriginalLevel.Army.of(0, 10, 5, 0));
        intro(l).dialog("header0", "dialog0", 0);
        victory(l).dialog("header1", "dialog1", 3).then(act(ActionKind.VICTORY));
        int[] prison = l.prison(enemy);
        l.units(captives, PEON, 10, prison[0], prison[1]);
        wave(l, 1, enemy, new float[]{8, 5, 4}, all(5), all(10));
        wave(l, 2, enemy, new float[]{25, 8.5f, 7}, all(10), all(10));
        lostWith(l, captives);
        return l;
    }

    /** Rubber warriors guard the natives; beating them brings rubber weapons. */
    private static @NonNull OriginalLevel island2() {
        OriginalLevel l = level(2, 256, .65f, 1f, .7f, 447363, 2);
        l.vikings(0, Scenario.Role.HUMAN);
        int enemy = l.natives(1, PASSIVE);
        l.playerStart(ARMY_2.withChieftain());
        int[] rubber = {10, 20, 30};
        l.base(enemy, true, true, 0, 0, OriginalLevel.Army.of(0, 0, 0, rubber[0]));
        int[] start = l.start(enemy);
        moreAtStart(l, enemy, RUBBER_WARRIOR, l.area(start[0], start[1], 20f, "original_area_camp",
                Scenario.playerName(enemy)), rubber);
        intro(l).dialog("header0", "dialog0", 0);
        victory(l).dialog("header1", "dialog1", 0).then(act(ActionKind.VICTORY));
        wave(l, 1, enemy, new float[]{10, 6, 4.5f}, new int[]{3, 5, 7}, new int[]{6, 10, 13});
        wave(l, 2, enemy, new float[]{27, 9, 7.5f}, new int[]{6, 10, 13}, new int[]{10, 10, 20});
        return l;
    }

    /** A native opponent with a guarded tower and a hoard of statues. */
    private static @NonNull OriginalLevel island3() {
        OriginalLevel l = level(3, 512, .75f, 1f, .5f, 96443, 2);
        l.vikings(0, Scenario.Role.HUMAN);
        // A normal opponent on easy, else a hard one.
        int enemy = l.natives(1, OPPONENT_HARD);
        l.playerStart(ARMY_2.withChieftain());
        int[] peons = {2, 5, 15};
        l.base(enemy, true, true, 1, 1, OriginalLevel.Army.of(peons[0], 0, 5, 0));
        int[] start = l.start(enemy);
        moreAtStart(l, enemy, PEON, l.area(start[0], start[1], 20f, "original_area_camp",
                Scenario.playerName(enemy)), peons);
        atStart(l, EASY, "original_ai").then(act(ActionKind.SET_AI, Param.PLAYER, enemy, Param.ROLE,
                OPPONENT_NORMAL));
        intro(l).dialog("header0", "dialog0", 0);
        victory(l).then(act(ActionKind.VICTORY));
        int[][] statues = {{134, 29}, {130, 28}, {130, 34}, {125, 37}, {121, 32}, {124, 28}, {136, 38}, {139, 33}};
        for (int[] statue : statues)
            l.statue(statue[0], statue[1]);
        return l;
    }

    /** A chieftain to free from natives who refill their armory without end; beating them brings the first spell. */
    private static @NonNull OriginalLevel island4() {
        OriginalLevel l = level(4, 256, .65f, 1f, .5f, 786433, 3);
        l.vikings(0, Scenario.Role.HUMAN);
        int captive = l.vikings(NEUTRAL_TEAM, NEUTRAL);
        int enemy = l.natives(1, PASSIVE);
        l.playerStart(ARMY_2.withChieftain());
        l.base(enemy, true, true, 2, 1, new OriginalLevel.Army(true, 10, 10, 10, 10));
        intro(l).dialog("header0", "dialog0", 0);
        wave(l, 1, enemy, new float[]{7, 4.5f, 4}, new int[]{4, 7, 11}, new int[]{8, 12, 16});
        wave(l, 2, enemy, new float[]{11, 8, 6.5f}, new int[]{8, 12, 16}, new int[]{10, 12, 20});
        victory(l).dialog("header1", "dialog1", 5).then(act(ActionKind.VICTORY));
        int[] prison = l.prison(enemy);
        l.place(captive, CHIEFTAIN, prison[0], prison[1]);
        l.trigger("original_setup", when(ConditionKind.GAME_STARTED))
                .then(act(ActionKind.REFILL_ARMORY, Param.PLAYER, enemy))
                .then(act(ActionKind.REINFORCEMENTS, Param.PLAYER, enemy, Param.DEPLOY_TYPE, IRON_WARRIORS));
        lostWith(l, captive);
        return l;
    }

    /** With a Viking ally against two native tribes; the ally must live. */
    private static @NonNull OriginalLevel island5() {
        OriginalLevel l = level(5, 512, .85f, 1f, .9f, 89864, 4);
        l.vikings(0, Scenario.Role.HUMAN);
        int friend = l.vikings(0, OPPONENT_HARD);
        int enemy0 = l.natives(1, OPPONENT_HARD);
        int enemy1 = l.natives(1, OPPONENT_HARD);
        l.playerStart(ARMY_2.withChieftain());
        l.base(friend, false, false, 0, 0, OriginalLevel.Army.of(25, 5, 0, 0));
        int[] peons = {5, 10, 25};
        for (int enemy : new int[]{enemy0, enemy1}) {
            l.base(enemy, true, false, 1, 1, OriginalLevel.Army.of(peons[0], 0, 0, 1));
            int[] start = l.start(enemy);
            moreAtStart(l, enemy, PEON, l.area(start[0], start[1], 20f, "original_area_camp",
                    Scenario.playerName(enemy)), peons);
        }
        intro(l).dialog("header1", "dialog1", 5).dialog("header0", "dialog0", 0);
        victory(l).dialog("header2", "dialog2", 5).then(act(ActionKind.VICTORY));
        lostWith(l, friend);
        return l;
    }

    /** Stranded Vikings to keep alive while beating the natives; they join the army. */
    private static @NonNull OriginalLevel island6() {
        OriginalLevel l = level(6, 256, .75f, 1f, .5f, 13462, 3);
        int you = l.vikings(0, Scenario.Role.HUMAN);
        int stranded = l.vikings(0, NEUTRAL);
        int enemy = l.natives(1, OPPONENT_HARD);
        l.playerStart(ARMY_6.withChieftain());
        int[] peons = {1, 3, 15};
        l.base(enemy, true, true, 0, 0, new OriginalLevel.Army(true, peons[0], 0, 0, 0));
        int[] start = l.start(enemy);
        moreAtStart(l, enemy, PEON, l.area(start[0], start[1], 20f, "original_area_camp",
                Scenario.playerName(enemy)), peons);
        intro(l).dialog("header1", "dialog1", 2).dialog("header0", "dialog0", 0);
        int[] prison = l.prison(you);
        l.units(stranded, PEON, 10, prison[0], prison[1]);
        lostWith(l, stranded);
        l.guardTower(enemy, IRON_WARRIOR, 39, 43);
        l.guardTower(enemy, IRON_WARRIOR, 35, 53);
        // The game counts the stranded who lived; here they all did.
        victory(l).dialog("new_units_header", "new_units", 0, 10).then(act(ActionKind.VICTORY));
        return l;
    }

    /** Two native tribes and their statues. */
    private static @NonNull OriginalLevel island7() {
        OriginalLevel l = level(7, 512, .75f, 1f, .5f, 725925, 3);
        l.vikings(0, Scenario.Role.HUMAN);
        // Easy opponents, hard ones on hard.
        int enemy0 = l.natives(1, OPPONENT_EASY);
        int enemy1 = l.natives(1, OPPONENT_EASY);
        l.playerStart(ARMY_7.withChieftain());
        int[] peons = {5, 15, 20};
        for (int enemy : new int[]{enemy0, enemy1}) {
            l.base(enemy, true, true, 0, 0, OriginalLevel.Army.of(peons[0], 0, 0, 0));
            int[] start = l.start(enemy);
            moreAtStart(l, enemy, PEON, l.area(start[0], start[1], 20f, "original_area_camp",
                    Scenario.playerName(enemy)), peons);
        }
        atStart(l, HARD, "original_ai")
                .then(act(ActionKind.SET_AI, Param.PLAYER, enemy0, Param.ROLE, OPPONENT_HARD))
                .then(act(ActionKind.SET_AI, Param.PLAYER, enemy1, Param.ROLE, OPPONENT_HARD));
        intro(l).dialog("header0", "dialog0", 0);
        victory(l).then(act(ActionKind.VICTORY));
        l.guardTower(enemy0, IRON_WARRIOR, 83, 70);
        l.guardTower(enemy1, IRON_WARRIOR, 189, 74);
        int[][] statues = {{67, 64}, {70, 52}, {77, 63}, {82, 52}, {76, 75}, {205, 81}, {199, 42}, {197, 69},
                {194, 77}, {187, 70}, {188, 77}, {190, 65}};
        for (int[] statue : statues)
            l.statue(statue[0], statue[1]);
        return l;
    }

    /**
     * The long march: through native towers and two ambushes to the rally point, to cast the first spell there.
     * The armies the ambushes spring are tribes of their own here, on the natives' team, so a trigger can send them.
     */
    private static @NonNull OriginalLevel island8() {
        OriginalLevel l = level(8, 1024, 1f, 1f, 0f, 285914281, 3);
        int you = l.vikings(0, Scenario.Role.HUMAN);
        int lost = l.vikings(NEUTRAL_TEAM, NEUTRAL);
        int enemy = l.natives(1, NEUTRAL);
        int ambush1 = l.natives(1, NEUTRAL);
        int ambush2 = l.natives(1, NEUTRAL);
        l.base(enemy, false, false, 0, 0, OriginalLevel.Army.of(0, 0, 0, 1));
        int start = l.area(170, 160, 16f, "original_area_start");
        int rally = l.area(354, 478, 8f, "original_area_rally");
        int spell = l.area(354, 478, 15f, "original_area_spell");
        intro(l).then(act(ActionKind.CAMERA_JUMP, Param.AREA, rally)).dialog("header0", "dialog0", 0)
                .then(act(ActionKind.CAMERA_JUMP, Param.AREA, start));
        l.place(you, CHIEFTAIN, 170, 160);
        l.units(you, ROCK_WARRIOR, ARMY_7_UNITS, 170, 160);
        OriginalLevel.Trigger cast = l.trigger("original_spell", when(ConditionKind.MAGIC_USED, Param.AREA, spell,
                Param.MAGIC, FIRST_SPELL)).inactive().then(act(ActionKind.VICTORY));
        l.trigger("original_arrival", when(ConditionKind.UNITS_IN_AREA, Param.PLAYER, you, Param.UNIT_FILTER,
                UnitFilter.CHIEFTAIN, Param.AREA, rally, Param.COUNT, 1), CampaignEditor.i18n("original_area_rally"))
                .dialog("header1", "dialog1", 0).objective("objective1")
                .then(act(ActionKind.ACTIVATE_TRIGGER, Param.TRIGGER, cast.id()));
        int[][] towers = {{208, 210}, {124, 211}, {139, 223}, {171, 250}, {155, 244}, {68, 189}, {56, 187},
                {44, 190}, {180, 124}, {224, 269}, {302, 370}, {312, 394}, {301, 388}, {253, 236}, {278, 316},
                {261, 166}, {308, 467}, {192, 252}, {203, 267}, {316, 435}};
        ObjectKind[] guards = {IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR,
                RUBBER_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR, IRON_WARRIOR,
                RUBBER_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR, ROCK_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR,
                RUBBER_WARRIOR, IRON_WARRIOR};
        for (int i = 0; i < towers.length; i++)
            l.guardTower(enemy, guards[i], towers[i][0], towers[i][1]);
        // The first ambush: killing the lone warrior sets its army on the spot.
        int bait1 = l.place(enemy, ROCK_WARRIOR, 240, 137);
        int[][] camps1 = {{213, 122}, {245, 170}, {239, 177}, {286, 113}, {289, 141}};
        ObjectKind[][] armies1 = {
                {RUBBER_WARRIOR, ROCK_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR},
                {RUBBER_WARRIOR, RUBBER_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR},
                {RUBBER_WARRIOR, ROCK_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR},
                {RUBBER_WARRIOR, ROCK_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR},
                {RUBBER_WARRIOR, ROCK_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR}};
        for (int i = 0; i < camps1.length; i++) {
            for (ObjectKind kind : armies1[i])
                l.place(ambush1, kind, camps1[i][0], camps1[i][1]);
        }
        l.trigger("original_ambush", when(ConditionKind.OBJECT_DESTROYED, Param.OBJECT, bait1), 1)
                .then(act(ActionKind.ATTACK_AREA, Param.PLAYER, ambush1, Param.COUNT, 25, Param.AREA,
                        l.area(238, 136, 8f, "original_area_target", 1)));
        // The second ambush, larger the harder the level.
        int bait2 = l.place(enemy, ROCK_WARRIOR, 347, 455);
        for (ObjectKind kind : new ObjectKind[]{ROCK_WARRIOR, ROCK_WARRIOR, IRON_WARRIOR, IRON_WARRIOR,
                RUBBER_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR})
            l.place(ambush2, kind, 364, 440);
        int camp_normal = l.area(365, 427, 6f, "original_area_army", 2);
        int camp_hard = l.area(366, 419, 6f, "original_area_army", 3);
        for (int difficulty : new int[]{NORMAL, HARD}) {
            atStart(l, difficulty, "original_extra")
                    .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, ambush2, Param.UNIT_TYPE, ROCK_WARRIOR,
                            Param.COUNT, 3, Param.AREA, camp_normal))
                    .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, ambush2, Param.UNIT_TYPE, IRON_WARRIOR,
                            Param.COUNT, 3, Param.AREA, camp_normal));
        }
        atStart(l, HARD, "original_extra")
                .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, ambush2, Param.UNIT_TYPE, ROCK_WARRIOR,
                        Param.COUNT, 3, Param.AREA, camp_hard))
                .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, ambush2, Param.UNIT_TYPE, IRON_WARRIOR,
                        Param.COUNT, 1, Param.AREA, camp_hard))
                .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, ambush2, Param.UNIT_TYPE, RUBBER_WARRIOR,
                        Param.COUNT, 3, Param.AREA, camp_hard));
        l.trigger("original_ambush", when(ConditionKind.OBJECT_DESTROYED, Param.OBJECT, bait2), 2)
                .then(act(ActionKind.ATTACK_AREA, Param.PLAYER, ambush2, Param.COUNT, 20, Param.AREA,
                        l.area(352, 480, 8f, "original_area_target", 2)));
        // Scattered resistance.
        Object[][] resistance = {
                {348, 315, RUBBER_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, ROCK_WARRIOR},
                {299, 321, RUBBER_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR},
                {300, 453, RUBBER_WARRIOR, ROCK_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR, IRON_WARRIOR, IRON_WARRIOR,
                        RUBBER_WARRIOR},
                {352, 456, IRON_WARRIOR}, {355, 459, IRON_WARRIOR}, {360, 461, IRON_WARRIOR},
                {365, 466, IRON_WARRIOR}, {367, 474, IRON_WARRIOR}, {369, 479, IRON_WARRIOR},
                {348, 455, IRON_WARRIOR}, {342, 459, IRON_WARRIOR}, {335, 467, IRON_WARRIOR},
                {334, 475, IRON_WARRIOR}, {345, 465, IRON_WARRIOR}, {346, 460, IRON_WARRIOR},
                {347, 467, IRON_WARRIOR},
                {331, 374, IRON_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR, IRON_WARRIOR},
                {348, 371, IRON_WARRIOR, RUBBER_WARRIOR},
                {399, 399, RUBBER_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR},
                {354, 474, IRON_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR,
                        RUBBER_WARRIOR}};
        for (Object[] group : resistance) {
            for (int i = 2; i < group.length; i++)
                l.place(enemy, (ObjectKind) group[i], (Integer) group[0], (Integer) group[1]);
        }
        // Lost Vikings, more of them the easier the level, who join the player's army when found.
        int[][] neutrals = {{267, 325}, {268, 324}, {267, 323}, {265, 325}, {265, 324}, {266, 326}, {267, 327},
                {269, 327}, {270, 327}, {268, 326}, {271, 325}, {270, 324}, {271, 328}, {269, 330}, {272, 323}};
        ObjectKind[] neutral_kinds = {RUBBER_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR,
                IRON_WARRIOR, RUBBER_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR, IRON_WARRIOR,
                RUBBER_WARRIOR, RUBBER_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR};
        for (int i = 0; i < 6; i++)
            l.place(lost, neutral_kinds[i], neutrals[i][0], neutrals[i][1]);
        int lost_area = l.area(268, 326, 16f, "original_area_lost");
        for (int difficulty : new int[]{EASY, NORMAL}) {
            atStart(l, difficulty, "original_extra")
                    .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, lost, Param.UNIT_TYPE, RUBBER_WARRIOR,
                            Param.COUNT, 2, Param.AREA, lost_area))
                    .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, lost, Param.UNIT_TYPE, IRON_WARRIOR,
                            Param.COUNT, 1, Param.AREA, lost_area));
        }
        atStart(l, EASY, "original_extra")
                .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, lost, Param.UNIT_TYPE, RUBBER_WARRIOR,
                        Param.COUNT, 4, Param.AREA, lost_area))
                .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, lost, Param.UNIT_TYPE, IRON_WARRIOR,
                        Param.COUNT, 2, Param.AREA, lost_area));
        l.trigger("original_found", when(ConditionKind.UNITS_IN_AREA, Param.PLAYER, you, Param.AREA, lost_area,
                Param.COUNT, 1))
                .dialog("header2", "dialog2", 4)
                .then(act(ActionKind.CHANGE_OWNER_AREA, Param.PLAYER, lost, Param.AREA, lost_area, Param.NEW_OWNER,
                        you));
        return l;
    }

    /** A native chieftain to keep alive while the natives' armory sends warrior after warrior. */
    private static @NonNull OriginalLevel island9() {
        OriginalLevel l = level(9, 256, 1f, .85f, .85f, 777777777, 3);
        l.vikings(0, Scenario.Role.HUMAN);
        int enemy = l.natives(1, PASSIVE);
        int chief_tribe = l.natives(NEUTRAL_TEAM, NEUTRAL);
        l.playerStart(ARMY_7.withChieftain());
        l.base(enemy, true, true, 0, 0, OriginalLevel.Army.of(0, 0, 0, 0));
        intro(l).dialog("header0", "dialog0", 0);
        l.place(chief_tribe, CHIEFTAIN, 56, 110);
        lostWith(l, chief_tribe);
        int[][] towers = {{50, 85}, {52, 81}, {54, 96}, {61, 104}, {57, 104}, {78, 90}, {72, 88}, {71, 83}};
        for (int[] tower : towers)
            l.guardTower(enemy, IRON_WARRIOR, tower[0], tower[1]);
        l.trigger("original_setup", when(ConditionKind.GAME_STARTED))
                .then(act(ActionKind.REFILL_ARMORY, Param.PLAYER, enemy))
                .then(act(ActionKind.DEPLOY, Param.PLAYER, enemy, Param.DEPLOY_TYPE, IRON_WARRIORS, Param.COUNT,
                        20))
                .then(act(ActionKind.REINFORCEMENTS, Param.PLAYER, enemy, Param.DEPLOY_TYPE, IRON_WARRIORS));
        victory(l).dialog("header1", "dialog1", 7).then(act(ActionKind.VICTORY));
        return l;
    }

    /**
     * No building here: past the towers and a rubber army to the statue, to learn and cast the second spell. Iron
     * warriors on easy, rock ones otherwise.
     */
    private static @NonNull OriginalLevel island10() {
        OriginalLevel l = level(10, 512, 1f, 1f, 0f, -1442873271, 2);
        int you = l.vikings(0, Scenario.Role.HUMAN);
        int enemy = l.natives(1, NEUTRAL);
        l.base(enemy, false, false, 0, 0, OriginalLevel.Army.of(0, 0, 0, 1));
        int start = l.area(142, 182, 16f, "original_area_start");
        int statue = l.area(173, 153, 6f, "original_area_statue");
        intro(l).then(act(ActionKind.CAMERA_JUMP, Param.AREA, l.area(177, 156, 8f, "original_area_view")))
                .dialog("header0", "dialog0", 0).then(act(ActionKind.CAMERA_JUMP, Param.AREA, start));
        l.place(you, CHIEFTAIN, 142, 182);
        l.units(you, PEON, 5, 142, 182);
        l.units(you, ROCK_WARRIOR, ARMY_7_UNITS - 5, 142, 182);
        atStart(l, EASY, "original_iron")
                .then(act(ActionKind.REMOVE_UNITS, Param.PLAYER, you, Param.UNIT_FILTER, UnitFilter.ROCK_WARRIORS,
                        Param.AREA, start))
                .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, you, Param.UNIT_TYPE, IRON_WARRIOR,
                        Param.COUNT, ARMY_7_UNITS - 5, Param.AREA, start));
        l.trigger("original_spell", when(ConditionKind.MAGIC_USED, Param.AREA, l.area(173, 153, 7f,
                "original_area_spell"), Param.MAGIC, SECOND_SPELL)).then(act(ActionKind.VICTORY));
        OriginalLevel.Trigger arrival = l.trigger("original_arrival", when(ConditionKind.UNITS_IN_AREA, Param.PLAYER,
                you, Param.UNIT_FILTER, UnitFilter.CHIEFTAIN, Param.AREA, statue, Param.COUNT, 1),
                CampaignEditor.i18n("original_area_statue"));
        for (int i = 1; i <= 11; i++)
            arrival.dialog("header" + i, "dialog" + i, i % 2 == 1 ? 0 : 8);
        arrival.objective("objective1");
        l.statue(173, 153);
        int[][] towers = {{177, 159}, {180, 176}, {165, 195}, {169, 198}, {152, 209}, {200, 197}, {199, 169}};
        ObjectKind[] guards = {IRON_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR,
                IRON_WARRIOR, IRON_WARRIOR};
        for (int i = 0; i < towers.length; i++)
            l.guardTower(enemy, guards[i], towers[i][0], towers[i][1]);
        int[][] blocking = {{173, 188, 5}, {175, 190, 5}, {178, 192, 5}, {185, 194, 5}, {181, 195, 9},
                {164, 203, 8}};
        for (int[] group : blocking)
            l.units(enemy, RUBBER_WARRIOR, group[2], group[0], group[1]);
        int[][] resistance = {{114, 163}, {118, 170}, {109, 153}, {122, 151}, {98, 137}, {93, 130}, {86, 132},
                {72, 146}, {158, 97}, {132, 118}, {157, 135}};
        for (int[] pair : resistance) {
            l.place(enemy, ROCK_WARRIOR, pair[0], pair[1]);
            l.place(enemy, IRON_WARRIOR, pair[0], pair[1]);
        }
        return l;
    }

    /** A long talk, then a Viking opponent behind rubber towers. */
    private static @NonNull OriginalLevel island11() {
        OriginalLevel l = level(11, 256, .75f, 1f, .85f, 83493473, 2);
        l.vikings(0, Scenario.Role.HUMAN);
        int enemy = l.vikings(1, OPPONENT_HARD);
        l.playerStart(ARMY_7.withChieftain());
        int[] peons = {10, 25, 40};
        l.base(enemy, true, false, 0, 0, OriginalLevel.Army.of(peons[0], 0, 2, 0));
        int[] start = l.start(enemy);
        moreAtStart(l, enemy, PEON, l.area(start[0], start[1], 20f, "original_area_camp",
                Scenario.playerName(enemy)), peons);
        int[] faces = {0, 6, 0, 6, 0, 6, 0, 3, 0};
        OriginalLevel.Trigger intro = intro(l);
        for (int i = 0; i < faces.length; i++)
            intro.dialog("header" + i, "dialog" + i, faces[i]);
        victory(l).then(act(ActionKind.VICTORY));
        int[][] towers = {{47, 22}, {54, 36}, {68, 36}, {80, 36}, {94, 30}};
        for (int[] tower : towers)
            l.guardTower(enemy, RUBBER_WARRIOR, tower[0], tower[1]);
        return l;
    }

    /** More stranded Vikings to keep alive; they join the army. */
    private static @NonNull OriginalLevel island12() {
        OriginalLevel l = level(12, 256, .5f, 1f, .57f, 67625656, 3);
        int you = l.vikings(0, Scenario.Role.HUMAN);
        int stranded = l.vikings(0, NEUTRAL);
        int enemy = l.natives(1, OPPONENT_HARD);
        l.playerStart(ARMY_7.withChieftain());
        int[] peons = {10, 20, 40};
        l.base(enemy, true, true, 0, 0, new OriginalLevel.Army(true, peons[0], 0, 0, 0));
        int[] start = l.start(enemy);
        moreAtStart(l, enemy, PEON, l.area(start[0], start[1], 20f, "original_area_camp",
                Scenario.playerName(enemy)), peons);
        intro(l).dialog("header1", "dialog1", 2).dialog("header0", "dialog0", 0);
        int[] prison = l.prison(you);
        l.units(stranded, PEON, 10, prison[0], prison[1]);
        lostWith(l, stranded);
        l.guardTower(enemy, IRON_WARRIOR, 39, 43);
        l.guardTower(enemy, IRON_WARRIOR, 35, 53);
        victory(l).dialog("new_units_header", "new_units", 0, 10).then(act(ActionKind.VICTORY));
        return l;
    }

    /** Hold out for a quarter of an hour against ever larger waves. */
    private static @NonNull OriginalLevel island13() {
        int minutes = 15;
        OriginalLevel l = level(13, 512, 1f, 1f, .8f, 16, 2);
        l.vikings(0, Scenario.Role.HUMAN);
        int enemy = l.natives(1, PASSIVE);
        l.playerStart(ARMY_13.withChieftain());
        l.base(enemy, true, true, 0, 0, OriginalLevel.Army.of(0, 10, 30, 0));
        intro(l).dialog("header0", "dialog0", 0, minutes);
        l.trigger("original_held", when(ConditionKind.TIME_ELAPSED, Param.SECONDS, minutes * 60), minutes)
                .then(act(ActionKind.VICTORY));
        int[][] towers = {{167, 60}, {171, 55}, {160, 60}, {142, 70}, {135, 72}, {130, 74}, {125, 76}, {120, 71},
                {115, 67}, {95, 68}, {93, 63}, {92, 57}, {90, 52}, {96, 38}, {99, 34}, {105, 24}, {164, 51},
                {103, 57}};
        for (int[] tower : towers)
            l.guardTower(enemy, RUBBER_WARRIOR, tower[0], tower[1]);
        l.trigger("original_setup", when(ConditionKind.GAME_STARTED))
                .then(act(ActionKind.REFILL_ARMORY, Param.PLAYER, enemy));
        holdOutWaves(l, enemy);
        return l;
    }

    /**
     * The six waves of the islands won by holding out (this one and the Natives' fourth), at the same times at
     * every difficulty.
     */
    static void holdOutWaves(@NonNull OriginalLevel l, int enemy) {
        float[] minutes = {3.5f, 4.5f, 6, 9, 11, 12.5f};
        int[][] attacks = {{5, 15, 20, 35, 35, 35}, {10, 30, 40, 70, 70, 70}, {20, 60, 80, 90, 90, 90}};
        for (int w = 0; w < minutes.length; w++) {
            int[] attackers = new int[3];
            int[] next = new int[3];
            for (int d = 0; d < 3; d++) {
                attackers[d] = attacks[d][w];
                next[d] = w + 1 < minutes.length ? attacks[d][w + 1] : 0;
            }
            wave(l, w + 1, enemy, all(minutes[w]), attackers, next);
        }
    }

    /** The last island: two native tribes and the great statue. */
    private static @NonNull OriginalLevel island14() {
        OriginalLevel l = level(14, 1024, .75f, .65f, .85f, 25, 3);
        int you = l.vikings(0, Scenario.Role.HUMAN);
        int enemy0 = l.natives(1, OPPONENT_HARD);
        // A normal opponent on easy, else a hard one.
        int enemy1 = l.natives(1, OPPONENT_HARD);
        int[] peons = {1, 5, 12};
        for (int enemy : new int[]{enemy0, enemy1}) {
            l.base(enemy, true, true, 0, 0, OriginalLevel.Army.of(peons[0], 0, 0, 0));
            int[] start = l.start(enemy);
            moreAtStart(l, enemy, PEON, l.area(start[0], start[1], 20f, "original_area_camp",
                    Scenario.playerName(enemy)), peons);
        }
        atStart(l, EASY, "original_ai").then(act(ActionKind.SET_AI, Param.PLAYER, enemy1, Param.ROLE,
                OPPONENT_NORMAL));
        intro(l).dialog("header0", "dialog0", 0);
        l.place(you, CHIEFTAIN, 236, 362);
        l.units(you, PEON, ARMY_13.peons(), 236, 362);
        l.units(you, ROCK_WARRIOR, ARMY_13.rock(), 236, 362);
        victory(l).then(act(ActionKind.VICTORY));
        greatStatues(l);
        return l;
    }

    /** The statues around the great statue, shared with the Natives' first island. */
    static void greatStatues(@NonNull OriginalLevel l) {
        int[][] statues = {{163, 126}, {130, 124}, {152, 138}, {152, 144}, {140, 140}, {143, 116}, {142, 131},
                {423, 174}, {408, 161}, {426, 156}, {418, 165}, {430, 165}, {419, 170}, {416, 156}};
        for (int[] statue : statues)
            l.statue(statue[0], statue[1]);
    }
}
