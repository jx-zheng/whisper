# 02 — Steganography schemes port (H1: ≥2 modern schemes), architecture, performance

Source of truth to port: Whisper2 @ `523e838` (MIT, same author). In Phase 0 it gets vendored into `docs/research/` and `src/test/resources/golden/`, because Whisper2 will be deleted. Files:
`internal/stego/stc/stc.go` (417 LOC), `internal/stego/permute/permute.go`, `internal/stego/frame.go` (382), `internal/stego/rate.go`, `internal/stego/costs/{hill,uerd}.go`, `internal/engine/{spatial,jpeg}/*.go`, `internal/carrier/{pngx/png.go (429), jpegx/jpeg.go (856)}`, `docs/research/03-engine-shortlist.md`, `08-stego-framing.md`.

## 1. Scheme line-up
| id | Display name | Covers in → stego out | Domain / cost | Rate (default · warn · hard) | Status |
|---|---|---|---|---|---|
| `hill-stc` | **Adaptive (HILL)**, recommended for PNG | PNG (8-bit grey/RGB/RGBA, non-interlaced) or BMP 24/32-bit → **PNG** (a BMP cover may also write BMP) | Spatial. HILL cost: KB high-pass → abs → 3×3 mean → reciprocal → 15×15 mean. Runs on the **green** plane (grey for greyscale); alpha is never touched. Binary STC h=10 on LSB parity, ±1 toward the in-range side. | 0.10 · 0.10 · 0.20 **bpp** | **MUST (new)** |
| `uerd-stc` | **Adaptive (JPEG)**, for JPEG photos | Baseline JPEG (8-bit, Huffman, YCbCr/grey) → **JPEG with the same quant/Huffman tables, no recompression** | DCT. UERD cost ρ = q(u,v)·(1/E + 1/Ē). The vector is the **nonzero luminance AC** coefficients. Coefficients with **\|c\|=1 are wet**, so no change can create or kill a nonzero and both sides see the same vector. DC is wet. Changes prefer shrinking toward zero. | 0.10 · 0.15 · 0.30 **bpnzAC** | **MUST (new)** |
| `suniward-stc` | Adaptive (S-UNIWARD) | Same as hill-stc | Spatial S-UNIWARD (Daubechies-8 wavelet filters, σ raised per Denemark 2014) + STC | Same as HILL | **Fallback only.** Build it if `uerd-stc` misses its Phase-5 gate, so H1 still holds. |
| `zhang-tang` | **Legacy (high capacity)** | PNG/BMP → PNG/BMP | Existing `ZhangTangScheme`: LSB-pair, 3 bits/pixel, keyed PBKDF2 walk | n/a (~3 bpp; 65,535 B cap) | Kept. New embeds carry the **new envelope** (01 §4). Old 0.9 images go through the legacy reader. The UI labels this scheme "easier to detect". |

Rejected, with reasons from the Whisper2 research:
- DL/GAN/INN/diffusion methods are not bit-exact and need a GPU.
- Robust JPEG needs the exact target compressor.
- Plain LSB is broken by χ²/RS, and Whisper1's own Analyze tab demonstrates that.

**Analyze tie-in:** χ²/RS should stay near cover values for `hill-stc` at 0.10 bpp. Add an informational test for this, like `SteganalysisResistanceTest`.

## 2. Shared core (port 1:1, Java-idiomatic)
### 2.1 STC: `com.jxzheng.whisper.schemes.stc.Stc`
- Clean-room binary syndrome-trellis code (Filler–Judas–Fridrich, TIFS 2011). Derive the submatrix Ĥ from (h, w) exactly as Whisper2 `submatrix()` does, with the first and last rows all ones. **Never copy the Binghamton C++.**
- API: `static int embed(byte[] coverBits, float[] costs, byte[] message, int h, byte[] outBits)` returns the change count. `static byte[] extract(byte[] bits, int msgBytes, int h)`.
- **Segmented Viterbi with checkpoints** (Whisper2 `segmentColumns = 8192`):
  - The forward pass keeps per-segment path-cost checkpoints (`double[2^h]`).
  - The backward pass replays one segment at a time into a reusable **bit-packed `long[]` backpointer**: 8192 × 1024 bits = 1 MiB at h=10.
  - Scratch stays bounded (`TrellisScratch`). This is the single most important memory rule in the port.
- Costs: `float[]` cost map, path accumulation in `double`. Wet = `Float.POSITIVE_INFINITY`. NaN or negative costs are an error.
- Allocate the trellis arrays once per `embed` and reuse them across segments. No per-column allocation, boxing, or streams/lambdas in hot loops.

### 2.2 Keyed permutation: `schemes.permute.KeyedPermutation`
- Fisher–Yates over `int[n]`, with randomness from a **ChaCha20 keystream** (32-byte key = seed, nonce = 12 zero bytes, counter 0).
- Take 4096-byte blocks and read **u32 little-endian** values. Use rejection sampling with `limit = (2^32 / n) * n`.
- This is Whisper2 `permute.Order`; it must match bit-for-bit, checked against the Go golden vectors.
- Memory: one `int[n]` (12 MP → 48 MB), which is acceptable for now. A Feistel cycle-walking permutation that needs no array is a later option, not part of this PR.

### 2.3 Framing: `schemes.framing.ParityFraming` (Whisper2 `frame.go` + `08-stego-framing.md`)
1. **Public salt (16 B):** STC-coded on a **public** Fisher–Yates subset. Its spread seed is the fixed 32-byte constant from `frame.go`; copy it verbatim (it is not a secret). Width is `w = ceil(1/rateCap)`, so the local rate stays ≤ the cap. If the cover can't hold `128·w + 256` samples, fail with a capacity error. Never narrow `w`.
2. **Keyed part,** on the remaining samples in `KeyedPermutation(visitSeed)` order: a 4-byte BE length prefix (STC width 8, span 256), then the envelope bytes at width `rest / (8·len)`.
3. **Self-check:** read back both the public salt and the keyed payload from the modified samples. On any mismatch, abort and write no output.

**Rate presets replace the free slider.** This is a UX choice, and it also fixes Whisper2's "rate must match on reveal" problem. The presets are `STANDARD = 0.10`, `LOW = 0.05`, and `PARANOID = 0.02`.
- Embed uses the preset the user chose.
- Reveal **tries all three**. Reading the public salt is cheap, and Argon2id is cached per distinct salt (01 §3), so the recipient never needs to know which preset the sender used.
- The CLI exposes `--preset` with preset names only.

### 2.4 Capacity: `schemes.Capacity`
Port `UserBudget` and `PayloadRate`. Report `maxBytes` (at the hard cap) and `recommendedBytes` (at the chosen preset) **after** subtracting the 01 §4 overhead: salt 16, prefix 4, envelope 58+, frame 45 + name + MIME. The UI meter uses these numbers directly.

## 3. Carriers (codecs): `com.jxzheng.whisper.carrier`
| Codec | Port of | Why not ImageIO |
|---|---|---|
| `PngCodec` | `pngx` | Embedding needs **sample-exact** 8-bit planes without a `BufferedImage` `int[]` ARGB copy. The codec also handles: an ancillary-chunk whitelist (`gAMA sRGB cHRM pHYs iCCP sBIT`); dropping `eXIf tEXt iTXt zTXt tIME` unless "Keep metadata" is on; **8 KiB IDAT** chunks (avoids encoder fingerprints); adaptive per-row filters (min-sum heuristic). |
| `BmpReader` | new (~80 LOC) | 24/32-bit uncompressed only. |
| `JpegCoefficients` | `jpegx` | ImageIO exposes no quantized DCT. The codec parses the markers and keeps every segment byte-identical except the regenerated entropy scan. It handles baseline and restart markers, and rejects progressive, arithmetic, 12-bit, and lossless files with clear messages. It exposes `luma()`, `setLuma()`, and `nzac()`. |
| `CoverConverter` (UX) | new | For paletted, interlaced, or 16-bit PNG, GIF, and WebP covers, offer **"Convert to 8-bit PNG cover"** via ImageIO and warn that the output format differs from the original. |

`Carrier` holds an immutable header plus a **mutable plane**: `byte[]` samples, or `int[]` coefficients for JPEG. It is decoded **once** per path.

## 4. Pluggable architecture (H4)
```
com.jxzheng.whisper
├── crypto/        AeadSuite, ChaCha20Poly1305Suite, AesGcmSuite, IntegrityOnlySuite, Argon2idKdf, Hkdf, Stream, Envelope, KeyCache, Secrets
├── payload/       PayloadKind{TEXT,FILE,IMAGE}, PayloadFrame, PayloadCodec, MimeSniffer, NameSanitizer, ImageFit
├── schemes/       StegoScheme, SchemeRegistry, SchemeInfo, Capacity, RatePreset, EmbedOptions
│   ├── stc/       Stc, Trellis (segmented), Submatrix
│   ├── permute/   KeyedPermutation
│   ├── framing/   ParityFraming
│   ├── costs/     HillCost, UerdCost, (SUniwardCost)
│   ├── adaptive/  HillStcScheme, UerdStcScheme
│   └── zhangtang/ ZhangTangScheme (moved), LegacyFormat
├── carrier/       Carrier, CarrierCache, PngCodec, BmpReader, JpegCoefficients, CoverConverter
├── ops/           StegoOperations (facade: hide/reveal/inspect/analyze), HideRequest, RevealResult, Progress
├── analysis/      (unchanged)
├── drivers/       CliDriver (new flags)
└── gui/           existing + payload/{PayloadEditor, TextEditor, FileEditor, ImageEditor, PayloadViewer, TextViewer, FileViewer, ImageViewer}, CapacityMeter, VerifiedBadge
```
```java
public interface StegoScheme {
    String id();                         // "hill-stc"
    SchemeInfo info();                   // display name, description, formats, security tier, unit
    boolean accepts(CarrierProbe probe); // magic bytes first, extension second
    Capacity estimate(Carrier cover, EmbedOptions opt);
    Carrier embed(Carrier cover, byte[] envelope, byte[] visitSeed, EmbedOptions opt, Progress p); // must self-verify
    byte[] extract(Carrier stego, byte[] visitSeed, EmbedOptions opt);
    Optional<byte[]> publicSalt(Carrier stego, RatePreset preset); // empty for zhang-tang
}
```
- **Registry.** `SchemeRegistry` is an explicit ordered list, so the UI order is stable. `StegoOperations.schemes()` feeds the existing `Forms.choice(...)` combo, so **new schemes need no UI code**. This is the existing UI-extensibility pattern Kevin pointed to, extended with `SchemeInfo` descriptions.
- **Auto on reveal:**
  - JPEG → `uerd-stc`.
  - PNG/BMP → `hill-stc` (all 3 presets) → `zhang-tang` with the v2 envelope → legacy 0.9.
  - Report which scheme matched.
- **Layering:** no scheme imports `gui`, and `gui` talks only to `ops`. Every package is testable headless.

## 5. Performance and memory (concrete)
**Decode once.** Today `EmbedPane.reloadCover()` decodes the cover for the preview, and then `embed()` decodes it **again** via `StegoOperations.readImage`. Replace both with a `CarrierCache`:
- one entry, keyed by `path + size + lastModified`,
- held through a `SoftReference`,
- shared by the preview, the capacity estimate, and embed.

Make the preview thumbnail from the decoded carrier using a subsampled view, not a second ImageIO read.

**Planes, not BufferedImage.** Work on the codec's `byte[]` samples, extracting the green plane into a reusable `byte[]`. Don't call `getRGB` or allocate `Color` per pixel in hot paths. Legacy `ZhangTangScheme` does this, which is fine there, but the new code must not copy it.

**HILL cost** uses separable prefix sums: O(n), independent of window size (the 15×15 box becomes two 1-D running sums). Residual and costs live in `float[]` arrays with ping-pong scratch. Rows can optionally be split across a `ForkJoinPool`; the result stays deterministic. STC itself is serial.

**Peak heap** for a 12 MP RGB PNG with hill-stc:

| Item | Approx. |
|---|---|
| file bytes | ~20 MB |
| samples | 36 MB |
| green plane | 12 MB |
| costs | 48 MB |
| scratch | 48 MB |
| permutation | 48 MB |
| bit arrays | 2×12 MB |
| trellis | ~2 MB |
| encoder output | ~20 MB |
| **Total** | **≤ 320 MB** |

Gate: a `@Tag("mem")` test, forked with `-Xmx512m`, embeds and extracts a 12 MP synthetic PNG.

**JPEG:** keep the luma coefficients as a single `int[]` (or `short[]`). Copy the chroma scan data through unchanged. Never decode to pixels.

**Time budgets** (mid laptop, JDK 21; the PR reports measured CI numbers):

| Benchmark | Target |
|---|---|
| `HillCost` 12 MP | ≤ 1.5 s |
| `Stc.embed` 1,000,000 cols, h=10 | ≤ 1.5 s |
| `Stc.extract` 1,000,000 cols | ≤ 100 ms |
| `KeyedPermutation` 12 M | ≤ 400 ms |
| hill-stc hide end-to-end 12 MP, excl. Argon2id | ≤ 6 s |
| uerd-stc hide 8 MP JPEG, excl. Argon2id | ≤ 6 s |
| Reveal (Argon2id cached) 12 MP | ≤ 3 s |

Benchmarks are JUnit `@Tag("bench")` tests: `System.nanoTime`, warm-up, then the median of 5, run via `mvn -Pbench test`. JMH (GPLv2+CPE) is fine as a **test-scope** dependency only.

## 6. Tests (must pass)
- **Golden vectors from Go** (Phase 0, before Whisper2 is deleted):
  - `permute.Order(n=1000, seed=00..1f)`
  - `stc.Embed`/`Extract` on fixed inputs, h ∈ {7, 10}
  - `HILL` on a 64×64 crop of `testdata/png/photo.png` (relative tolerance ≤1e-6)
  - `UERD` on `testdata/jpeg/noise.jpg`
  - `publicLayout` indices for n=100,000 at the three rates

  Commit them as JSON under `src/test/resources/golden/`, with a README naming the generator and the source commit.
- **STC properties** (seeded, ≥500 cases): `extract(embed(c, ρ, m)) == m`; wet positions never change; exhaustive min-cost check on tiny instances (n ≤ 16).
- **Round trips** over scheme × fixture × preset × payload size {0, 1, 100, near max}:
  - Over capacity → `MessageTooLongException` that includes the numbers.
  - Self-check failure → no file written.
- **Carrier fidelity:**
  - PNG: every IDAT chunk is 8192 bytes except the last; the ancillary whitelist is honored.
  - JPEG: every non-scan segment is byte-identical; only luma AC coefficients with |c|≥2 change, by ±1.
- **Fuzzing:** `PngCodec`, `JpegCoefficients`, and the framing code, using mutated fixtures (seeded, 10k iterations). They may throw only checked exceptions and must never OOM.
- **Fixtures:** port Whisper2 `testdata/png/{flat,greyscale,large,photo,tiny}.png` and `testdata/jpeg/noise.jpg`, and add a real QF75 4:2:0 JPEG and a 24-bit BMP, keeping the total under 2 MB. Generate the 12 MP inputs inside the tests.
