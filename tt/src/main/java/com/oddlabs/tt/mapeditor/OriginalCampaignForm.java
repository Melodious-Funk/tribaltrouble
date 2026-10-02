package com.oddlabs.tt.mapeditor;

import com.oddlabs.tt.animation.TimerAnimation;
import com.oddlabs.tt.event.LocalEventQueue;
import com.oddlabs.tt.form.MessageForm;
import com.oddlabs.tt.gui.CancelButton;
import com.oddlabs.tt.gui.FocusDirection;
import com.oddlabs.tt.gui.Form;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.HorizButton;
import com.oddlabs.tt.gui.Label;
import com.oddlabs.tt.gui.LabelBox;
import com.oddlabs.tt.gui.Skin;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.oddlabs.tt.gui.Placement.BOTTOM_LEFT;
import static com.oddlabs.tt.gui.Placement.BOTTOM_RIGHT;
import static com.oddlabs.tt.gui.Placement.LEFT_MID;

/**
 * Picks one of the game's own campaigns to open in the campaign editor, and builds it away from the screen, as
 * generating its islands takes a few seconds.
 */
final class OriginalCampaignForm extends Form {
    private static final int TEXT_WIDTH = 420;
    private static final int BUTTON_WIDTH = 110;
    /** Seconds between looks at how the building is going. */
    private static final float POLL_SECONDS = .1f;

    private final @NonNull GUIRoot gui_root;
    private final @NonNull Consumer<@NonNull CampaignFile> open;
    private final @NonNull Label label_status;
    private final @NonNull HorizButton button_vikings;
    private final @NonNull HorizButton button_natives;
    private @Nullable TimerAnimation poll;

    /** @param open opens the campaign once it is built */
    OriginalCampaignForm(@NonNull GUIRoot gui_root, @NonNull Consumer<@NonNull CampaignFile> open) {
        super(CampaignEditor.i18n("original_caption"));
        this.gui_root = gui_root;
        this.open = open;
        LabelBox label_help = new LabelBox(CampaignEditor.i18n("original_help"), Skin.getSkin().getEditFont(),
                TEXT_WIDTH);
        label_status = new Label("", Skin.getSkin().getEditFont(), TEXT_WIDTH);
        button_vikings = new HorizButton(OriginalCampaign.Tribe.VIKINGS.getName(), BUTTON_WIDTH);
        button_vikings.addMouseClickListener((_, _, _, _) -> build(OriginalCampaign.Tribe.VIKINGS));
        button_natives = new HorizButton(OriginalCampaign.Tribe.NATIVES.getName(), BUTTON_WIDTH);
        button_natives.addMouseClickListener((_, _, _, _) -> build(OriginalCampaign.Tribe.NATIVES));
        HorizButton button_cancel = new CancelButton(BUTTON_WIDTH);
        button_cancel.addMouseClickListener((_, _, _, _) -> cancel());
        addChild(label_help);
        addChild(label_status);
        addChild(button_vikings);
        addChild(button_natives);
        addChild(button_cancel);
        label_help.place();
        label_status.place(label_help, BOTTOM_LEFT, Skin.getSkin().getFormData().sectionSpacing());
        button_cancel.place(label_status, BOTTOM_RIGHT, Skin.getSkin().getFormData().sectionSpacing());
        button_natives.place(button_cancel, LEFT_MID);
        button_vikings.place(button_natives, LEFT_MID);
        compileCanvas();
        centerPos();
    }

    /** Builds a campaign on a thread of its own, telling how far it has come, and opens it once built. */
    private void build(OriginalCampaign.@NonNull Tribe tribe) {
        if (poll != null)
            return;
        button_vikings.setDisabled(true);
        button_natives.setDisabled(true);
        AtomicInteger islands = new AtomicInteger();
        AtomicReference<CampaignFile> built = new AtomicReference<>();
        AtomicReference<RuntimeException> failed = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                built.set(OriginalCampaign.build(tribe, islands::incrementAndGet));
            } catch (RuntimeException e) {
                failed.set(e);
            }
        }, "Original campaign");
        thread.setDaemon(true);
        thread.start();
        label_status.set(CampaignEditor.i18n("original_building", 0));
        poll = new TimerAnimation(LocalEventQueue.getQueue().getManager(), timer -> {
            CampaignFile file = built.get();
            RuntimeException error = failed.get();
            if (file == null && error == null) {
                label_status.set(CampaignEditor.i18n("original_building", islands.get()));
                return;
            }
            timer.stop();
            remove();
            if (file != null) {
                open.accept(file);
            } else {
                gui_root.addModalForm(new MessageForm(CampaignEditor.i18n("original_failed",
                        String.valueOf(error.getMessage()))));
            }
        }, POLL_SECONDS);
        poll.start();
    }

    @Override
    public void cancel() {
        // A campaign being built is opened once it is: there is no stopping the generator halfway.
        if (poll == null)
            super.cancel();
    }

    @Override
    public void setFocus(@NonNull FocusDirection direction) {
        if (direction == FocusDirection.BACKWARD) {
            super.setFocus(direction);
        } else {
            button_vikings.setFocus(direction);
        }
    }
}
