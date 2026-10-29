> Imported from Whisper2 @523e838 (MIT); Whisper2 retired. This file is historical design context. The Java implementation in this repository is the product; nothing here is required to build or test Whisper.

# 02 — Crypto Design (payload AEAD only)

## Non-negotiable product rule
**Encrypt the payload. Never encrypt (or scramble) the carrier.** The carrier must remain a valid, visually/audibly normal media file. Changing the cover into "encrypted noise" is a watermarking/encryption anti-pattern, destroys deniability, and is trivial to spot.

## Threat model

### Assets
- Confidentiality and authenticity of the user-supplied message bytes.
- Steganographic undetectability of the *presence* of a payload (separate from crypto; handled by the engine and payload size).
- The passphrase (or raw 32-byte key) used to derive keys.

### Adversaries
1. **Passive warden (Eve):** observes the carrier. Goal: decide cover vs stego, or recover the message. Crypto does not stop detection; it stops reading the message if the stego is found.
2. **Active warden:** may modify the carrier (recompress, resize, strip metadata). Without a robust engine, extraction fails. Crypto must **fail closed** (auth tag mismatch → clear "tampered or wrong password" error; never return garbage plaintext).
3. **Offline passphrase attacker:** recovers the stego, extracts the ciphertext bits, then brute-forces the passphrase. Mitigated by Argon2id memory hardness. (stegseek cracks steghide's weak KDF through rockyou.txt in ~2 s — that must never happen here.)
4. **Malicious cover / malicious stego file:** crafted to crash or exploit the decoder. Mitigated by strict parsers, size limits, and fuzzing.

### Out of scope (v1)
- Forward secrecy across sessions, recipient public-key exchange, multi-party.
- Denying that *some* stego scheme was used (impossible against a determined steganalyst with the right detector). Goal is "looks like an ordinary photo/audio to humans and to non-specialised tools".
- Side-channel resistance beyond not logging secrets and wiping key material after use.

## Algorithms (stdlib / x/crypto only)

| Role | Algorithm | Source |
|---|---|---|
| Password KDF | **Argon2id** | `golang.org/x/crypto/argon2` (BSD-3) |
| Key expansion | **HKDF-SHA-256** | `crypto/hkdf` (stdlib since Go 1.24+) |
| Default AEAD | **ChaCha20-Poly1305** | `golang.org/x/crypto/chacha20poly1305` (also `crypto/cipher` wrappers) |
| Alternate AEAD | **AES-256-GCM** | `crypto/aes` + `cipher.NewGCM` (stdlib; AES-NI / ARM crypto extensions) |
| Optional AEAD | **XChaCha20-Poly1305** | `golang.org/x/crypto/chacha20poly1305.NewX` — prefer if a single long stream without chunking is ever needed. With chunked STREAM, 12-byte nonces are fine. |
| Compression (optional, before AEAD) | **zstd** | `github.com/klauspost/compress/zstd` (Apache-2.0 / MIT / BSD — see module NOTICE) |
| CSPRNG | `crypto/rand` | stdlib |
| Secure wipe (best effort) | `runtime/secret` (Go 1.26+, `GOEXPERIMENT=runtimesecret`, linux/amd64+arm64) and/or `github.com/awnumar/memguard` (Apache-2.0) | |

**No custom crypto. No AES-CBC, no AES-CTR-without-MAC, no XOR, no "homebrew cascade".**

## Key derivation

```
salt     ← 16 random bytes          (stored OUTSIDE crypto blob: in stego PRNG seed material — see "where salt lives")
pass     ← user passphrase (UTF-8) or empty if --key-file is used
seed     ← Argon2id(pass, salt, t, m, p, 32)     // 32-byte master seed
encKey   ← HKDF-SHA-256(ikm=seed, salt=empty, info="whisper2 v1 aead")
stegoKey ← HKDF-SHA-256(ikm=seed, salt=empty, info="whisper2 v1 stego")  // seeds the visit-order PRNG
```

### Argon2id profiles (RFC 9106 §4)
| Profile | t | m | p | Wall-clock target (desktop) |
|---|---|---|---|---|
| **default** ("second recommended") | 3 | 2^16 KiB = **64 MiB** | 4 | ~0.3–0.8 s |
| **high** ("first recommended") | 1 | 2^21 KiB = **2 GiB** | 4 | ~0.5–2 s if RAM available |
| **interactive** (CI / tiny VMs only) | 2 | 2^15 KiB = 32 MiB | 1 | Not for release defaults |

Store the profile id (1 byte) with the salt so the extractor uses the same parameters.

### Key-file mode
If the user supplies a 32-byte key file (or base64), skip Argon2id: `seed = keyfile`. Still run HKDF for domain separation. Reject key files that aren't exactly 32 raw bytes or 44/64-char base64/hex of 32 bytes.

## Container format (the bytes that get *embedded*)

The embedded blob must look like uniform random bits. **No ASCII magic, no version string, no "WHISPER2" banner.**

```
offset  size   field
0       1      flags
                 bit0..3: format version (1)
                 bit4:    compression used (1=zstd)
                 bit5:    AEAD id (0=ChaCha20-Poly1305, 1=AES-256-GCM)
                 bit6..7: reserved (0)
1       1      kdf profile id (1=default, 2=high, 3=interactive, 0=keyfile/no-kdf)
2       16     salt
18      16     file nonce (random per embed; HKDF-salt for payload key — age pattern)
34      8      plaintext length (uint64 BE) of the *original* message
                 (authenticated as AEAD AAD; also bounds decompression)
42      …      STREAM ciphertext (see below)
```

**STREAM framing (age / Miscreant / Tink style — C2SP age.md ✅):**
- `payloadKey = HKDF-SHA-256(ikm=encKey, salt=fileNonce, info="whisper2 v1 payload")`
- Split plaintext (optionally after zstd) into 64 KiB chunks.
- Each chunk: `ChaCha20-Poly1305(payloadKey, nonce= [7 zero bytes || uint32BE(seq) || lastFlag], plaintext)`
  - `lastFlag` is 0x00 for intermediate chunks and 0x01 for the final chunk (must be non-empty unless the whole payload is empty).
  - Ciphertext chunk = ciphertext ‖ 16-byte tag.
- Decrypt streams one chunk at a time; **abort on any tag failure**; reject a stream that ends without a final chunk.

`plaintext length` goes into AEAD additional data of chunk 0 (and is checked after decrypt). Overlong claimed lengths are rejected before allocating.

### Where the salt and nonce live
Two viable designs; pick one and stick to it:

**A — Header-in-payload (recommended for Whisper2 v1):** salt and file nonce sit at the start of the embedded bitstream above. Extractor reads the first 42 bits-as-bytes from the recovered bitstream, derives keys, then authenticates the rest. Simpler, auditable, matches age's "header then STREAM body" shape. Detectable only if an attacker already knows a file is stego *and* knows our header layout — at which point crypto (not stego) is what matters.

**B — Zero-overhead (stenoxide style):** salt is derived from a perceptual hash of the cover; nothing but ciphertext bits is stored. Higher stego purity, but: (i) any cover pixel the engine is allowed to change also changes the salt, so the salt must be computed from a *stable* subset of the cover (e.g. MSBs, or a thumbnail) **before** embedding, and both sides must agree; (ii) debugging wrong-password vs wrong-cover becomes harder. Defer to v1.1 once engines are solid.

## Compression
- Default **off** for already-compressed inputs (zip, jpg, mp4, age files). Auto-detect by entropy or magic.
- When on: zstd level 3 (fast). Compress → AEAD. Never AEAD → compress.
- Cap decompressed size at `min(claimed_length, 256 MiB)` (configurable).

## API sketch

```go
package crypto // import "github.com/jx-zheng/Whisper2/internal/crypto"

type Profile uint8 // Default, High, Interactive, KeyFile

type SealParams struct {
    Passphrase []byte     // wiped by Seal
    KeyFile    []byte     // alternative; mutually exclusive
    Profile    Profile
    AEAD       AEADKind   // ChaCha20Poly1305 | AES256GCM
    Compress   bool
}

type Envelope struct { /* flags, profile, salt, nonce, ptLen, ciphertext */ }

func Seal(plaintext []byte, p SealParams) (env Envelope, stegoSeed []byte, err error)
func Open(env Envelope, passphrase, keyFile []byte) (plaintext []byte, stegoSeed []byte, err error)

func (e Envelope) Bytes() []byte      // canonical serialisation for the embedder
func ParseEnvelope(b []byte) (Envelope, error)
```

`stegoSeed` (32 bytes) is the HKDF-derived seed the engine uses for the keyed visit-order PRNG. Seal and Open both return it so embed and extract stay in lockstep.

## Anti-patterns (refuse these in review)
1. Encrypting / shuffling / noising the carrier pixels "for extra security".
2. Storing the passphrase, seed, or raw AEAD key in logs, crash reports, or UI state.
3. Using `math/rand` or time-based seeds for salt/nonce.
4. AES-ECB, AES-CBC without HMAC, RC4, Blowfish, "AES-256 + SHA-512 cascade".
5. Embedding a plaintext filename, MIME type, or magic that survives extraction without being inside the AEAD.
6. Truncating Argon2id output to less than 32 bytes.
7. Reusing a file nonce under the same key.
8. Returning unauthenticated plaintext on tag failure "so the user can see something".
9. Disabling the auth tag "for capacity".
10. Rolling a custom STREAM variant when the age construction already exists and is reviewed.

## Testing requirements
- Wycheproof-style vectors for our STREAM framing (hand-crafted).
- Cross-check ChaCha20-Poly1305 and AES-GCM against known RFC / stdlib vectors.
- Argon2id against RFC 9106 test vectors.
- Property: Seal→Open roundtrip; bit-flip any byte of Envelope.Bytes() → Open returns err; wrong passphrase → err; truncated stream → err.
- Race detector on concurrent Seal/Open.
- `GOEXPERIMENT=runtimesecret` build smoke test on linux/amd64.
