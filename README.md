# whisper

Whisper hides a message, a file, or an image inside a picture, then checks that the hidden bytes are exactly the ones that were embedded.

The cover stays a normal PNG, BMP, or JPEG. Whisper encrypts only the payload. It does not scramble the picture, and it does not survive recompression, resizing, screenshots, or a chat app that re-encodes uploads. A careful analyst can still try to detect that something was embedded. Use a strong passphrase.

There is a command-line tool and a JavaFX desktop app. Both call the same code.

## Quick start

Java 21 and Maven 3.8 or newer.

```bash
mvn -B package
mvn javafx:run
```

The shaded jar is the CLI. JavaFX stays on the module path and is not inside the jar.

```bash
java -jar target/whisper-1.0.0.jar embed \
  -i cover.png -o stego.png -m "hello" -k "a real passphrase"

java -jar target/whisper-1.0.0.jar extract \
  -i stego.png -k "a real passphrase"
```

Those two commands are the defaults: `auto` picks HILL for a PNG and UERD for a JPEG, and the cipher is ChaCha20-Poly1305. Omit `-E` and the stego passphrase is also the encryption key. A blank passphrase still embeds, and the result is labelled not confidential.

Extract prints `Verified · SHA-256 …` on standard error when the payload checks out.

## Choosing a scheme

| Scheme | Use it for | What changes | Rate |
|---|---|---|---|
| `hill-stc` | PNG or BMP photos | Green (or grey) samples, ±1, HILL cost + STC | 0.10 bpp standard |
| `uerd-stc` | Baseline JPEG photos | Nonzero luminance AC coefficients, ±1 toward zero | 0.10 bpnzAC standard |
| `zhang-tang` | Small covers, or when you need more room | LSB pairs, about 3 bits per pixel, easier to detect | up to 64 KiB |

Standard, Low (0.05), and Paranoid (0.02) are the stealth presets. The recipient does not have to pick the same one.

Rough room at Standard, after framing:

- 1 megapixel PNG: about 12 KB
- 12 megapixel PNG: about 150 KB

A JPEG's room depends on how many nonzero AC coefficients it has, not on its pixel count. `inspect` prints the number for a file.

```bash
java -jar target/whisper-1.0.0.jar inspect -i cover.png
java -jar target/whisper-1.0.0.jar inspect -i photo.jpg --json
```

## What you can hide

- **Text.** UTF-8. `--message` / `-m`, or `--text-file`. `--message-file` is the same as `--text-file`.
- **File.** Any bytes. `--file report.pdf`. The sanitized base name is stored so Extract can suggest it.
- **Image.** `--image secret.png`. If it does not fit, `--fit-quality 75 --fit-max-edge 1024` re-encodes a smaller JPEG copy of the *hidden* image. The cover is not re-encoded.

Passphrases: `-k` is the stego passphrase. `-E` / `--encrypt-key` is a separate encryption passphrase. Omit `-E` and the stego passphrase is used for both. `--cipher none` authenticates with HMAC and does not encrypt. `--kdf high` uses the 2 GiB Argon2id profile.

## Verification

On success the CLI and the app show the cipher and the full SHA-256 of the content. Copy that fingerprint and share it separately if you want the recipient to confirm they got the same bytes.

| What you see | Meaning |
|---|---|
| Verified · cipher · SHA-256 … | Tag and content hash match |
| Verified, not confidential · … | Tag and content hash match, and the encryption passphrase was blank |
| Verified, not encrypted · SHA-256 … | HMAC matched; anyone with the stego passphrase can read it |
| Nothing found with this key | Wrong stego passphrase, no payload, or the image was edited |
| Wrong encryption passphrase, or the payload was tampered with | The stego passphrase found a payload; the encryption passphrase or the bytes did not |
| Payload damaged (incomplete / decompression failed) | The stream is truncated or will not inflate. Nothing is shown |
| Integrity check failed: content hash does not match | The tag matched and the hash did not. The content is not shown |
| Tampered or wrong key (not encrypted) | Integrity-only HMAC failed |
| Unverified (legacy format) | A Whisper 0.9 image. Re-embed to get a content hash. Encrypted 0.9 images need `-E` and then read "AES-GCM tag OK; no content hash". Ciphertext is not shown |

## Security model

- The payload is sealed with ChaCha20-Poly1305 (default) or AES-256-GCM. Keys come from Argon2id (default: 3 passes, 64 MiB, 4 lanes) and HKDF-SHA-256, then an age-style 64 KiB STREAM.
- The content hash sits *inside* the authenticated frame. There is no magic banner and no clear hash in the embedded stream.
- Whisper does not protect against recompression, resize, filters, screenshots, a weak passphrase, or a determined steganalyst. Chi-square and RS in Analyze are relative checks, not a proof that a file is clean or dirty.
- Passphrases on the command line can be seen by other users on the machine. Prefer a private shell. JavaFX keeps passphrase text in strings the runtime will not wipe; that is a limit of the toolkit.
- There is no network access, telemetry, or auto-update.

## Legacy 0.9 images

Images embedded with Zhang–Tang, with or without AES-128-GCM/PBKDF2, still extract:

```bash
java -jar target/whisper-1.0.0.jar extract -i old.png -k "legacy-stego-key"
java -jar target/whisper-1.0.0.jar extract -i old.png -k "legacy-stego-key" -e -E "legacy-enc-key"
```

They are labelled unverified. Re-embed with `hill-stc` or `uerd-stc` if you want the content hash. New Zhang–Tang embeds that opt into a modern cipher use the v2 frame. The old `-e -c aes` path is unchanged.

## Build, test, bench

```bash
mvn -B verify
mvn -B -Pbench test    # Argon2id and STC timings
mvn -B -Pmem test      # 12 megapixel hill-stc, forked with -Xmx512m
```

The layout check runs when `whisper.layout` is true (CI sets this under Xvfb):

```bash
xvfb-run -a mvn -B -Dwhisper.layout=true -Dtest=LayoutRegressionTest test
```

## Credits and references

- Zhang and Tang, *A Novel Image Steganography Algorithm Against Statistical Analysis*, ICMLC 2007.
- Filler, Judas, and Fridrich, *Minimizing Additive Distortion in Steganography Using Syndrome-Trellis Codes*, IEEE TIFS 2011. The STC here is a clean-room implementation of that construction. It is not the Binghamton toolbox.
- Li, Wang, Huang, and Li, *A new cost function for spatial image steganography* (HILL), ICIP 2014.
- UERD as used for nonzero luminance AC coefficients.
- RFC 8439 (ChaCha20-Poly1305), RFC 9106 (Argon2), RFC 5869 (HKDF), and the age STREAM chunking pattern.
- Bouncy Castle `bcprov-jdk18on` 1.86 supplies Argon2id and HKDF only. It is not registered as a JCA provider. AEAD, SHA-256, and DEFLATE come from the JDK.

## License

See the repository license. The ported algorithms follow the same author's MIT Whisper2 design; that repository is retired and is not required to build or test Whisper.
