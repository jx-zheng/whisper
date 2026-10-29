# whisper

Steganographic messaging via Zhang–Tang LSB-pair embedding.

**whisper** is a Java command-line tool, with a desktop app for the same operations. It embeds secret messages in RGB images using the Zhang–Tang LSB-pair steganography scheme from *[A Novel Image Steganography Algorithm Against Statistical Analysis](https://ieeexplore.ieee.org/document/4370824)* (Hong-Juan Zhang & Hong-Jun Tang, ICMLC 2007).

The scheme hides each secret bit `m` in a color-channel sample `c` relative to the previous sample `p` so that `(LSB(p) + LSB(c')) mod 2 == m`, adjusting `c` by at most ±1 (with overflow corrected by ±2). Pixel walk order follows the **stego passphrase**. A truncated HMAC over the length header rejects most wrong-key extractions.

The stego passphrase is optional. Omit `-k`, leave it empty, or leave the Stego passphrase field blank to use the public default walk: PBKDF2-HMAC-SHA256 of the empty string with the fixed salt `whisper-stego-prng-v1`. That walk is not a secret — anyone can extract. HMAC-SHA256 rejects a zero-length key, so the length-header tag for this default uses the same PBKDF2 output. A non-empty stego passphrase still keys that tag with the raw UTF-8 passphrase. Spaces are a real passphrase; only a missing or empty value selects the default.

Optional AES-128-GCM encrypts the hidden message before embedding. The stego file stays a normal PNG or BMP; the container is not encrypted. The **encryption passphrase** is separate from the stego key. Pass it with `-E` / `--encrypt-key` (or the Encryption passphrase field). It is never copied from `-k`. Omit `-E`, pass `-E ""`, or leave the field blank to use the public empty-string AES-GCM key: PBKDF2 of the empty password with a random salt, plus the usual IV and tag. That key authenticates the payload and does not provide confidentiality. Omitting `-E` while `-e` is set prints a warning on stderr. An explicit `-E ""` does the same derivation without the warning.

Older builds used `-k` for both the pixel walk and AES-GCM. Extract those images with the same string on both flags: `extract -k K -e -E K`.

Pixel walks use a PBKDF2-stretched HMAC-SHA256 counter PRNG (not `String.hashCode()`). This changes walks vs earlier builds — re-embed existing messages. Use `-e` with an encryption passphrase when confidentiality matters.
Passphrases on the command line may be visible to other local users — prefer a private shell history.

## Requirements

- Java 21+
- Maven 3.8+

## Building

```bash
mvn clean package
```

This produces an executable uber-JAR at `target/whisper-0.9.0.jar`. The CLI is unchanged: `java -jar` still runs embed, extract, and analyze.

## Desktop app

The GUI calls the same embed, extract, and analyze code as the CLI. It is dark only: there is no light palette and no theme toggle. Drag the window to resize it. The preview sits beside the form when the window is wide and moves above the form when it is narrow. Message areas grow with the window; the page scrolls when the content is taller than the window. The smallest size is 640×480. It needs a display. Maven downloads the OpenJFX 21 natives for the OS you are building on (macOS, Windows, or Linux). On Linux the machine also needs GTK 3, which desktop installs already have.

```bash
mvn javafx:run
```

The shaded JAR does not bundle JavaFX, so `java -jar` stays the CLI.

Embed, Extract, and Analyze are separate tabs: cover or stego file (you can type a path, browse, or drop a file), an optional masked **Stego passphrase**, scheme (`zhang-tang`), and cipher (`aes`, used when encryption is on). **Encryption passphrase** is a second masked field, shown only when **Encrypt payload (AES-GCM)** or **Decrypt payload (AES-GCM)** is checked. Both fields are optional. Message files are UTF-8 text, same as `--message-file`. Stego output is PNG or BMP. Encryption matches `-e`: it encrypts the hidden message before embedding, and the output stays a normal PNG or BMP. After a successful embed, **Open in Extract** copies the stego path, both passphrases, and the encryption setting. Analyze does not use a passphrase. It shows the chi-square and RS figures from the CLI.

## Usage

```bash
# Embed with the public default pixel walk (no stego key)
java -jar target/whisper-0.9.0.jar embed \
  -i cover.png -o stego.png -m "hello"

# Embed with a stego key
java -jar target/whisper-0.9.0.jar embed \
  -i cover.png -o stego.png -m "hello" -k walk-secret

# Embed with a stego key and a different AES passphrase
java -jar target/whisper-0.9.0.jar embed \
  -i cover.png -o stego.png -m "hello" -k walk-secret -e -E cipher-secret

# Extract the public-walk message
java -jar target/whisper-0.9.0.jar extract -i stego.png

# Extract an encrypted payload
java -jar target/whisper-0.9.0.jar extract -i stego.png -k walk-secret -e -E cipher-secret

# Chi-square + RS steganalysis checks
java -jar target/whisper-0.9.0.jar analyze -i stego.png
```

| Flag | Meaning |
|------|---------|
| `-i, --input-file` | Cover/stego image |
| `-o, --output-file` | Output stego image (embed) or message file (extract) |
| `-m, --message` | Message text to embed |
| `-f, --message-file` | Read embed message from a file |
| `-k, --key` | Stego / pixel-walk key. Optional. Omitted or empty uses the public empty-string walk |
| `-e, --encrypt` | Encrypt the message payload before embed / decrypt after extract. Does not encrypt the image |
| `-E, --encrypt-key` | Encryption passphrase (PBKDF2 → AES-GCM) for the payload only. Not copied from `-k`. Omitted or empty (`-E ""`) is the public empty-string cipher key. Omitting it with `-e` prints a warning |
| `-s, --scheme` | Scheme name (default: `zhang-tang`) |
| `-c, --cipher` | Cipher name when `-e` is set (default: `aes`) |
| `-h, --help` | Usage help |

**Use PNG (or BMP).** Embed allow-lists PNG/BMP only — JPEG/WebP/GIF and unknown extensions are rejected because they destroy LSBs (GIF via palette quantization). Cover warnings are filename-extension heuristics only.

## Statistical checks

`analyze` runs Westfeld–Pfitzmann chi-square and Fridrich RS heuristics. Treat them as relative diagnostics (cover vs stego), not calibrated detectors — absolute thresholds vary by image.

## Testing

```bash
mvn test
```
