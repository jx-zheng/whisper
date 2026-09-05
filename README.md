# whisper

Steganographic messaging via Zhang–Tang LSB-pair embedding.

**whisper** is a Java command-line tool that embeds secret messages in RGB images using the Zhang–Tang LSB-pair steganography scheme from *[A Novel Image Steganography Algorithm Against Statistical Analysis](https://ieeexplore.ieee.org/document/4370824)* (Hong-Juan Zhang & Hong-Jun Tang, ICMLC 2007).

The scheme hides each secret bit `m` in a color-channel sample `c` relative to the previous sample `p` so that `(LSB(p) + LSB(c')) mod 2 == m`, adjusting `c` by at most ±1 (with overflow corrected by ±2). Pixel walk order is keyed, so extraction needs the same passphrase. A truncated HMAC over the length header rejects most wrong-key extractions.

Optional AES-128-GCM (PBKDF2 key derivation) can encrypt the payload before embedding.
Pixel walks are keyed from SHA-256(passphrase); use `-e` when confidentiality matters.
Passphrases on the command line may be visible to other local users — prefer a private shell history.

## Requirements

- Java 21+
- Maven 3.8+

## Building

```bash
mvn clean package
```

This produces an executable uber-JAR at `target/whisper-0.9.0.jar`.

## Usage

```bash
# Embed a plaintext message
java -jar target/whisper-0.9.0.jar embed \
  -i cover.png -o stego.png -m "hello" -k my-secret-key

# Embed with AES encryption
java -jar target/whisper-0.9.0.jar embed \
  -i cover.png -o stego.png -m "hello" -k my-secret-key -e

# Extract (print to stdout)
java -jar target/whisper-0.9.0.jar extract -i stego.png -k my-secret-key

# Extract encrypted payload
java -jar target/whisper-0.9.0.jar extract -i stego.png -k my-secret-key -e
```

| Flag | Meaning |
|------|---------|
| `-i, --input-file` | Cover/stego image |
| `-o, --output-file` | Output stego image (embed) or message file (extract) |
| `-m, --message` | Message text to embed |
| `-f, --message-file` | Read embed message from a file |
| `-k, --key` | Stego key (and cipher passphrase when `-e` is set) |
| `-e, --encrypt` | Encrypt before embed / decrypt after extract |
| `-s, --scheme` | Scheme name (default: `zhang-tang`) |
| `-c, --cipher` | Cipher name when `-e` is set (default: `aes`) |
| `-h, --help` | Usage help |

Prefer lossless formats such as PNG for the stego output. Lossy formats (e.g. JPEG) will destroy the embedded bits.

## Testing

```bash
mvn test
```
