> Imported from Whisper2 @523e838 (MIT); Whisper2 retired. This file is historical design context. The Java implementation in this repository is the product; nothing here is required to build or test Whisper.

# 03 — Engine Shortlist (ranked for v1)

Honesty first: **classical adaptive embedding (cost + STC) is far more shippable overnight than any deep-learning method.** DL methods either (a) don't give bit-exact extraction, (b) need GPUs and multi-hundred-MB models, or (c) generate a new image instead of using the user's cover. See survey §2.

## Ranking

| Rank | Engine | Priority | Domain | Bit-exact? | Pure Go? |
|---|---|---|---|---|---|
| 1 | **HILL + binary STC** on PNG/TIFF/BMP/WebP-lossless | MUST | Spatial | Yes | Yes |
| 2 | **UERD + binary STC** on JPEG (no recompression) | MUST | JPEG DCT | Yes | Yes* |
| 3 | **Adaptive PCM + binary STC** on WAV | MUST | Audio | Yes | Yes |
| 4 | S-UNIWARD (fixed σ) + STC | SHOULD | Spatial | Yes | Yes |
| 5 | J-UNIWARD + STC | SHOULD | JPEG DCT | Yes | Yes* |
| 6 | FLAC (encode via mewkiz) using PCM engine | SHOULD | Audio | Yes | Yes |
| 7 | LSB-replacement / LSB-matching, keyed positions | NICE† | Spatial | Yes | Yes |
| 8 | Errorless robust JPEG (Butora lattices) | NICE (v2) | JPEG | Yes vs one coder | cgo |
| 9 | PDF TJ-kerning | NICE (v2) | PDF | Fragile | Yes |
| — | SteganoGAN / HiNet / ISN / PRIS / diffusion / LLM | **DO NOT** | — | No / fragile | No |

\* Needs a JPEG coefficient codec (fork stdlib or carefully audit `0verkilll/jpeg`); see §JPEG I/O below.
† Ship only behind an "Insecure / demo" label for CTF interop. Sequential LSB is broken by chi-square / RS / Sample Pairs.

## MUST #1 — Spatial HILL + STC

**Cover:** PNG, TIFF, BMP, lossless WebP (decode to `image.Image`, work in RGBA or NRGBA; prefer keeping the original colour type on write). Greyscale and RGB only for v1; reject paletted unless expanded.

**Pipeline**
1. Decode → contiguous `[]uint8` samples (use one channel, typically G, or cycle R,G,B).
2. Build HILL cost map (float64), wet-out saturated extremes if desired.
3. Derive a keyed random permutation of pixel indices from `stegoSeed` (crypto.HKDF → ChaCha8 stream → Fisher–Yates).
4. STC-embed the envelope bits into LSBs, flipping a sample by ±1 toward the lower-cost neighbour (binary STC on the LSB plane; when both directions have equal LSB effect, pick the lower HILL cost).
5. Re-encode **preserving the original PNG envelope**: ancillary chunks on a whitelist (`gAMA,sRGB,cHRM,pHYs,iCCP,sBIT`), drop `eXIf/tEXt/iTXt/zTXt/tIME`, and match original IDAT chunk size if known. Fall back to 8 KiB IDATs (not Go's 32 KiB default).
6. Immediately extract and compare envelope bytes; abort and leave no output file on mismatch.

**Capacity estimation (honest)**
```
usable = count(cost < wetThreshold)
maxBits ≈ 0.5 * usable * payloadRate     // payloadRate default 0.05–0.10 bpp of *image* area
```
UI shows: image pixels, usable pixels, estimated max payload bytes, and a red/amber/green detectability gauge. Refuse to embed above a hard cap of **0.2 bpp**; warn above 0.1 bpp. stenoxide's 0.02 bpp is a good "paranoid" preset.

**Roundtrip requirements**
- Bit-exact envelope recovery on the written file.
- Pixel-level: only intended ±1 changes.
- Idempotent extract: extract(extract's carrier) = same payload.
- Fails cleanly if the user re-saves the PNG in Preview/Photoshop with conversion to indexed colour or 16-bit.

## MUST #2 — JPEG UERD + STC

**Cover:** baseline DCT JPEG, greyscale or YCbCr. Reject progressive / arithmetic / 12-bit for v1 (detect and error with a clear message).

**Pipeline**
1. Parse JPEG → quantised DCT coefficients (Y, optionally Cb/Cr) + quant tables + all non-scan markers.
2. UERD costs per AC coefficient (skip DCs for better security, or include them — make it a flag; Butora notes DCs are often most robust but also sensitive).
3. Wet costs (=∞) for zeros if using an nsF5-style "don't fill zeros" policy; UERD usually allows signed ±1 with care about creating zeros.
4. STC embed; write a new JPEG that **byte-identical-copies** every marker segment except the entropy-coded scans you re-encode. Same quant tables, same restart markers, same APP0/APP1 (EXIF) decision as PNG (default: strip EXIF GPS for safety, with an opt-in `--keep-metadata`).
5. Roundtrip verify.

**Capacity:** default max **0.1 bpnzAC**, warn at 0.15, hard-cap 0.3. Prefer QF ≥ 75 covers; warn on QF < 70 and on already-double-compressed signatures.

**JPEG I/O options (pick during engine spike)**
| Option | Licence | Risk | Notes |
|---|---|---|---|
| Fork `image/jpeg` decoder + write Huffman encoder | BSD-3 (Go) | Med eng. effort | Provenance clear; jsteg shows the fork pattern |
| `github.com/0verkilll/jpeg` | MIT | **High supply-chain** (0★, first release 2026-06) | Claims DCT access; must audit before use |
| cgo `libjpeg-turbo` | IJG / BSD-style | Med | Bit-exact with many real compressors; needed later for robust mode |
| `lukechampine.com/jsteg` as-is | MIT | Wrong abstraction | Re-encodes from `image.Image`; loses exact coeff control |

Recommendation: **spike A (fork) for 2–3 days; keep cgo libjpeg behind a build tag for v2 robust mode.**

## MUST #3 — WAV PCM adaptive + STC

**Cover:** WAV, PCM s16le or s24le, mono or stereo. Reject float WAV, extensible edge cases, and >2 channels for v1.

**Pipeline**
1. Parse RIFF; copy all non-data chunks verbatim.
2. Costs: 1-D HILL analogue — high-pass residual → abs → local mean → invert. Wet near digital silence (`|x|<ε`) and at clip.
3. Embed across interleaved channels or mid-channel only (flag).
4. Write WAV with identical header fields.

**Capacity:** default 0.02 bits/sample; hard cap 0.1. A 3-minute 44.1 kHz mono s16 track at 0.02 bps ≈ 66 KB payload.

## SHOULD
- **S-UNIWARD** with σ raised per Denemark et al. 2014 (prevents probability streaking).
- **J-UNIWARD** once JPEG I/O is solid; cache wavelet window computations.
- **FLAC** via `mewkiz/flac` encoder: decode → PCM embed → re-encode. Accept that re-encode changes compression ratio; never claim bit-identical container.
- Progressive JPEG write support.

## NICE / later
- Robust errorless JPEG (Butora): "Survive Slack upload" toggle, cgo convert/mozjpeg.
- PDF TJ kerning via pdfcpu (warn heavily about fragility).
- APNG frame sequential spatial embedding.
- Insecure LSB mode for teaching / CTF.

## DO NOT (v1)
Anything requiring ONNX/PyTorch, anything with BER > 0 without a full ECC stack and a UX that explains "probabilistic recovery", anything that generates a new cover with Stable Diffusion, anything that encrypts the carrier.

## Cross-engine contract

```go
type Capacity struct {
    MaxPayloadBytes int
    RecommendedBytes int
    RateUnit string  // "bpp" | "bpnzAC" | "bps"
    Rate float64
    Warnings []string
}

type Engine interface {
    Name() string
    Accepts(filename string, head []byte) bool
    Estimate(cover []byte, opts Options) (Capacity, error)
    Embed(cover, payload []byte, seed []byte, opts Options) (stego []byte, meta Meta, err error)
    Extract(stego []byte, seed []byte, opts Options) (payload []byte, meta Meta, err error)
}
```

Every `Embed` MUST call `Extract` internally and check `bytes.Equal`. Every engine MUST be deterministic given `(cover, payload, seed, opts)`.

## Test corpus (minimum)
- BOSSBase / ALASKA subsets are academic; for CI use a **home-grown corpus**: 20 PNGs (photo, flat UI screenshot, saturated sunset, 1×1, 8K), 20 JPEGs (QF 50/75/95, greyscale, 4:2:0, EXIF-heavy iPhone), 10 WAVs (silence, voice, music, clipping).
- Property tests: random payload lengths from 0 to MaxPayload; random seeds; `-race`; fuzz the parsers with `go-fuzz`/`testing.F`.
