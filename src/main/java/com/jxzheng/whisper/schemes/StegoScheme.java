package com.jxzheng.whisper.schemes;

import java.util.Optional;

import com.jxzheng.whisper.carrier.Carrier;
import com.jxzheng.whisper.carrier.CarrierProbe;
import com.jxzheng.whisper.ops.Progress;

/**
 * One way of hiding an already-packaged byte string in a cover.
 *
 * <p>Implementations embed the bytes they are given. They do not encrypt.
 * {@code embed} must read the payload back and refuse to return a carrier
 * that does not reproduce it.
 */
public interface StegoScheme {

    String id();

    SchemeInfo info();

    boolean accepts(CarrierProbe probe);

    Capacity estimate(Carrier cover, EmbedOptions options);

    /**
     * @param visitSeed 32-byte embedding seed for adaptive schemes; ignored by Zhang–Tang
     */
    Carrier embed(Carrier cover, byte[] payload, byte[] visitSeed, EmbedOptions options, Progress progress);

    byte[] extract(Carrier stego, byte[] visitSeed, EmbedOptions options);

    /**
     * Public salt spread on the cover, when this scheme has one.
     * Empty for Zhang–Tang.
     */
    Optional<byte[]> publicSalt(Carrier stego, RatePreset preset);
}
