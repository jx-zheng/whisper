package com.jxzheng.whisper.carrier;

/**
 * Enough of a file to decide which scheme can carry it.
 *
 * @param name file name, used only when the magic bytes are inconclusive
 * @param prefix leading bytes of the file, possibly empty
 */
public record CarrierProbe(String name, byte[] prefix) {
}
