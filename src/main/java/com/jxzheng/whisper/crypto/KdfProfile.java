package com.jxzheng.whisper.crypto;

/**
 * Argon2id parameter sets stored in the envelope.
 * Memory is kibibytes, matching {@code Argon2Parameters.Builder.withMemoryAsKB}.
 */
public enum KdfProfile {
    DEFAULT(1, 3, 64 * 1024, 4),
    HIGH(2, 1, 2 * 1024 * 1024, 4),
    TEST(3, 2, 32 * 1024, 1);

    private final int wire;
    private final int iterations;
    private final int memoryKiB;
    private final int lanes;

    KdfProfile(int wire, int iterations, int memoryKiB, int lanes) {
        this.wire = wire;
        this.iterations = iterations;
        this.memoryKiB = memoryKiB;
        this.lanes = lanes;
    }

    public int wire() {
        return wire;
    }

    public int iterations() {
        return iterations;
    }

    public int memoryKiB() {
        return memoryKiB;
    }

    public int lanes() {
        return lanes;
    }

    public static KdfProfile fromWire(int wire) {
        for (KdfProfile profile : values()) {
            if (profile.wire == wire) {
                return profile;
            }
        }
        return null;
    }

    public static KdfProfile fromId(String id) {
        if (id == null || id.isBlank()) {
            return DEFAULT;
        }
        return switch (id.toLowerCase(java.util.Locale.ROOT).trim()) {
            case "default" -> DEFAULT;
            case "high" -> HIGH;
            case "test" -> TEST;
            default -> throw new IllegalArgumentException("Unknown KDF profile: " + id);
        };
    }
}
