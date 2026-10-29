> Imported from Whisper2 @523e838 (MIT); Whisper2 retired. This file is historical design context. The Java implementation in this repository is the product; nothing here is required to build or test Whisper.

# 04 — Go Architecture

Module path (assumed): `github.com/jx-zheng/Whisper2`

## Package layout

```
Whisper2/
├── go.mod
├── README.md
├── cmd/
│   ├── whisper2/          # CLI entry (cobra)
│   │   └── main.go
│   └── whisper2-ui/       # Desktop entry (Wails); thin wrapper
│       └── main.go
├── internal/
│   ├── crypto/            # Seal/Open, Argon2id, HKDF, STREAM AEAD
│   ├── stego/
│   │   ├── engine.go      # Engine interface, registry
│   │   ├── stc/           # Clean-room STC (binary + ternary later)
│   │   ├── costs/
│   │   │   ├── hill.go
│   │   │   ├── suniward.go
│   │   │   ├── uerd.go
│   │   │   └── audio_adaptive.go
│   │   └── permute/       # Keyed Fisher–Yates visit order
│   ├── carrier/
│   │   ├── pngx/          # PNG decode/encode with envelope fidelity
│   │   ├── jpegx/         # Coefficient-level JPEG I/O
│   │   ├── wavx/          # WAV PCM
│   │   └── flacx/         # Optional FLAC
│   ├── engine/
│   │   ├── spatial/       # HILL/S-UNIWARD + STC → PNG/TIFF
│   │   ├── jpeg/          # UERD/J-UNIWARD + STC
│   │   └── audio/         # WAV/FLAC
│   ├── app/               # Shared use-cases: Hide, Reveal, Inspect
│   │   ├── hide.go
│   │   ├── reveal.go
│   │   └── capacity.go
│   └── ui/                # Wails bindings (no business logic)
├── frontend/              # Wails TS app (Svelte or React)
├── testdata/              # Tiny fixtures committed; large corpus gitignored
├── scripts/
│   └── gen_stc_vectors.py # Offline: produce golden vectors via pySTC for cross-check
└── docs/                  # User docs (later)
```

**Visibility rule:** everything under `internal/` stays internal. If we later need a public Go API, add `pkg/whisper2` that re-exports a stable subset.

## Core interfaces

```go
// internal/stego/engine.go
package stego

type Options struct {
    Rate        float64 // 0 → engine default
    Channel     string  // "auto","Y","R","G","B","mid"
    KeepMeta    bool
    MaxPayload  int
    Paranoid    bool    // forces 0.02 bpp / equivalent
}

type Meta struct {
    Engine      string
    CoverSHA256 string
    StegoSHA256 string
    PayloadLen  int
    Rate        float64
    Changes     int
    Duration    time.Duration
    Warnings    []string
}

type Capacity struct {
    MaxBytes         int
    RecommendedBytes int
    Unit             string
    Rate             float64
    Warnings         []string
}

type Engine interface {
    Name() string
    Accepts(name string, prefix []byte) bool
    Estimate(cover []byte, opt Options) (Capacity, error)
    Embed(cover, payload, seed []byte, opt Options) ([]byte, Meta, error)
    Extract(stego, seed []byte, opt Options) ([]byte, Meta, error)
}

func Register(e Engine) { /* … */ }
func ForFile(name string, prefix []byte) (Engine, error) { /* … */ }
```

```go
// internal/app/hide.go — orchestration used by CLI and UI
func Hide(coverPath, outPath string, message []byte, pass []byte, opt HideOptions) (Meta, error) {
    cover := readFileLimited(coverPath, maxCover)
    eng := stego.ForFile(coverPath, cover[:min(64,len(cover))])
    env, seed, err := crypto.Seal(message, cryptoParams(pass, opt))
    stegoBytes, meta, err := eng.Embed(cover, env.Bytes(), seed, opt.Engine)
    // Embed already verified roundtrip of payload bits
    return meta, atomicWrite(outPath, stegoBytes)
}
```

## STC package notes
- Start with **binary** STC (LSB plane). Height `h=7..10` configurable; default 10.
- Public API: `Embed(coverBits []byte, costs []float64, message []byte, h int) (stegoBits []byte, nChanges int, err error)` and `Extract(stegoBits []byte, messageLen int, h int) ([]byte, error)`.
- Validate against golden vectors generated once with `pystcstego` / stenoxide and stored under `testdata/stc/`. Do **not** link the Binghamton C++ (unclear licence).
- Property: `Extract(Embed(c, costs, m)) == m` for random inputs; wet-cost columns never flip.

## CLI UX (sketch)
```
whisper2 hide   -i cover.png -m message.txt -o stego.png
whisper2 hide   -i cover.jpg -m - -o stego.jpg          # message from stdin
whisper2 reveal -i stego.png -o message.txt
whisper2 inspect -i cover.png                           # capacity + warnings
whisper2 engines                                        # list
```
Flags: `--passphrase-env=VAR`, `--key-file=`, `--profile=default|high`, `--aead=chacha|aesgcm`, `--rate=0.05`, `--paranoid`, `--keep-metadata`, `--engine=spatial|jpeg|audio|auto`, `--json` for scripting.

Never accept `--passphrase=...` on the argv (shell history). Use env, keyring, or interactive prompt (`huh` / terminal).

## UI binding surface
Wails binds only `internal/ui.Service` methods:
- `Inspect(path) (CapacityReport, error)`
- `Hide(req HideRequest) (JobID, error)` — heavy work on a worker goroutine; progress events.
- `Reveal(req RevealRequest) (JobID, error)`
- `Cancel(JobID)`
Passphrases flow through Wails bindings as strings once; wipe on the Go side after Seal/Open. Prefer OS keychain for "remember passphrase" via `zalando/go-keyring` (MIT).

## Testing strategy
| Layer | How |
|---|---|
| Unit | `crypto`, `stc`, each cost function, each carrier parser. Table-driven. |
| Golden | STC vectors; HILL cost map vs conseal/py reference on a 64×64 fixture (tolerance 1e-9 relative). |
| Integration | `Hide`→`Reveal` for every engine × every fixture in `testdata/`. |
| Roundtrip invariant | `Embed` internal check + external test. |
| Fuzz | `ParseEnvelope`, PNG/JPEG/WAV parsers (`testing.F`). |
| Race | `go test -race ./...` in CI; Wails UI callbacks on main thread only. |
| Det | Steganalysis smoke (optional job): call Aletheia / a tiny SRM-like feature score on stego vs cover; gate is informational, not pass/fail. |
| Bench | `BenchmarkSTC_1MP`, `BenchmarkHILL_12MP`, `BenchmarkHidePNG`. Targets: HILL+STC 12 MP < 3 s on mid laptop; JPEG 8 MP < 5 s. |

## Performance notes
- HILL box filters: use separable prefix sums; don't call a generic convolution per pixel.
- STC: allocate the trellis once; process in column blocks; consider `GOMAXPROCS` only for cost-map generation (STC is inherently serial along the trellis).
- Memory: stream WAV; memory-map large PNGs carefully (still need a mutable copy for ±1).
- Don't copy the whole JPEG entropy stream more than once.
- Argon2id dominates short messages; show a progress spinner in the UI.

## Build tags
- `//go:build cgo` for optional libjpeg-turbo robust engine.
- `//go:build wails` if needed to keep CLI builds free of webview deps.
- Release builds: `trimpath`, `-ldflags="-s -w"`, note that stripping makes crash debug harder.

## CI (recommendation)
GitHub Actions: `go test -race ./...`, `go vet`, `staticcheck`, `govulncheck`, build CLI for linux/amd64, darwin/amd64, darwin/arm64, windows/amd64. UI build only on macOS and Windows runners (webview deps).
