package com.oddlabs.tt.mapeditor;

import com.oddlabs.matchmaking.Game;
import com.oddlabs.net.NetworkSelector;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.audio.AudioManager;
import com.oddlabs.tt.camera.CameraState;
import com.oddlabs.tt.form.LoadCallback;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.landscape.LandscapeResources;
import com.oddlabs.tt.landscape.NotificationListener;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.landscape.WorldParameters;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.PlayerInfo;
import com.oddlabs.tt.render.DefaultRenderer;
import com.oddlabs.tt.render.LandscapeRenderer;
import com.oddlabs.tt.render.MatrixStack;
import com.oddlabs.tt.render.Picker;
import com.oddlabs.tt.render.RenderQueues;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.render.UIRenderer;
import com.oddlabs.tt.resource.IslandGenerator;
import com.oddlabs.tt.resource.WorldInfo;
import com.oddlabs.tt.viewer.Cheat;
import com.oddlabs.tt.viewer.Selection;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Builds the editor's island behind the progress screen, the same way the main menu builds its backdrop island:
 * a world with a single idle player and no races loaded, since the editor only shapes terrain.
 */
final class MapEditorLoader implements LoadCallback {
    private final @NonNull NetworkSelector network;
    private final @NonNull MapSettings settings;
    private final @Nullable String map_name;
    private final float @Nullable [] @Nullable [] heights;
    private final MapFile.@Nullable Resources resources;

    MapEditorLoader(@NonNull NetworkSelector network, @NonNull MapSettings settings, @Nullable String map_name,
            float @Nullable [] @Nullable [] heights, MapFile.@Nullable Resources resources) {
        this.network = network;
        this.settings = settings;
        this.map_name = map_name;
        this.heights = heights;
        this.resources = resources;
    }

    @Override
    public @NonNull UIRenderer load(@NonNull GUIRoot gui_root) {
        AnimationManager.freezeTime();
        IslandGenerator generator = settings.createGenerator();
        PlayerInfo[] players = new PlayerInfo[]{new PlayerInfo(0, 0, "")};
        WorldParameters world_params = new WorldParameters(Game.GAMESPEED_NORMAL, "", 2,
                Player.DEFAULT_MAX_UNIT_COUNT);
        WorldInfo world_info = generator.generate(players.length, world_params.getInitialUnitCount(), 0f);
        float[][] terrain = world_info.heightmap();
        boolean edited = heights != null && heights.length == terrain.length;
        if (edited) {
            // The height map keeps this array, so saved heights must be in place before the world is built.
            for (int y = 0; y < terrain.length; y++)
                System.arraycopy(heights[y], 0, terrain[y], 0, terrain[y].length);
            TerrainEditor.pinEdges(terrain);
        }
        if (resources != null) {
            // Saved resources take the generated ones' place before the world plants them.
            List<List<int[]>> lists = List.of(world_info.trees(), world_info.palm_trees(), world_info.rocks(),
                    world_info.iron());
            for (Resource kind : Resource.values()) {
                lists.get(kind.ordinal()).clear();
                lists.get(kind.ordinal()).addAll(resources.of(kind));
            }
        }

        RenderQueues render_queues = new RenderQueues();
        LandscapeResources landscape_resources = World.loadCommon(render_queues);
        World world = World.newWorld(AudioManager.getManager(), landscape_resources, null, new NotificationListener() {
        }, world_params, world_info, generator.getTerrainType(), players, generator.getFogInfo());
        AnimationManager manager = new AnimationManager();
        LandscapeRenderer landscape_renderer = new LandscapeRenderer(world, world_info, manager);
        Player local_player = world.getPlayers()[0];
        Selection selection = new Selection(local_player);
        Picker picker = new Picker(manager, local_player, gui_root, render_queues, landscape_renderer, selection);
        // The editor's view toggles (trees, wireframe) are the renderer's cheat switches.
        Cheat view = new Cheat();
        DefaultRenderer renderer = new DefaultRenderer(view, local_player, render_queues, world_info, landscape_renderer,
                picker, selection, generator, new MatrixStack(), new MatrixStack(), null);

        GroundTextures ground = GroundTextures.create(world_info, settings,
                Renderer.getRenderer().getRenderContext(), resources == null);
        // The generator baked the ground texture for its own heights and trees, so saved ones need it redone.
        if ((edited || resources != null) && ground != null)
            ground.rebuildAll();
        ResourceSnapper snapper = new ResourceSnapper(world);
        AccessMap access_map = new AccessMap(terrain, settings);
        AccessOverlay access = new AccessOverlay(access_map);
        TerrainEditor editor = new TerrainEditor(world.getHeightMap(), terrain, (x0, y0, x1, y1) -> {
            snapper.snap(x0, y0, x1, y1);
            if (ground != null)
                ground.heightsChanged(x0, y0, x1, y1);
            access_map.heightsChanged();
        });
        ResourceLayer layer = new ResourceLayer(world, access_map, settings, (changed, x0, y0, x1, y1) -> {
            if (ground != null)
                ground.resourcesChanged(changed, x0, y0, x1, y1);
        });
        MapEditorDelegate delegate = new MapEditorDelegate(network, gui_root, world, manager, picker, view,
                new CameraState(generator.getFogInfo()), editor, ground, access_map, access, layer,
                new PlantLayer(world, access_map), renderer.getWater(), settings, map_name, edited, resources != null);
        Renderer.getRenderer().setMusicPath("/music/menu.ogg", 0f);
        gui_root.pushDelegate(delegate);
        return renderer;
    }
}
