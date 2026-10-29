> Imported from Whisper2 @523e838 (MIT); Whisper2 retired. This file is historical design context. The Java implementation in this repository is the product; nothing here is required to build or test Whisper.

# Whisper2 — Executive Summary (research pack, 2026-10-29)

**Product:** a Go desktop app and CLI for modern steganography. Only the payload is encrypted, never the carrier (cover). It supports several carrier types. Repo: https://github.com/jx-zheng/Whisper2 (empty, private).

## Recommended stack (v1)

| Layer | Pick | Why (short) |
|---|---|---|
| Language/toolchain | **Go 1.27.x** (go.mod minimum `go 1.26`) | Current stable releases are go1.27.3 and go1.26.10. Wails v3 needs Go 1.26 or newer. |
| Payload crypto | **ChaCha20-Poly1305 (default) + AES-256-GCM (option)**, chunked STREAM framing in the style of age, keys from **Argon2id → HKDF-SHA-256** | Stdlib plus `golang.org/x/crypto` only (BSD-3). The embedded bytes have **no plaintext magic or header**: they look like random bits. See `02-crypto-design.md`. |
| KDF params | Argon2id t=3, m=64 MiB, p=4, 16-byte salt (RFC 9106 "second recommended"). There is also a "high" profile at t=1, m=2 GiB. | RFC 9106 §4. Tools without a memory-hard KDF get cracked fast (stegseek runs through rockyou.txt against steghide in about 2 s). |
| Stego core | **Content-adaptive embedding: cost function + Syndrome-Trellis Codes (STC)**, binary STC with ±1 changes in the cheaper direction | Has been the state of the art in practice since 2011 (Filler/Judas/Fridrich). Extraction is deterministic and bit-exact, which encrypted payloads require. No Go STC exists, so we write a clean-room port (~400 LOC). |
| Engines (MUST) | ① **PNG/lossless spatial: HILL + STC** ② **JPEG DCT domain: UERD + STC, no recompression** ③ **WAV PCM: 1-D adaptive cost + STC** | All pure Go, with bit-exact roundtrip. Capacity is honest: target ≤0.1 bpp spatial, ≤0.1–0.2 bpnzAC JPEG. |
| Engines (SHOULD) | J-UNIWARD costs, FLAC (via `mewkiz/flac` encoder), progressive-JPEG output, an "LSB-replacement" compatibility mode labelled *insecure* | |
| UI | **Wails v3** (v3.0.0 went stable 2026-10-26, MIT; pin `v3.0.x`) with a TypeScript+Svelte/React frontend | Designer-grade HTML/CSS UI using the native WKWebView on macOS. The fallback is Fyne 2.8 (pure Go) if the webview risk or Node toolchain is unwanted. See `05-ui-toolkit.md`. |
| CLI | `spf13/cobra` (Apache-2.0) or `urfave/cli` (MIT); `charmbracelet/huh` for prompts | |

## What the research says (headline findings)
1. **The deep-learning methods that look SOTA on paper don't work for us in practice.** HiNet and ISN hide *images in images* and recover the secret image *approximately*. After the unavoidable 8-bit rounding, HiNet's recovered-secret PSNR is about 40 dB, which is far from bit-exact (PRIS, arXiv 2309.13620). Ciphertext breaks if a single bit flips. SteganoGAN reports a "4.4 bpp" figure, but that is RS-corrected *estimated* capacity with 87% raw bit accuracy. It also needs PyTorch and fails under any channel noise.
2. **Diffusion-based generative steganography (2024–2026) is promising but not shippable in Go.** CIF (arXiv 2508.00434) reaches 99.02% bit accuracy lossless at 0.0625 bpp on 512×512, with a GPU and a pretrained rectified-flow model. That still requires error-correcting codes and a multi-GB model. It also *generates* an image rather than using the user's own cover.
3. **Classical adaptive embedding is the honest choice.** It ships overnight, it's bit-exact, and it's well studied. But modern CNN steganalysis catches it at higher payloads under lab conditions: SRNet detection error P_E is about 0.12 for non-robust UERD at 0.1 bpnzAC, QF75 (Butora et al., arXiv 2211.04750, Table III). **Low payload is the main lever for security.** Keep the defaults conservative and show a "detectability risk" meter.
4. **The file format can give the game away even when the pixels don't.** Go's `image/png` writes 32 KiB IDAT chunks, which is a fingerprint. Missing EXIF/ICC chunks and recompressing a JPEG are fingerprints too. Engines must preserve the original container structure (see the stenoxide reference design).

## Do NOT build overnight
- Any DL/GAN/INN/diffusion engine, ONNX runtime integration, or GPU dependency.
- Robust-to-recompression ("survives WhatsApp/Slack") JPEG. It's real research (Butora errorless lattice embedding), but it needs the exact target compressor, i.e. libjpeg via cgo. Plan it for v2.
- Video (H.264/HEVC), LLM text steganography (Meteor/Discop need bit-identical model inference on both ends), PDF TJ-kerning, Unicode zero-width, or append-after-EOF "stego". These are fragile or trivially detectable.
- Encrypting or scrambling the carrier itself, custom ciphers, plaintext headers or magic bytes inside the embedded stream, SHA-256(password) as a key.
- Mobile builds, auto-updater, cloud features.

## Files in this pack
`01-literature-survey.md` · `02-crypto-design.md` · `03-engine-shortlist.md` · `04-go-architecture.md` · `05-ui-toolkit.md` · `06-open-source-libs.md` · `07-cloud-agent-handoff.md` (paste-ready) · `sources.json`
