package com.oddlabs.tt.player.neural;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * The policy: a small fully connected network read from a file the trainer exports, evaluated in plain Java. Every
 * machine of a lockstep game must choose the same action from the same observation, so the arithmetic is floats in a
 * fixed order with {@link StrictMath} for tanh and exp: no native library, no vector code, the same bits everywhere.
 *
 * <p>The file, big-endian: the magic and version, the observation size, the number of hidden layers, then each hidden
 * layer, the policy layer (to {@link Heads#LOGITS}) and the value layer (to 1), each as its input and output sizes,
 * its weights row by row and its biases. Hidden layers use tanh.
 */
final class Net {
    private static final int MAGIC = 0x54544E4E; // "TTNN"
    private static final int VERSION = 1;

    private final float @NonNull [] @NonNull [] weights;
    private final float @NonNull [] @NonNull [] biases;
    private final int @NonNull [] inputs;
    private final int hidden_layers;
    /** The activations of the last hidden layer after {@link #forward}. */
    private float @NonNull [] features = new float[0];

    private Net(float @NonNull [] @NonNull [] weights, float @NonNull [] @NonNull [] biases, int @NonNull [] inputs,
            int hidden_layers) {
        this.weights = weights;
        this.biases = biases;
        this.inputs = inputs;
        this.hidden_layers = hidden_layers;
    }

    /** The net of {@code file}; null without a file: the policy then chooses uniformly at random. */
    static @Nullable Net load(@Nullable Path file, int observation_size) {
        if (file == null) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IllegalArgumentException(file + " is not a neural net file of version " + VERSION);
            }
            int observation = in.readInt();
            if (observation != observation_size) {
                throw new IllegalArgumentException(
                        file + " takes " + observation + " observations, this build makes " + observation_size + ": train a net for this build");
            }
            int hidden = in.readInt();
            int layers = hidden + 2;
            float[][] weights = new float[layers][];
            float[][] biases = new float[layers][];
            int[] inputs = new int[layers];
            for (int layer = 0; layer < layers; layer++) {
                inputs[layer] = in.readInt();
                int outputs = in.readInt();
                weights[layer] = new float[inputs[layer] * outputs];
                biases[layer] = new float[outputs];
                for (int i = 0; i < weights[layer].length; i++) {
                    weights[layer][i] = in.readFloat();
                }
                for (int i = 0; i < outputs; i++) {
                    biases[layer][i] = in.readFloat();
                }
            }
            if (biases[hidden].length != Heads.LOGITS || biases[hidden + 1].length != 1) {
                throw new IllegalArgumentException(
                        file + " has " + biases[hidden].length + " logits, this build " + Heads.LOGITS + ": train a net for this build");
            }
            return new Net(weights, biases, inputs, hidden);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the net " + file, e);
        }
    }

    /** One layer: out = W in + b. */
    private float @NonNull [] layer(int layer, float @NonNull [] in) {
        float[] w = weights[layer];
        float[] b = biases[layer];
        int n = inputs[layer];
        float[] out = new float[b.length];
        for (int o = 0; o < out.length; o++) {
            float sum = b[o];
            int row = o * n;
            for (int i = 0; i < n; i++) {
                sum += w[row + i] * in[i];
            }
            out[o] = sum;
        }
        return out;
    }

    /** The logits for {@code observation}; {@link #value()} then gives the value of the same observation. */
    float @NonNull [] forward(float @NonNull [] observation) {
        float[] x = observation;
        for (int layer = 0; layer < hidden_layers; layer++) {
            x = layer(layer, x);
            for (int i = 0; i < x.length; i++) {
                x[i] = (float) StrictMath.tanh(x[i]);
            }
        }
        features = x;
        return layer(hidden_layers, x);
    }

    float value() {
        return layer(hidden_layers + 1, features)[0];
    }

    /**
     * Chooses one action per head from {@code logits} among the choices {@code allowed} marks, by sampling with
     * {@code random} or, without it, the most likely one. Returns the log probability of the whole choice.
     */
    static float choose(float @NonNull [] logits, boolean @NonNull [] allowed, @Nullable Random random,
            int @NonNull [] action) {
        double log_probability = 0;
        for (int head = 0; head < Heads.COUNT; head++) {
            int offset = Heads.offset(head);
            int size = Heads.size(head);
            double max = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < size; i++) {
                if (allowed[offset + i]) {
                    max = StrictMath.max(max, logits[offset + i]);
                }
            }
            double[] p = new double[size];
            double sum = 0;
            for (int i = 0; i < size; i++) {
                p[i] = allowed[offset + i] ? StrictMath.exp(logits[offset + i] - max) : 0;
                sum += p[i];
            }
            int chosen = -1;
            if (random == null) {
                for (int i = 0; i < size; i++) {
                    if (allowed[offset + i] && (chosen < 0 || p[i] > p[chosen])) {
                        chosen = i;
                    }
                }
            } else {
                double at = random.nextDouble() * sum;
                for (int i = 0; i < size; i++) {
                    if (allowed[offset + i]) {
                        chosen = i; // the last allowed one takes what rounding leaves
                        at -= p[i];
                        if (at < 0) {
                            break;
                        }
                    }
                }
            }
            action[head] = chosen;
            log_probability += StrictMath.log(p[chosen] / sum);
        }
        return (float) log_probability;
    }
}
