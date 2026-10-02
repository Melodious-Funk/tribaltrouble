package com.oddlabs.tt.mapeditor;

import org.jspecify.annotations.NonNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A trigger's condition or one of its actions: the kind, as a {@link ConditionKind} or {@link ActionKind} ordinal,
 * and the settings that kind uses. Areas, objects and triggers are named by id, so they can be renamed and moved.
 */
final class Step {
    int kind;
    private int player;
    private int other_player;
    private int area = -1;
    private int object = -1;
    private int building = -1;
    private int amount;
    private int type;
    private int trigger = -1;
    private @NonNull String header = "";
    private @NonNull String text = "";

    private Step(int kind) {
        this.kind = kind;
    }

    static @NonNull Step condition(@NonNull ConditionKind kind) {
        Step step = new Step(kind.ordinal());
        step.reset(kind.getParams());
        return step;
    }

    static @NonNull Step action(@NonNull ActionKind kind) {
        Step step = new Step(kind.ordinal());
        step.reset(kind.getParams());
        return step;
    }

    /** Switches to a condition kind, keeping the settings the two kinds share. */
    void setCondition(@NonNull ConditionKind new_kind) {
        if (new_kind.ordinal() != kind)
            switchKind(new_kind.ordinal(), ConditionKind.of(kind).getParams(), new_kind.getParams());
    }

    /** Switches to an action kind, keeping the settings the two kinds share. */
    void setAction(@NonNull ActionKind new_kind) {
        if (new_kind.ordinal() != kind)
            switchKind(new_kind.ordinal(), ActionKind.of(kind).getParams(), new_kind.getParams());
    }

    private void switchKind(int new_kind, @NonNull Param @NonNull [] old_params,
            @NonNull Param @NonNull [] new_params) {
        kind = new_kind;
        for (Param param : new_params) {
            boolean kept = false;
            for (Param old : old_params)
                kept |= old == param;
            if (!kept && !param.isText())
                set(param, param.getDefault());
        }
    }

    private void reset(@NonNull Param @NonNull [] params) {
        for (Param param : params) {
            if (!param.isText())
                set(param, param.getDefault());
        }
    }

    int get(@NonNull Param param) {
        return switch (param) {
            case PLAYER -> player;
            case TARGET_PLAYER, NEW_OWNER -> other_player;
            case AREA -> area;
            case OBJECT -> object;
            case BUILDING -> building;
            case COUNT, SECONDS, RADIUS -> amount;
            case UNIT_TYPE, DEPLOY_TYPE, SUPPLY_TYPE, MAGIC, ROLE, FACE, TEAM -> type;
            case TRIGGER -> trigger;
            case HEADER, TEXT -> throw new IllegalArgumentException(param + " is text");
        };
    }

    void set(@NonNull Param param, int value) {
        switch (param) {
            case PLAYER -> player = value;
            case TARGET_PLAYER, NEW_OWNER -> other_player = value;
            case AREA -> area = value;
            case OBJECT -> object = value;
            case BUILDING -> building = value;
            case COUNT, SECONDS, RADIUS -> amount = value;
            case UNIT_TYPE, DEPLOY_TYPE, SUPPLY_TYPE, MAGIC, ROLE, FACE, TEAM -> type = value;
            case TRIGGER -> trigger = value;
            case HEADER, TEXT -> throw new IllegalArgumentException(param + " is text");
        }
    }

    @NonNull String getText(@NonNull Param param) {
        return param == Param.HEADER ? header : text;
    }

    void setText(@NonNull Param param, @NonNull String value) {
        if (param == Param.HEADER)
            header = value;
        else
            text = value;
    }

    @NonNull Step copy() {
        Step step = new Step(kind);
        step.player = player;
        step.other_player = other_player;
        step.area = area;
        step.object = object;
        step.building = building;
        step.amount = amount;
        step.type = type;
        step.trigger = trigger;
        step.header = header;
        step.text = text;
        return step;
    }

    /** Writes the step as {@link Scenario}'s current version keeps it. */
    void write(@NonNull DataOutputStream out) throws IOException {
        out.writeShort(kind);
        out.writeByte(player);
        out.writeByte(other_player);
        out.writeInt(area);
        out.writeInt(object);
        out.writeInt(building);
        out.writeInt(amount);
        out.writeShort(type);
        out.writeInt(trigger);
        out.writeUTF(header);
        out.writeUTF(text);
    }

    /**
     * Reads a step as a level of the given {@link Scenario} version keeps it.
     *
     * @param version the level's version; steps of version 1 have no building
     */
    static @NonNull Step read(@NonNull DataInputStream in, int version) throws IOException {
        Step step = new Step(in.readShort());
        step.player = Math.clamp(in.readByte(), 0, Scenario.NUM_PLAYERS - 1);
        step.other_player = Math.clamp(in.readByte(), 0, Scenario.NUM_PLAYERS - 1);
        step.area = in.readInt();
        step.object = in.readInt();
        step.building = version >= 2 ? in.readInt() : -1;
        step.amount = in.readInt();
        step.type = in.readShort();
        step.trigger = in.readInt();
        step.header = in.readUTF();
        step.text = in.readUTF();
        return step;
    }
}
