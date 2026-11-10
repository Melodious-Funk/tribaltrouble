import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.landscape.IslandInfo;
import com.oddlabs.tt.procedural.Landscape;
import com.oddlabs.tt.procedural.Landscape.TerrainType;

import java.util.Arrays;
import java.util.List;

/**
 * Checks Landscape.generateHeadless against the game's full constructor: every getter the world reads must return
 * the same values, for both terrains, the map sizes given and a spread of sliders and player counts. Prints the two
 * generation times per size. Run it after any merge that touches procedural/Landscape.java:
 *
 * <pre>JDK_JAVA_OPTIONS=-Xmx3g ./aisim.sh lab lab/neural/LandscapeCheck.java [SEEDS] [METERS,METERS...]</pre>
 */
public class LandscapeCheck {
    public static void main(String[] args) {
        Settings.setSettings(new Settings());
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 12;
        int[] sizes = args.length > 1 ? Arrays.stream(args[1].split(",")).mapToInt(Integer::parseInt).toArray()
                : new int[]{256, 512, 1024};
        int maps = 0;
        for (int meters : sizes) {
            long full_nanos = 0;
            long headless_nanos = 0;
            int timed = 0;
            for (int i = 0; i < seeds; i++) {
                int seed = 3 + 7919 * i + meters;
                TerrainType terrain = i % 2 == 0 ? TerrainType.NATIVE : TerrainType.VIKING;
                int players = 2 + i % 5;
                float hills = (i * 3 % 11) / 10f;
                float trees = (i * 7 % 11) / 10f;
                float supplies = (i * 5 % 11) / 10f;
                if (i == 0) { // the training setting: tropical, hills 0, trees 10, supplies 10, 1 vs 1
                    players = 2;
                    hills = 0f;
                    trees = 1f;
                    supplies = 1f;
                }
                long t0 = System.nanoTime();
                Landscape full = new Landscape(players, meters, terrain, .15f, hills, trees, supplies, seed * seed, 20,
                        0f, false);
                long t1 = System.nanoTime();
                Landscape headless = Landscape.generateHeadless(players, meters, terrain, hills, trees, supplies,
                        seed * seed, 20, 0f, false);
                long t2 = System.nanoTime();
                if (i >= 2) { // the first maps of a size compile the code
                    full_nanos += t1 - t0;
                    headless_nanos += t2 - t1;
                    timed++;
                }
                String where = meters + " m seed " + seed + " " + terrain + " " + players + " players";
                same(where, "height", full.getHeight(), headless.getHeight());
                same(where, "ground", full.getGround(), headless.getGround());
                same(where, "access", full.getAccessGrid(), headless.getAccessGrid());
                same(where, "dock", full.getDockGrid(), headless.getDockGrid());
                same(where, "water", full.getWaterGrid(), headless.getWaterGrid());
                same(where, "build", full.getBuildGrid(), headless.getBuildGrid());
                same(where, "island ids", full.getIslandIds(), headless.getIslandIds());
                same(where, "islands", islands(full.getIslandInfos()), islands(headless.getIslandInfos()));
                same(where, "trees", full.getTrees().toArray(), headless.getTrees().toArray());
                same(where, "palmtrees", full.getPalmtrees().toArray(), headless.getPalmtrees().toArray());
                same(where, "rock", full.getRock().toArray(), headless.getRock().toArray());
                same(where, "iron", full.getIron().toArray(), headless.getIron().toArray());
                same(where, "plants", full.getPlants(), headless.getPlants());
                same(where, "starts", full.getStartingLocations(), headless.getStartingLocations());
                if (full.getSeaLevelMeters() != headless.getSeaLevelMeters()) {
                    throw new IllegalStateException(where + ": sea level differs");
                }
                maps++;
            }
            if (timed > 0) {
                System.out.printf("%d m: full %.1f ms, headless %.1f ms per map (mean of %d)%n", meters,
                        full_nanos / 1e6 / timed, headless_nanos / 1e6 / timed, timed);
            }
        }
        System.out.println("identical: " + maps + " maps");
    }

    private static Object[] islands(List<IslandInfo> infos) {
        return infos.stream().map(i -> new Object[]{new int[]{i.id(), i.area(), i.trees(), i.rocks(), i.iron(),
                i.minX(), i.minY(), i.maxX(), i.maxY(), i.centerX(), i.centerY()}, i.contourPoints().toArray()})
                .toArray();
    }

    private static void same(String where, String what, Object[] a, Object[] b) {
        if (!Arrays.deepEquals(a, b)) {
            throw new IllegalStateException(where + ": " + what + " differs");
        }
    }
}
