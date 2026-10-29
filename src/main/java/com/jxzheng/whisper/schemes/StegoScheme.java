package com.jxzheng.whisper.schemes;

import com.jxzheng.whisper.carrier.Carrier;

/**
 * One way of hiding an already-packaged byte string in a cover.
 *
 * <p>Implementations embed the bytes they are given. They do not encrypt.
 * {@code embed} must read the payload back and refuse to return a carrier
 * that does not reproduce it.
 */
public interface StegoScheme {

    String id();

    /** {@code visitSeed} is the 32-byte embedding seed for adaptive schemes. */
    Carrier embed(Carrier cover, byte[] payload, byte[] visitSeed, EmbedOptions options);

    byte[] extract(Carrier stego, byte[] visitSeed, EmbedOptions options);
}
