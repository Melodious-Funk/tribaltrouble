package com.oddlabs.tt.camera;

import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.render.Picker;
import org.jspecify.annotations.NonNull;

/** What the controllable cameras need from the screen that owns them. */
public interface CameraHost {
    @NonNull World getWorld();

    @NonNull GUIRoot getGUIRoot();

    @NonNull Picker getPicker();
}
