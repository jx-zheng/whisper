# 01 — Crypto port (Whisper2 design → Java), integrity, failure modes

This document is normative for the CloudAgent. The source design is Whisper2 `internal/crypto` (`crypto.go`, `stream.go`, `compress.go`) plus `docs/research/02-crypto-design.md` and `08-stego-framing.md`. All of these get copied into the whisper repo under `docs/research/` in Phase 0.

## 1. What Whisper1 has today (main @ cd85cbe)
| Piece | Today | Problem |
|---|---|---|
| Payload cipher | `AesCipher`: AES-**128**-GCM, PBKDF2-HMAC-SHA256 at 65,536 iterations, layout `salt16‖iv12‖ct‖tag16` (44 B overhead) | PBKDF2 is not memory-hard, so stegseek-class cracking applies. Only one cipher. |
| Pixel-walk PRNG | `KeyedPrng`: PBKDF2 with a **fixed** salt `"whisper-stego-prng-v1"` → HMAC-SHA256 counter stream, extends `java.util.Random` | A fixed salt allows precomputation across all users. |
| Header auth | 2-byte truncated HMAC over a 2-byte length (`ZhangTangScheme.headerMac`) | 1/65,536 false accept. No content integrity when unencrypted. |
| Max payload | 65,535 B (u16 length) | — |
| Integrity of contents | Only the GCM tag, and only when `-e` is used. Nothing otherwise. | Fails H3. |

## 2. Target algorithms (H2: ≥2 AEADs)
| Role | Algorithm | Java implementation | Notes |
|---|---|---|---|
| Password KDF | **Argon2id** v1.3 | `org.bouncycastle:bcprov-jdk18on:1.86` → `Argon2BytesGenerator` + `Argon2Parameters.ARGON2_id` (low-level API; **do not** register BC as a JCA provider) | Profiles as in Whisper2. **default**: t=3, m=64 MiB, p=4 (RFC 9106 second recommended). **high**: t=1, m=2 GiB, p=4 (opt-in). **test**: t=2, m=32 MiB, p=1 (tests only). Output is 32 B. |
| Key expansion | **HKDF-SHA-256** (RFC 5869) | BC `HKDFBytesGenerator(new SHA256Digest())`, wrapped in a ~20-line `Hkdf` class. `javax.crypto.KDF` "HKDF-SHA256" is only available on **JDK 25+** (JEP 510), so it can be swapped in if the baseline moves to 25 LTS. | Domain labels are listed below. |
| AEAD #1 (default) | **ChaCha20-Poly1305** (RFC 8439) | JDK built-in since 11: `Cipher.getInstance("ChaCha20-Poly1305")` with `IvParameterSpec(nonce12)` | Re-init with a fresh nonce per chunk. SunJCE refuses key+nonce reuse. |
| AEAD #2 | **AES-256-GCM** | JDK `AES/GCM/NoPadding`, 128-bit tag | HotSpot AES-NI intrinsics. |
| Integrity-only mode (encryption off) | **HMAC-SHA-256** (full 32 B) | JDK `Mac "HmacSHA256"` | Only if the in-flight PR makes encryption optional. Authenticates but does not hide. |
| Compression | **DEFLATE** (raw, level 6) | JDK `java.util.zip.Deflater` / `Inflater` | Replaces Whisper2 zstd. Payloads are KB-scale, this adds no dependency, and pure-Java zstd (`aircompressor-v3` 3.14) targets JDK 25. Auto rule from Whisper2: skip compression if the magic bytes say the data is already compressed (PNG/JPEG/ZIP/GZIP/PDF/7z/xz/zstd/ftyp/RIFF/Ogg/FLAC) or if Shannon entropy is ≥7.2 bits/byte. Keep the compressed form only if it is smaller. Order is compress → AEAD, never the reverse. |
| Content hash | **SHA-256** | JDK `MessageDigest` | Lives inside the AEAD plaintext (§4). |
| Keyed visit order | Fisher–Yates driven by a **ChaCha20 keystream** | JDK `Cipher "ChaCha20"` + `ChaCha20ParameterSpec(zeroNonce, 0)` over zero blocks | Whisper2 `permute.Order`, with rejection sampling. Replaces `KeyedPrng` for the new schemes. `KeyedPrng` stays only inside Zhang–Tang. |
| CSPRNG | `SecureRandom` default (never `Random`) | JDK | Injectable for tests. |

**Dependency added:** only `bcprov-jdk18on` (Bouncy Castle Licence, MIT-style). Everything else comes from the JDK. No libsodium/JNI.

## 3. Key schedule (supports the in-flight split-key model)
```
salt          ← 16 random bytes (public; coded on a public spread subset of the cover, 02 §2.3)
stegoMaster   ← Argon2id(stegoKey, salt, profile)                          // 32 B
visitSeed     ← HKDF(ikm=stegoMaster, salt=∅, info="whisper v2 stego")     // keyed permutation
if single-key mode (one passphrase for both, per the in-flight PR's setting):
    encMaster ← stegoMaster                                                // one Argon2id run
else (split keys):
    encMaster ← Argon2id(encKey, SHA-256("whisper v2 enc salt" ‖ salt)[0:16], profile)
aeadKey       ← HKDF(ikm=encMaster, salt=∅,         info="whisper v2 aead")
payloadKey    ← HKDF(ikm=aeadKey,   salt=fileNonce, info="whisper v2 payload")   // age pattern
macKey        ← HKDF(ikm=encMaster, salt=fileNonce, info="whisper v2 mac")       // integrity-only mode
```
- **Key-file mode** (Whisper2 parity, optional UI): a 32-byte key (raw, 64 hex chars, or 44 base64 chars) replaces Argon2id. HKDF still runs.
- Wipe every secret `byte[]` / `char[]` after use with `Arrays.fill`. Passphrases travel as `char[]` from `PasswordField` (copy `getCharacters()`; never log the resulting String). This is best effort only, because JavaFX keeps a String internally. The README should say so.
- **Derivation cache:** `KeyCache` maps `(salt, profile, keyId)` → `stegoMaster` for the duration of one reveal attempt. Reveal tries up to 3 rate presets (02 §2.3), and each preset can read a different salt. Never run Argon2id twice for the same salt. Clear the cache when the attempt ends.

## 4. Byte layouts

### 4.1 Envelope (what the scheme embeds; keyed stream; no magic)
```
off size field
0   1    flags: bits0-3 version = 2 (Whisper1 v2); bit4 compressed(DEFLATE);
               bits5-6 suite: 00 ChaCha20-Poly1305, 01 AES-256-GCM, 10 integrity-only(HMAC); 11 reserved
               bit7 reserved = 0
1   1    kdf profile: 0 keyfile, 1 default, 2 high, 3 test
2   16   salt (same bytes as the public prefix; reveal rejects a mismatch, like Whisper2)
18  16   fileNonce (random per embed)
34  8    frameLen u64 BE (length of the *uncompressed* payload frame §4.2; bounds inflate)
42  …    AEAD suites: STREAM ciphertext. 64 KiB chunks; nonce = 7×0x00 ‖ u32BE seq ‖ lastFlag(0/1);
         header bytes 0..41 are AAD of chunk 0; each chunk = ct ‖ 16-byte tag.
         Integrity-only: frame bytes (optionally deflated) ‖ HMAC-SHA-256(macKey, bytes 0..end-32).
```
Overhead is 42 + 16·⌈n/64 KiB⌉ for the AEAD suites (58 B for payloads ≤64 KiB) and 42 + 32 = 74 B for integrity-only.
Hard cap: frameLen ≤ 64 MiB (`MaxFrame`). Reject a claimed length that exceeds it **before** allocating anything.

### 4.2 Payload frame (the AEAD plaintext; new; drives the UX in 03)
```
off  size  field
0    1     frameVersion = 1
1    1     kind: 0 TEXT (UTF-8), 1 FILE, 2 IMAGE
2    1     flags: bit0 hasName, bit1 imageReencoded (sender used "fit to capacity"), others 0
3    2     nameLen u16 BE (0..255), then name bytes (UTF-8, sanitized basename; 0 for TEXT)
…    1     mimeLen (0..100), then ASCII MIME (e.g. "image/png", "application/pdf"; "" allowed)
…    8     contentLen u64 BE
…    32    SHA-256(content)          ← H3 explicit checksum (over the *original* content bytes)
…    n     content
```
Typical frame overhead is 45 B for TEXT, and 45 B plus name and MIME (≈ 70–110 B) for FILE/IMAGE.
Total overhead end to end for a TEXT payload under ChaCha20 is 16 (public salt) + 4 (length prefix) + 58 (envelope) + 45 (frame) ≈ **123 B**. The UI meter must show this overhead, not just the content bytes.

**Why a hash inside an AEAD?** The AEAD already authenticates. Kevin still wants an **explicit, user-visible checksum**. The SHA-256 does three things:
1. It gives the sender and recipient a fingerprint they can compare out of band ("ends in …9f3a").
2. It catches bugs in our own compress / frame / scheme code paths that run after authentication.
3. It gives integrity-only mode the same UX.

The hash sits **inside** the authenticated plaintext, so it never hands a warden a hash they could use to confirm a guess about the secret. Never place a clear hash outside the AEAD.

### 4.3 Legacy read path (Whisper1 ≤0.9)
Zhang–Tang images made with `-e` carry `salt16‖iv12‖AES-128-GCM`. Without `-e` they carry raw bytes. Keep `AesCipher` as `LegacyAes128GcmPbkdf2` (**decrypt only**) behind `LegacyFormat`. Legacy payloads have no frame and no content hash, so the badge reads **"Unverified (legacy format)"** and the viewer is chosen by magic-sniffing (03 §4).

## 5. Pluggable modules (H4)
```java
package com.jxzheng.whisper.crypto;
public interface AeadSuite {            // registered in CipherRegistry (explicit ordered list)
    String id();                        // "chacha20-poly1305", "aes-256-gcm", "none"
    String displayName();               // "ChaCha20-Poly1305 (recommended)"
    int suiteBits();                    // wire value for flags bits5-6
    int overhead(int frameLen);
    void seal(byte[] key32, byte[] aad, byte[] in, int inOff, int inLen, byte[] out, int outOff); // preallocated output
    int open(byte[] key32, byte[] aad, byte[] in, int inOff, int inLen, byte[] out, int outOff) throws AuthException;
}
public interface PasswordKdf { byte[] derive(char[] pass, byte[] salt16, KdfProfile p); }  // Argon2idKdf (BC)
final class Hkdf { static byte[] expand(byte[] ikm, byte[] salt, String info, int len); }
final class Stream { /* STREAM chunking shared by all AEAD suites */ }
public final class Envelope { static byte[] seal(SealParams p, byte[] frame); static OpenResult open(byte[] blob, KeyMaterial k); }
```
`StegoOperations` uses only `Envelope` and `PayloadFrame`. The combo boxes are filled from `CipherRegistry.all()`, so adding XChaCha20 later takes one class and one wire value.

## 6. Verification outcomes and failure modes (H3)
The reveal pipeline runs in this order: read public salt → derive keys → extract keyed stream → length prefix → envelope open → inflate → frame parse → content re-hash.

| # | Situation | Where it fails | User-facing result (wording guide) | Notes |
|---|---|---|---|---|
| F1 | Correct keys, untouched image | — | ✅ **Verified** · ChaCha20-Poly1305 · SHA-256 `1a2b3c4d…9f3a` (copy button) | The full 64-hex digest is available via tooltip / copy. |
| F2 | Wrong **stego** key, or the image has no payload | Garbage length prefix, envelope salt ≠ public salt, or tag failure | ❌ **Nothing found with this key.** "Either the stego key is wrong, the image has no Whisper payload, or it was edited/recompressed after embedding." | These cases can't be told apart. That is by design (no magic). |
| F3 | Right stego key, wrong **encryption** key (split mode) | AEAD tag fails on chunk 0 | ❌ **Wrong encryption passphrase, or the payload was tampered with.** | The salt check passed, so the stego key did find *something*. Say so. |
| F4 | Image recompressed / resized / filtered / screenshotted | STC bits change → tag fails (or F2) | ❌ Same as F2. Hint: "Recompression, resizing, chat apps and screenshots destroy hidden data." | Not robust by design (out of scope). |
| F5 | Truncated stream / missing final chunk / extra bytes | `Stream.open` | ❌ **Payload damaged (incomplete).** | Fail closed. Never show partial plaintext. |
| F6 | AEAD OK, but inflate fails or length ≠ frameLen | After auth | ❌ **Payload damaged (decompression failed).** Log it as an internal error. | Only reachable through a bug or a compromised key. |
| F7 | AEAD OK, inflate OK, **SHA-256 mismatch** | Frame check | ❌ **Integrity check failed: content hash does not match.** Never show the content. | Should be impossible. Treat it as a bug; fuzzing tests must assert it never happens. |
| F8 | Integrity-only mode, HMAC mismatch | `IntegrityOnlySuite.open` | ❌ **Tampered or wrong key (not encrypted).** | — |
| F9 | Integrity-only mode, HMAC OK | — | ⚠️ **Verified, not encrypted** · SHA-256 … | Amber badge, because anyone with the stego key can read it. |
| F10 | Legacy 0.9 image | Legacy path | ⚠️ **Unverified (legacy format)**. If it was encrypted: "AES-GCM tag OK; no content hash". | — |
| F11 | Frame says IMAGE, but decoding fails or exceeds limits | Viewer | ✅ Verified, shown as a **File** card with "Couldn't preview this image" | Integrity and previewability are separate questions. |
| F12 | Unsafe frame name (`../`, absolute path, control chars, reserved Windows names) | Frame parse | Sanitize to a basename. If nothing is left, use `whisper-payload.<ext from mime>`. | Never trust the name for the save path. |
| F13 | `frameLen` / `contentLen` / `nameLen` over a cap or inconsistent | Parse, before allocation | ❌ Payload damaged. | Fuzz this. |
| F14 | Rate preset mismatch (the sender used Paranoid) | The public salt read differs | Handled automatically: reveal tries Standard → Low → Paranoid (02 §2.3) using the derivation cache. | Worst case with a wrong key is ≈ 3 Argon2id runs. |

Embed-side verification: every scheme **self-extracts** before writing (the Whisper2 rule). Embed also re-opens the envelope and compares the SHA-256 before the output is atomically moved into place (`Files.move(tmp, out, ATOMIC_MOVE, REPLACE_EXISTING)`). The success banner shows the same fingerprint the recipient will see.

## 7. Performance and memory rules (crypto side)
- **Read the payload file once.** Check `Files.size` against capacity **before** reading. Then read once through a `DigestInputStream` (SHA-256) directly into the frame buffer at its final offset, with no intermediate copies.
- **Preallocate exact outputs.** Size the deflate buffer from `frameLen` using the `Deflater` bound, then allocate `envelope = new byte[42 + suite.overhead(len)]`. STREAM writes each chunk with `Cipher.doFinal(in, off, len, out, outOff)`. No `ByteArrayOutputStream` growth, no per-chunk garbage.
- **Run Argon2id once per (salt, key).** A single-key embed is 1 run. Split keys are 2 runs; show "Deriving keys… 2/2".
- **Keep all KDF and crypto work off the FX thread.** Use `Background` / `Task` and show progress stages: Reading → Deriving keys → Encrypting → Embedding → Verifying → Writing.
- **Benchmarks** (JUnit `@Tag("bench")`, excluded from the default `mvn test`, run with `mvn -Pbench test`):
  - Argon2id, default profile: ≤ **1.5 s** (mid laptop, JDK 21). Record the CI number.
  - Seal + open 1 MiB, each AEAD: ≤ **50 ms**.
  - Envelope + frame for 64 KiB: allocation ≤ 3× payload, measured with `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`.

## 8. Tests (must pass)
- RFC 9106 §5.3 Argon2id vector, RFC 8439 §2.8.2 ChaCha20-Poly1305, one NIST GCM 256-bit case, RFC 5869 HKDF A.1.
- STREAM: empty payload, exactly 64 KiB, 64 KiB + 1, 3 chunks. Reordering, dropping, or duplicating a chunk must fail. Flipping the final flag must fail.
- **Bit-flip sweep:** flip every bit of the envelope for a 200 B payload. Every flip must fail (F2/F3/F5–F8) and never return plaintext.
- Frame fuzz (a seeded loop of 10k mutations): the parser throws only `PayloadFormatException` and never allocates beyond the caps.
- Split- and single-key round trips. A wrong encryption key gives the F3 wording. A wrong stego key gives the F2 wording.
- Legacy: a fixture PNG made by 0.9.0 (generated from current `main` before any changes and committed) still extracts, with the legacy badge.
- Secrets are wiped: after seal/open, the key arrays exposed to test hooks are all zero.
