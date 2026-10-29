# Architecture

Whisper hides a payload in an image and authenticates it. The cover image is never encrypted. Only the payload is.

```
gui  →  ops  →  schemes / crypto / payload / carrier
                 analysis stays independent
```

`gui` talks only to `ops`. Schemes, codecs, and ciphers do not import JavaFX.

## Packages

| Package | Role |
|---|---|
| `crypto` | Argon2id, HKDF-SHA-256, ChaCha20-Poly1305, AES-256-GCM, HMAC integrity mode, age-style STREAM, v2 envelope |
| `payload` | Text / file / image frame, SHA-256 inside the frame, name sanitizing, MIME sniff, fit-to-capacity |
| `schemes` | `StegoScheme` plus Zhang–Tang, `hill-stc`, and `uerd-stc` |
| `schemes.stc` | Clean-room binary syndrome-trellis code |
| `schemes.permute` | ChaCha20 Fisher–Yates visit order |
| `schemes.framing` | Public salt spread, then keyed length and payload |
| `schemes.costs` | HILL and UERD |
| `carrier` | PNG, BMP, and baseline JPEG coefficient codec |
| `ops` | Hide, reveal, legacy embed/extract, atomic write |
| `drivers` | CLI |
| `gui` | JavaFX Embed, Extract, and Analyze |
| `analysis` | Chi-square and RS |

## Add a scheme

1. Implement `StegoScheme` (`id`, `info`, `accepts`, `estimate`, `embed`, `extract`, `publicSalt`).
2. `embed` must read the payload back and refuse to return a carrier that does not match. Write the file with `AtomicFiles` from `ops`, not from the scheme.
3. Register the instance in `SchemeRegistry`. The Embed combo lists registry ids. No new UI code is required for the combo itself.
4. If the scheme needs a new container, teach `PayloadOps` and `ImageFormats` which extension is safe. JPEG output is allowed only for a coefficient-domain scheme.

## Add a cipher

1. Implement `AeadSuite` with a distinct `suiteBits` value (0 ChaCha, 1 AES-GCM, 2 integrity-only, 3 reserved).
2. Register the id in `CipherRegistry`.
3. Map the id in `CliDriver.suite` and the Embed cipher combo. `Envelope` already frames STREAM for suites 0 and 1 and HMAC for suite 2.

## Add a payload kind

1. Add a wire value on `PayloadKind` and teach `PayloadFrame` to read and write it.
2. Add an editor under `gui` and a viewer branch in Extract. Sniff bytes with `MimeSniffer` before trusting the frame's MIME string.
3. Keep the content hash inside the frame. Do not put a hash in the clear.

## Key schedule

```
stegoMaster = Argon2id(stego passphrase, public salt, profile)
visitSeed   = HKDF(stegoMaster, "whisper v2 stego")
encMaster   = stegoMaster                         // one passphrase
            or Argon2id(enc passphrase, SHA-256("whisper v2 enc salt" ‖ salt)[0:16])
payloadKey  = HKDF(HKDF(encMaster, "whisper v2 aead"), file nonce, "whisper v2 payload")
```

Legacy Zhang–Tang walks still use `KeyedPrng` (PBKDF2). That path does not use the visit seed. AES-128-GCM/PBKDF2 is decrypt-only via `LegacyAes128GcmPbkdf2`.
