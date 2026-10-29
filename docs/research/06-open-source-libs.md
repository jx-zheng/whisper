> Imported from Whisper2 @523e838 (MIT); Whisper2 retired. This file is historical design context. The Java implementation in this repository is the product; nothing here is required to build or test Whisper.

# 06 — Open-Source Libraries to Reuse

Preference order: **MIT / BSD / Apache-2.0**. Avoid GPL/AGPL in the core binary unless isolated behind a clearly marked optional tool. Licences and activity checked 2026-10-29 via GitHub API / pkg.go.dev unless noted.

## Crypto & memory

| Module | Licence | Use | Notes |
|---|---|---|---|
| `golang.org/x/crypto` (argon2, chacha20poly1305, hkdf legacy) | BSD-3 | KDF + AEAD | Current module v0.58.0 on pkg.go.dev |
| `crypto/hkdf`, `crypto/aes`, `crypto/cipher`, `crypto/rand` | Go BSD-3 | Stdlib | Prefer stdlib HKDF (added with Go 1.24+) |
| `github.com/klauspost/compress` (`zstd`) | Apache-2.0 / MIT / BSD | Optional compression | Actively maintained |
| `github.com/awnumar/memguard` | Apache-2.0 | Locked, wipeable buffers | Optional; also consider `runtime/secret` (Go 1.26 experiment) |
| `filippo.io/age` / `github.com/FiloSottile/age` | BSD-3 | **Reference only** for STREAM framing | Don't take a hard dependency; reimplement the small STREAM wrapper |

## Stego core (implement / port; don't vendor unclear code)

| Source | Licence | Use |
|---|---|---|
| Filler/Judas/Fridrich STC paper + description | IEEE paper (copyrighted text) | Spec for clean-room Go STC |
| Binghamton STC toolbox C++/Matlab | **Unclear / not clearly OSS** | Read for understanding only; **do not copy** |
| `github.com/daniellerch/pySTC` | MIT | Generate golden vectors; CI cross-check |
| `github.com/stenoxide/stenoxide` (+ `stenoxide-core` crate) | Apache-2.0 | Architecture reference (HILL+STC+Argon2id+XChaCha+envelope fidelity). Rust — don't link; do learn from. |
| `github.com/uibk-uncover/conseal` | MPL-2.0 | Cost-map oracles for tests (Python). MPL is fine for a **dev-only** tool. |
| `github.com/daniellerch/aletheia` | MIT | Optional steganalysis smoke tests |

## Existing Go stego (use as reference or LSB fallback only)

| Module | Licence | Verdict |
|---|---|---|
| `github.com/auyer/steganography` | MIT | Simple LSB; good for "insecure" mode / tests |
| `github.com/DimitarPetrov/stegify` | MIT | LSB multi-carrier CLI; reference only |
| `lukechampine.com/jsteg` | MIT | JPEG LSB via forked decoder; **re-encodes from image.Image** — pattern useful, API not |

## Image / audio / PDF I/O

| Module | Licence | Use | Caution |
|---|---|---|---|
| `image`, `image/png`, `image/jpeg`, `golang.org/x/image` | BSD-3 | Baseline decode | PNG writer uses 32 KiB IDATs — replace for fidelity |
| `github.com/chai2010/webp` | BSD-3 | Lossless WebP | Check encode path supports lossless roundtrip |
| `github.com/disintegration/imaging` | MIT | Resize / colour for inspect previews | Last push 2023; still widely used |
| `github.com/0verkilll/jpeg` | MIT | Possible DCT-level JPEG | **0★, brand new (2026-06)** — audit before shipping |
| `github.com/0verkilll/f5` | MIT | F5 reference | Same author; research only (F5 not for v1) |
| cgo `libjpeg-turbo` | IJG/BSD-ish | Exact compressor for v2 robust mode | Build-tag isolated |
| `github.com/go-audio/wav` | Apache-2.0 | WAV | **Archived 2024-10** — prefer a thin in-tree RIFF parser |
| `github.com/mewkiz/flac` | Unlicense | FLAC encode/decode | Encoder API present |
| `github.com/pdfcpu/pdfcpu` | Apache-2.0 | PDF parse/write for v2 TJ experiments | |
| `github.com/gen2brain/go-fitz` | **AGPL-3.0** | MuPDF bindings | **Don't link into core** |

## CLI / UI / UX

| Module | Licence | Use |
|---|---|---|
| `github.com/wailsapp/wails/v3` | MIT | Desktop UI (recommended) |
| `github.com/fyne-io/fyne/v2` | BSD-3 | Fallback pure-Go UI |
| `github.com/spf13/cobra` | Apache-2.0 | CLI commands |
| `github.com/urfave/cli/v3` | MIT | Alternative CLI |
| `github.com/charmbracelet/huh` | MIT | Interactive prompts |
| `github.com/charmbracelet/bubbletea` | MIT | Optional TUI dashboard |
| `github.com/zalando/go-keyring` | MIT | OS keychain |
| `github.com/stretchr/testify` | MIT | Tests |

## ONNX / DL (explicitly deferred)

| Module | Licence | Notes |
|---|---|---|
| `github.com/yalue/onnxruntime_go` | MIT | Only if a future optional plugin needs ONNX; not v1 |

## Licence hygiene checklist for the Cloud Agent
1. Run `go-licenses report ./...` before first release.
2. No GPL/AGPL in the main binary.
3. Vendor nothing from Binghamton toolbox.
4. Keep a `NOTICE` for Apache deps.
5. Document third-party licences in `docs/third_party.md`.
