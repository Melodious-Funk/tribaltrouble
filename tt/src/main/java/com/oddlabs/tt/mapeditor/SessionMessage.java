package com.oddlabs.tt.mapeditor;

import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * What one edit of a shared session carries, as the server passes it on: a kind, the campaign level it belongs to
 * (always 0 in a map session), and what the kind says.
 *
 * <ul>
 * <li>{@link #TERRAIN}: heights and resources, as an {@link EditOp}.</li>
 * <li>{@link #SCENARIO}: a level's units, buildings, areas, triggers and texts, as a {@link ScenarioSync.Op}.</li>
 * <li>{@link #SWITCH_LEVEL}: everyone goes on to another level of the campaign, laid out as the player sending it has
 * it, as {@link CampaignFile#levelToBytes} writes it.</li>
 * <li>{@link #ADD_LEVEL}: a new level for everyone, at the end of the campaign or in place of the level of that number
 * if another player's new level got there first; everyone goes on to it.</li>
 * <li>{@link #SPAWNS}: players' spawns moved or taken away, as {@link SpawnSync} writes them.</li>
 * </ul>
 *
 * <p>Edits of a level other than the one shown are left out: they were made before their player heard everyone had
 * moved on.
 */
final class SessionMessage {
    static final byte TERRAIN = 1;
    static final byte SCENARIO = 2;
    static final byte SWITCH_LEVEL = 3;
    static final byte ADD_LEVEL = 4;
    static final byte SPAWNS = 5;

    private static final int HEADER_SIZE = 5;

    private SessionMessage() {
    }

    static byte @NonNull [] encode(byte kind, int level, byte @NonNull [] payload) {
        return ByteBuffer.allocate(HEADER_SIZE + payload.length).put(kind).putInt(level).put(payload).array();
    }

    static byte kind(byte @NonNull [] message) throws IOException {
        check(message);
        return message[0];
    }

    static int level(byte @NonNull [] message) throws IOException {
        check(message);
        return ByteBuffer.wrap(message, 1, 4).getInt();
    }

    static byte @NonNull [] payload(byte @NonNull [] message) throws IOException {
        check(message);
        return Arrays.copyOfRange(message, HEADER_SIZE, message.length);
    }

    /** Whether a message moves everyone to another level, which its sender does too once the server orders it. */
    static boolean movesEveryone(byte @NonNull [] message) {
        return message.length >= HEADER_SIZE && (message[0] == SWITCH_LEVEL || message[0] == ADD_LEVEL);
    }

    private static void check(byte @NonNull [] message) throws IOException {
        if (message.length < HEADER_SIZE)
            throw new IOException("Edit too short");
    }
}
