package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.player.campaign.NativeCampaign;
import com.oddlabs.tt.procedural.Landscape;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static com.oddlabs.tt.mapeditor.ObjectKind.ARMORY;
import static com.oddlabs.tt.mapeditor.ObjectKind.CHIEFTAIN;
import static com.oddlabs.tt.mapeditor.ObjectKind.IRON_WARRIOR;
import static com.oddlabs.tt.mapeditor.ObjectKind.PEON;
import static com.oddlabs.tt.mapeditor.ObjectKind.QUARTERS;
import static com.oddlabs.tt.mapeditor.ObjectKind.ROCK_WARRIOR;
import static com.oddlabs.tt.mapeditor.ObjectKind.RUBBER_WARRIOR;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.FIRST_SPELL;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.all;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.atStart;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.intro;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.lostWith;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.moreAtStart;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.victory;
import static com.oddlabs.tt.mapeditor.OriginalCampaign.wave;
import static com.oddlabs.tt.mapeditor.OriginalLevel.DIFFICULTIES;
import static com.oddlabs.tt.mapeditor.OriginalLevel.EASY;
import static com.oddlabs.tt.mapeditor.OriginalLevel.act;
import static com.oddlabs.tt.mapeditor.OriginalLevel.difficultyName;
import static com.oddlabs.tt.mapeditor.OriginalLevel.when;
import static com.oddlabs.tt.mapeditor.Scenario.NEUTRAL_TEAM;
import static com.oddlabs.tt.mapeditor.Scenario.Role.NEUTRAL;
import static com.oddlabs.tt.mapeditor.Scenario.Role.OPPONENT_HARD;
import static com.oddlabs.tt.mapeditor.Scenario.Role.PASSIVE;

/**
 * The Native campaign's islands (player.campaign.NativeIsland0 to 7), as {@link OriginalCampaign} writes them out.
 * Each method follows its island's init and start: the generator inputs and tribes first, then what start places
 * and sets off, in its order.
 */
final class OriginalNativeIslands {
    private static final int MAX_UNITS = NativeCampaign.MAX_UNITS;
    private static final Landscape.TerrainType VIKING = Landscape.TerrainType.VIKING;
    private static final Landscape.TerrainType NATIVE = Landscape.TerrainType.NATIVE;

    // The peons the campaign carries to each island, every captive freed: 10 to start with, and the captives of
    // islands 2 and 4.
    private static final int PEONS_2 = 10;
    private static final int PEONS_3 = 20;
    private static final int PEONS_5 = 30;

    private OriginalNativeIslands() {
    }

    static @NonNull List<CampaignFile.@NonNull Level> levels(OriginalCampaign.@NonNull Progress progress) {
        return OriginalCampaign.levels(progress, List.of(OriginalNativeIslands::island0,
                OriginalNativeIslands::island1, OriginalNativeIslands::island2, OriginalNativeIslands::island3,
                OriginalNativeIslands::island4, OriginalNativeIslands::island5, OriginalNativeIslands::island6,
                OriginalNativeIslands::island7));
    }

    private static @NonNull OriginalLevel level(int number, int meters, Landscape.@NonNull TerrainType terrain,
            float hills, float vegetation, float supplies, int seed, int tribes) {
        return new OriginalLevel("NativeIsland", number, new OriginalLevel.Island(meters, terrain, hills,
                vegetation, supplies, seed, tribes, MAX_UNITS), true);
    }

    /**
     * The Vikings land and burn the village; the chieftain gathers the peons left and finds a new home. The island
     * is the Vikings' last one.
     */
    private static @NonNull OriginalLevel island0() {
        OriginalLevel l = level(0, 1024, NATIVE, .75f, .65f, .85f, 25, 4);
        int you = l.natives(0, Scenario.Role.HUMAN);
        int reinforcements = l.natives(NEUTRAL_TEAM, NEUTRAL);
        int enemy = l.vikings(1, OPPONENT_HARD);
        int village = l.natives(0, NEUTRAL);
        int landing = l.area(179, 195, 20f, "original_area_landing");
        int village_area = l.area(143, 120, 16f, "original_area_village");
        intro(l).then(act(ActionKind.CAMERA_JUMP, Param.AREA, landing)).dialog("header0", "dialog0", 0)
                .then(act(ActionKind.CAMERA_JUMP, Param.AREA, village_area)).dialog("header1", "dialog1", 0);
        l.place(you, CHIEFTAIN, 140, 117);
        int quarters = l.place(village, QUARTERS, 135, 128);
        int armory = l.place(village, ARMORY, 143, 124);
        int[][] warriors = {{145, 127}, {149, 122}, {145, 119}, {150, 125}};
        for (int[] warrior : warriors)
            l.place(village, ROCK_WARRIOR, warrior[0], warrior[1]);
        // Peons to gather, fewer the harder the level.
        int peons_area = l.area(230, 108, 12f, "original_area_reinforcements");
        l.units(reinforcements, PEON, 6, 230, 108);
        int[] more_peons = {9, 4, 0};
        for (int d = 0; d < 2; d++) {
            atStart(l, DIFFICULTIES[d], "original_extra").then(act(ActionKind.SPAWN_UNITS, Param.PLAYER,
                    reinforcements, Param.UNIT_TYPE, PEON, Param.COUNT, more_peons[d], Param.AREA, peons_area));
        }
        l.place(enemy, CHIEFTAIN, 179, 195);
        l.units(enemy, IRON_WARRIOR, 45, 179, 195);
        l.units(enemy, RUBBER_WARRIOR, 15, 179, 195);
        l.trigger("original_raid", when(ConditionKind.GAME_STARTED))
                .then(act(ActionKind.ATTACK_PLAYER, Param.PLAYER, enemy, Param.TARGET_PLAYER, village, Param.COUNT,
                        61));
        victory(l).dialog("header5", "dialog5", 6).dialog("header6", "dialog6", 0).dialog("header7", "dialog7", 6)
                .then(act(ActionKind.VICTORY));
        OriginalVikingIslands.greatStatues(l);
        // Finding the peons: the Vikings sail off, leaving a few to start a camp, and the village is lost. The new
        // camp's first peons come in the same go as the Vikings leave, so the Vikings are never all gone.
        int camp = l.area(437, 140, 16f, "original_area_new_camp");
        int[] camp_peons = {5, 10, 15};
        OriginalLevel.Trigger found = l.trigger("original_found", when(ConditionKind.UNITS_IN_AREA, Param.PLAYER,
                you, Param.AREA, peons_area, Param.COUNT, 1))
                .then(act(ActionKind.CAMERA_JUMP, Param.AREA, peons_area)).dialog("header2", "dialog2", 0)
                .objective("objective1")
                .then(act(ActionKind.REMOVE_STATUES))
                .then(act(ActionKind.REMOVE_UNITS, Param.PLAYER, enemy))
                .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, enemy, Param.UNIT_TYPE, PEON, Param.COUNT,
                        camp_peons[0], Param.AREA, camp));
        for (int d = 1; d < DIFFICULTIES.length; d++) {
            OriginalLevel.Trigger more = l.trigger("original_new_camp", when(ConditionKind.GAME_STARTED),
                    difficultyName(DIFFICULTIES[d])).only(DIFFICULTIES[d]).inactive()
                    .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, enemy, Param.UNIT_TYPE, PEON, Param.COUNT,
                            camp_peons[d] - camp_peons[0], Param.AREA, camp));
            found.then(act(ActionKind.ACTIVATE_TRIGGER, Param.TRIGGER, more.id()));
        }
        found.then(act(ActionKind.KILL_UNITS, Param.PLAYER, village))
                .then(act(ActionKind.REMOVE_OBJECT, Param.OBJECT, quarters))
                .then(act(ActionKind.REMOVE_OBJECT, Param.OBJECT, armory))
                .dialog("header3", "dialog3", 2).dialog("header4", "dialog4", 0)
                .then(act(ActionKind.CHANGE_OWNER_AREA, Param.PLAYER, reinforcements, Param.AREA, peons_area,
                        Param.NEW_OWNER, you));
        return l;
    }

    /** Captives of the Vikings, freed once the guard that watches them falls; the Vikings attack again and again. */
    private static @NonNull OriginalLevel island1() {
        OriginalLevel l = level(1, 256, VIKING, .75f, 1f, .5f, 1, 3);
        int you = l.natives(0, Scenario.Role.HUMAN);
        int enemy = l.vikings(1, PASSIVE);
        int guards = l.vikings(1, NEUTRAL);
        int captives = l.natives(NEUTRAL_TEAM, NEUTRAL);
        int start = l.area(24, 86, 16f, "original_area_start");
        int captive_area = l.area(50, 96, 8f, "original_area_captives");
        intro(l).then(act(ActionKind.CAMERA_JUMP, Param.AREA, captive_area)).dialog("header0", "dialog0", 4)
                .dialog("header1", "dialog1", 0).then(act(ActionKind.CAMERA_JUMP, Param.AREA, start));
        l.place(you, CHIEFTAIN, 24, 86);
        l.units(you, ROCK_WARRIOR, 10, 24, 86);
        int[][] tied_up = {{48, 96}, {48, 95}, {48, 98}, {49, 98}, {50, 97}, {51, 96}, {51, 94}, {52, 96},
                {52, 94}, {50, 95}};
        for (int[] captive : tied_up)
            l.place(captives, PEON, captive[0], captive[1]);
        l.place(guards, IRON_WARRIOR, 45, 98);
        l.place(guards, IRON_WARRIOR, 47, 92);
        int watch = l.place(guards, IRON_WARRIOR, 54, 97);
        l.trigger("original_freed", when(ConditionKind.OBJECT_DESTROYED, Param.OBJECT, watch))
                .dialog("header2", "dialog2", 0).dialog("header3", "dialog3", 3).dialog("header4", "dialog4", 0)
                .dialog("header5", "dialog5", 3).objective("objective1")
                .then(act(ActionKind.CHANGE_OWNER_AREA, Param.PLAYER, captives, Param.AREA, captive_area,
                        Param.NEW_OWNER, you));
        victory(l).then(act(ActionKind.VICTORY));
        endlessWaves(l, enemy, 5, 10, 20);
        l.place(enemy, QUARTERS, 106, 54);
        l.place(enemy, ARMORY, 97, 50);
        l.units(enemy, IRON_WARRIOR, 10, 106, 54);
        l.guardTower(enemy, IRON_WARRIOR, 97, 39);
        l.guardTower(enemy, IRON_WARRIOR, 90, 52);
        l.guardTower(enemy, IRON_WARRIOR, 96, 63);
        return l;
    }

    /**
     * The waves of the Natives' first islands: a first one, then one every few minutes for the better part of an
     * hour.
     */
    private static void endlessWaves(@NonNull OriginalLevel l, int enemy, int first, int later, int defense) {
        wave(l, 1, enemy, new float[]{7, 5, 4}, all(first), all(later));
        float[][] minutes = {{11, 16, 21, 26, 31, 36, 41}, {8.5f, 13, 17, 21, 25, 29, 33, 37, 41},
                {7, 11, 15, 19, 23, 27, 31, 35, 39}};
        for (int w = 0; w < minutes[1].length; w++) {
            float[] at = new float[3];
            for (int d = 0; d < 3; d++)
                at[d] = w < minutes[d].length ? minutes[d][w] : 0;
            wave(l, w + 2, enemy, at, all(later), all(defense));
        }
    }

    /** Captives of the Vikings to keep alive; they join the tribe. */
    private static @NonNull OriginalLevel island2() {
        OriginalLevel l = level(2, 256, VIKING, .75f, 1f, 1f, 10, 3);
        int you = l.natives(0, Scenario.Role.HUMAN);
        int captives = l.natives(NEUTRAL_TEAM, NEUTRAL);
        int enemy = l.vikings(1, PASSIVE);
        l.base(enemy, true, true, 0, 0, OriginalLevel.Army.of(0, 10, 5, 0));
        intro(l).dialog("header0", "dialog0", 0);
        // The game counts the captives who lived; here they all did.
        victory(l).dialog("header1", "dialog1", 0, 10).then(act(ActionKind.VICTORY));
        l.place(you, CHIEFTAIN, 100, 73);
        l.units(you, PEON, PEONS_2, 100, 73);
        int[] prison = l.prison(enemy);
        l.units(captives, PEON, 10, prison[0], prison[1]);
        endlessWaves(l, enemy, 5, 10, 10);
        lostWith(l, captives);
        l.guardTower(enemy, IRON_WARRIOR, 42, 83);
        l.guardTower(enemy, IRON_WARRIOR, 63, 89);
        return l;
    }

    /**
     * No building here: past the Viking towers to Thor, who teaches the second spell to cast before him. Iron
     * warriors on easy, rock ones otherwise. The Vikings' camp sends him a warrior now and then.
     */
    private static @NonNull OriginalLevel island3() {
        OriginalLevel l = level(3, 512, VIKING, 1f, 1f, 0f, 808208041, 3);
        int you = l.natives(0, Scenario.Role.HUMAN);
        int enemy = l.vikings(1, NEUTRAL);
        int reinforcements = l.vikings(1, PASSIVE);
        int start = l.area(125, 222, 16f, "original_area_start");
        int thor = l.area(40, 40, 16f, "original_area_thor");
        intro(l).then(act(ActionKind.CAMERA_JUMP, Param.AREA, thor)).dialog("header0", "dialog0", 0)
                .then(act(ActionKind.CAMERA_JUMP, Param.AREA, start));
        l.place(you, CHIEFTAIN, 125, 222);
        l.units(you, PEON, 5, 125, 222);
        l.units(you, ROCK_WARRIOR, PEONS_3 - 5, 125, 222);
        atStart(l, EASY, "original_iron")
                .then(act(ActionKind.REMOVE_UNITS, Param.PLAYER, you, Param.UNIT_FILTER, UnitFilter.ROCK_WARRIORS,
                        Param.AREA, start))
                .then(act(ActionKind.SPAWN_UNITS, Param.PLAYER, you, Param.UNIT_TYPE, IRON_WARRIOR,
                        Param.COUNT, PEONS_3 - 5, Param.AREA, start));
        OriginalLevel.Trigger cast = l.trigger("original_spell", when(ConditionKind.MAGIC_USED, Param.AREA,
                l.area(40, 40, 20f, "original_area_spell"), Param.MAGIC, FIRST_SPELL)).inactive()
                .then(act(ActionKind.VICTORY));
        OriginalLevel.Trigger arrival = l.trigger("original_arrival", when(ConditionKind.UNITS_IN_AREA, Param.PLAYER,
                you, Param.UNIT_FILTER, UnitFilter.CHIEFTAIN, Param.AREA, thor, Param.COUNT, 1),
                CampaignEditor.i18n("original_area_thor"));
        for (int i = 1; i <= 7; i++)
            arrival.dialog("header" + i, "dialog" + i, i % 2 == 1 ? 7 : 0);
        arrival.objective("objective1").then(act(ActionKind.ACTIVATE_TRIGGER, Param.TRIGGER, cast.id()))
                .dialog("header8", "dialog8", 0);
        l.place(reinforcements, QUARTERS, 96, 145);
        l.place(reinforcements, ARMORY, 126, 135);
        l.units(reinforcements, IRON_WARRIOR, 30, 126, 135);
        int[][] towers = {{60, 60}, {110, 167}, {102, 127}, {67, 163}, {71, 189}, {92, 205}, {128, 186}, {191, 185},
                {145, 128}, {173, 90}, {161, 82}, {120, 140}, {124, 127}, {135, 140}, {100, 139}, {80, 97},
                {112, 76}};
        ObjectKind[] guards = {IRON_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR, RUBBER_WARRIOR,
                IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, RUBBER_WARRIOR,
                RUBBER_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR, IRON_WARRIOR};
        for (int i = 0; i < towers.length; i++)
            l.guardTower(enemy, guards[i], towers[i][0], towers[i][1]);
        int[][] resistance = {{180, 155}, {178, 145}, {178, 151}, {180, 153}, {103, 93}, {99, 99}, {113, 98},
                {116, 98}, {83, 170}, {110, 199}, {186, 215}, {186, 211}, {53, 105}, {51, 107}, {53, 110},
                {53, 101}};
        for (int i = 0; i < resistance.length; i++)
            l.place(enemy, i % 2 == 0 ? ROCK_WARRIOR : IRON_WARRIOR, resistance[i][0], resistance[i][1]);
        // Ten warriors in all, one each so often, the more often the harder the level. They are the Vikings' own
        // here, where the game hands each one over to the tribe at Thor as it sets out.
        int thor_guard = l.area(62, 62, 6f, "original_area_thor_guard");
        int[] seconds = {120, 60, 30};
        for (int d = 0; d < DIFFICULTIES.length; d++) {
            OriginalLevel.Trigger send = l.trigger("original_reinforcements", when(ConditionKind.TIME_ELAPSED,
                    Param.SECONDS, seconds[d]), difficultyName(DIFFICULTIES[d])).only(DIFFICULTIES[d]).repeat()
                    .then(act(ActionKind.ATTACK_AREA, Param.PLAYER, reinforcements, Param.COUNT, 1, Param.AREA,
                            thor_guard));
            l.trigger("original_reinforcements_end", when(ConditionKind.TIME_ELAPSED, Param.SECONDS,
                    seconds[d] * 10 + seconds[d] / 2), difficultyName(DIFFICULTIES[d])).only(DIFFICULTIES[d])
                    .then(act(ActionKind.DEACTIVATE_TRIGGER, Param.TRIGGER, send.id()));
        }
        return l;
    }

    /** Hold out for a quarter of an hour, keeping the captives alive; they join the tribe. */
    private static @NonNull OriginalLevel island4() {
        int minutes = 15;
        OriginalLevel l = level(4, 512, VIKING, .8f, .8f, .8f, 19 * 19, 3);
        int you = l.natives(0, Scenario.Role.HUMAN);
        int captives = l.natives(0, NEUTRAL);
        int enemy = l.vikings(1, PASSIVE);
        l.base(enemy, true, true, 0, 0, OriginalLevel.Army.of(0, 10, 30, 0));
        intro(l).dialog("header0", "dialog0", 2).dialog("header1", "dialog1", 0);
        l.place(you, CHIEFTAIN, 45, 44);
        l.units(you, PEON, PEONS_3, 45, 44);
        l.units(captives, PEON, 10, 39, 38);
        int captive_area = l.area(39, 38, 10f, "original_area_captives");
        l.trigger("original_held", when(ConditionKind.TIME_ELAPSED, Param.SECONDS, minutes * 60), minutes)
                .then(act(ActionKind.VICTORY));
        int[][] towers = {{125, 161}, {125, 150}, {136, 153}, {180, 184}, {158, 154}, {163, 177}, {173, 175},
                {165, 167}, {104, 192}, {108, 185}, {103, 210}, {115, 205}, {155, 185}, {145, 171}, {108, 150},
                {130, 189}, {82, 170}, {65, 167}};
        for (int[] tower : towers)
            l.guardTower(enemy, RUBBER_WARRIOR, tower[0], tower[1]);
        l.trigger("original_setup", when(ConditionKind.GAME_STARTED))
                .then(act(ActionKind.REFILL_ARMORY, Param.PLAYER, enemy));
        // The first wave goes for the captives as well.
        int[] first = {5, 10, 20};
        for (int d = 0; d < DIFFICULTIES.length; d++) {
            l.trigger("original_captive_attack", when(ConditionKind.TIME_ELAPSED, Param.SECONDS, 210),
                    difficultyName(DIFFICULTIES[d])).only(DIFFICULTIES[d])
                    .then(act(ActionKind.ATTACK_AREA, Param.PLAYER, enemy, Param.COUNT, first[d], Param.AREA,
                            captive_area));
        }
        OriginalVikingIslands.holdOutWaves(l, enemy);
        lostWith(l, captives);
        return l;
    }

    /** A Viking opponent. */
    private static @NonNull OriginalLevel island5() {
        OriginalLevel l = level(5, 512, VIKING, 1f, 1f, 1f, 4, 2);
        l.natives(0, Scenario.Role.HUMAN);
        int enemy = l.vikings(1, OPPONENT_HARD);
        l.playerStart(new OriginalLevel.Army(true, PEONS_5, 0, 0, 0));
        int[] peons = {5, 10, 25};
        l.base(enemy, true, true, 0, 0, OriginalLevel.Army.of(peons[0], 0, 0, 0));
        int[] start = l.start(enemy);
        moreAtStart(l, enemy, PEON, l.area(start[0], start[1], 20f, "original_area_camp",
                Scenario.playerName(enemy)), peons);
        intro(l).dialog("header0", "dialog0", 0);
        victory(l).dialog("header1", "dialog1", 8).then(act(ActionKind.VICTORY));
        return l;
    }

    /** With a native ally against two Viking tribes; the ally must live. */
    private static @NonNull OriginalLevel island6() {
        OriginalLevel l = level(6, 1024, VIKING, .5f, .8f, .9f, 44, 4);
        l.natives(0, Scenario.Role.HUMAN);
        int friend = l.natives(0, OPPONENT_HARD);
        int enemy0 = l.vikings(1, OPPONENT_HARD);
        int enemy1 = l.vikings(1, OPPONENT_HARD);
        l.playerStart(new OriginalLevel.Army(true, PEONS_5, 0, 0, 0));
        l.base(friend, true, true, 2, 2, OriginalLevel.Army.of(1, 0, 2, 0));
        int[] peons = {10, 20, 35};
        for (int enemy : new int[]{enemy0, enemy1}) {
            l.base(enemy, true, false, 1, 1, OriginalLevel.Army.of(peons[0], 0, 0, 1));
            int[] start = l.start(enemy);
            moreAtStart(l, enemy, PEON, l.area(start[0], start[1], 20f, "original_area_camp",
                    Scenario.playerName(enemy)), peons);
        }
        intro(l).dialog("header0", "dialog0", 0).dialog("header1", "dialog1", 1).dialog("header2", "dialog2", 0)
                .dialog("header3", "dialog3", 1);
        victory(l).dialog("header4", "dialog4", 4).then(act(ActionKind.VICTORY));
        lostWith(l, friend);
        return l;
    }

    /** The last island: the Vikings' stronghold and its statues. */
    private static @NonNull OriginalLevel island7() {
        OriginalLevel l = level(7, 512, VIKING, .75f, 1f, .75f, 925, 2);
        l.natives(0, Scenario.Role.HUMAN);
        int enemy = l.vikings(1, OPPONENT_HARD);
        l.playerStart(new OriginalLevel.Army(true, PEONS_5, 0, 0, 0));
        int[] peons = {10, 20, 40};
        l.base(enemy, false, false, 0, 0, OriginalLevel.Army.of(peons[0], 0, 0, 0));
        int[] start = l.start(enemy);
        moreAtStart(l, enemy, PEON, l.area(start[0], start[1], 20f, "original_area_camp",
                Scenario.playerName(enemy)), peons);
        intro(l).dialog("header0", "dialog0", 5).dialog("header1", "dialog1", 0);
        victory(l).then(act(ActionKind.VICTORY));
        l.place(enemy, CHIEFTAIN, 97, 60);
        l.place(enemy, QUARTERS, 105, 56);
        l.place(enemy, ARMORY, 108, 79);
        int[][] towers = {{101, 64}, {90, 59}, {87, 70}, {93, 81}, {109, 89}, {115, 90}, {123, 93}, {132, 78},
                {106, 118}, {112, 119}, {126, 117}};
        for (int[] tower : towers)
            l.guardTower(enemy, IRON_WARRIOR, tower[0], tower[1]);
        // The hoards of three Viking islands piled up, the great statue last.
        int[][] statues = {{98, 62}, {94, 67}, {83, 58}, {93, 49}, {97, 59}, {84, 61}, {96, 49}, {100, 49},
                {84, 67}, {83, 64}, {95, 50}, {91, 63}, {97, 50}, {93, 51}, {93, 65}, {98, 54}, {96, 51}, {96, 54},
                {94, 52}, {97, 56}, {94, 59}, {91, 53}, {88, 57}, {93, 53}, {94, 53}, {90, 54}, {91, 54}, {93, 61},
                {93, 54}, {94, 63}, {90, 55}, {92, 55}, {94, 55}, {91, 51}};
        for (int[] statue : statues)
            l.statue(statue[0], statue[1]);
        return l;
    }
}
