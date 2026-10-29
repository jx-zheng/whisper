> Imported from Whisper2 @523e838 (MIT); Whisper2 retired. This file is historical design context. The Java implementation in this repository is the product; nothing here is required to build or test Whisper.

# 07 — Cloud Agent Handoff Prompt (paste-ready)

Copy everything inside the fence below into a new Cursor Cloud Agent task. It is self-contained. The research pack lives in the repo (or attach the `whisper2-research/` directory). Do not assume the agent has prior chat context.

---

```
# Task: Scaffold and implement Whisper2 v1 (Go steganography desktop + CLI)

You are a coding agent. Implement Whisper2 against an empty/private GitHub repo
https://github.com/jx-zheng/Whisper2. Kevin wants a working v1: payload-only
encryption + classical adaptive steganography engines + CLI + Wails v3 UI.
Deep-learning engines are explicitly out of scope.

## Product rules (non-negotiable)
1. Encrypt the PAYLOAD only. NEVER encrypt, scramble, or noise the carrier/cover.
2. Embedded ciphertext must be bit-exact recoverable. No approximate / PSNR-based recovery.
3. No plaintext magic string inside the embedded stream (no "WHISPER2" banner).
4. Prefer MIT/BSD/Apache dependencies. No GPL/AGPL in the main binary.
5. Do NOT implement: SteganoGAN, HiNet, ISN, PRIS, diffusion/LLM stego, video,
   robust-to-recompression JPEG, PDF stego, encrypting the cover, custom ciphers.
6. Do NOT copy code from the Binghamton STC toolbox (unclear licence). Clean-room
   STC from the Filler/Judas/Fridrich TIFS 2011 paper description.

## Authoritative design docs
If present in the workspace, treat these as normative:
- whisper2-research/00-executive-summary.md
- whisper2-research/02-crypto-design.md
- whisper2-research/03-engine-shortlist.md
- whisper2-research/04-go-architecture.md
- whisper2-research/05-ui-toolkit.md
- whisper2-research/06-open-source-libs.md
If missing, follow the condensed specs below.

## Stack
- Go 1.26+ (prefer current stable 1.27.x). Module: github.com/jx-zheng/Whisper2
- Crypto: Argon2id → HKDF-SHA-256 → ChaCha20-Poly1305 (default) or AES-256-GCM;
  age-style 64 KiB STREAM chunking; optional zstd before AEAD.
- Stego: HILL + binary STC (PNG), UERD + binary STC (JPEG), adaptive PCM + STC (WAV).
- CLI: cobra. UI: Wails v3 (pin v3.0.x). Pure Go core under internal/.

## Condensed crypto envelope (embedded bytes)
offset 0:  flags (version=1, compression bit, AEAD id)
       1:  kdf profile id
       2:  16-byte salt
      18:  16-byte file nonce
      34:  uint64 BE plaintext length (AAD)
      42:  STREAM ciphertext chunks (64 KiB; nonce = 7 zero || u32 seq || lastFlag)
Argon2id default: t=3, m=64MiB, p=4 (RFC 9106 second recommended).
HKDF info labels: "whisper2 v1 aead", "whisper2 v1 stego", "whisper2 v1 payload".
Also return a 32-byte stegoSeed from HKDF for the visit-order PRNG.

## Condensed package layout
cmd/whisper2, cmd/whisper2-ui,
internal/crypto, internal/stego/{engine.go,stc,costs,permute},
internal/carrier/{pngx,jpegx,wavx},
internal/engine/{spatial,jpeg,audio},
internal/app, internal/ui, frontend/

Engine interface:
  Name, Accepts, Estimate, Embed(cover,payload,seed,opt), Extract(stego,seed,opt)
Embed MUST self-verify by extracting and comparing payload bytes before returning.

## Ordered build plan (complete in order; stop at a green checkpoint each time)

### Phase 0 — Repo skeleton (checkpoint: `go test ./...` passes empty)
- go mod init, README, LICENSE (MIT unless Kevin says otherwise), .gitignore
- internal package stubs, cmd/whisper2 stub that prints version
- Copy or keep whisper2-research/ docs under /docs/research/

### Phase 1 — Crypto (checkpoint: full unit tests + bitflip/wrong-pass failures)
- Implement internal/crypto per 02-crypto-design.md
- Tests: roundtrip, wrong passphrase, truncated stream, bitflip each header field,
  Argon2id profile plumbing, both AEADs, compress on/off
- Never log secrets; wipe passphrase buffers after use

### Phase 2 — STC (checkpoint: golden vectors + property tests)
- Clean-room binary STC (h default 10)
- Generate golden vectors offline (document how via pySTC) OR hand-craft small ones
- Property: Extract(Embed(c,costs,m))==m; wet costs never flipped
- Benchmark on 1e6 cover elements

### Phase 3 — Spatial engine PNG (checkpoint: Hide↔Reveal on fixtures)
- HILL costs (verify against a known 64×64 fixture within 1e-9 relative if reference available)
- Keyed Fisher–Yates visit order from stegoSeed
- PNG writer with envelope fidelity: whitelist ancillary chunks; default IDAT 8 KiB
  (NOT stdlib 32 KiB); strip EXIF/text by default
- CLI: whisper2 hide/reveal/inspect for PNG
- Testdata: at least 5 PNGs (photo, flat, tiny, large, greyscale)

### Phase 4 — JPEG engine (checkpoint: Hide↔Reveal, no re-encode from pixels)
- Coefficient-level JPEG I/O (prefer forking image/jpeg decoder + writing scans;
  if evaluating github.com/0verkilll/jpeg, AUDIT first — brand new, 0 stars)
- UERD costs + STC on Y (optional Cb/Cr later)
- Preserve quant tables; reject progressive/arithmetic/12-bit with clear errors
- Default rate ≤0.1 bpnzAC; warn otherwise

### Phase 5 — WAV engine (checkpoint: Hide↔Reveal)
- PCM s16/s24; adaptive 1-D cost; preserve non-data chunks
- Reject float WAV

### Phase 6 — CLI polish (checkpoint: --help, JSON output, passphrase via env/tty only)
- cobra commands; --json; --paranoid; --profile; --aead; --rate
- REFUSE --passphrase on argv (use env / prompt / key-file)

### Phase 7 — Wails v3 UI (checkpoint: desktop hide/reveal on macOS or Linux)
- Pin wails v3.0.x; TypeScript frontend (Svelte or React template)
- Screens: drop zone, inspect/capacity meter, hide, reveal, settings
- Bind only internal/app use-cases; passphrase via secure input; no secrets in logs
- Progress events for Argon2id + embed

### Phase 8 — Hardening (checkpoint: CI green)
- go test -race ./..., vet, staticcheck, govulncheck
- Fuzz parsers and envelope
- go-licenses report; document third_party
- Cross-build CLI: linux/amd64, darwin/arm64, windows/amd64

## Success criteria
- [ ] whisper2 hide/reveal roundtrips a random 10 KB payload through PNG, JPEG, and WAV fixtures
- [ ] Wrong passphrase always errors; any single-bit flip in stego payload region fails auth
- [ ] Cover is never encrypted; stego opens in Preview/Chrome/ffplay as a normal media file
- [ ] Default spatial rate ≤0.1 bpp with UI/CLI warnings above that; hard fail above 0.2 bpp
- [ ] No DL/ONNX deps in go.mod
- [ ] README documents build, usage, threat model summary, and "what this does not protect against"
- [ ] All tests pass with -race

## Do-not-do list (reject PRs / stop if drifting)
- Do not add PyTorch/ONNX/SteganoGAN/HiNet/diffusion
- Do not encrypt the carrier
- Do not invent ciphers or skip the Poly1305/GCM tag
- Do not use math/rand for salts/nonces
- Do not vendor Binghamton C++ STC
- Do not claim "undetectable" in README; say "adaptive embedding at low rates reduces detectability"
- Do not implement Slack/WhatsApp-robust JPEG yet (v2)
- Do not add analytics, network calls, or auto-update in v1
- Do not commit large corpuses or secrets

## Working style
- Small commits per phase; keep main buildable
- Prefer stdlib; justify every third-party module in the PR description
- When JPEG DCT I/O is harder than expected, ship PNG+WAV first and leave JPEG behind a TODO with a spike branch — do not block the whole v1 on JPEG
- Ask for human review before adding cgo
```

---

## Notes for the human launching the agent
- Attach or ensure `whisper2-research/` is in the workspace.
- Budget: Phases 0–3 are the highest value if usage limits are tight; 4–5 next; UI last if needed.
- Grant the agent write access to the Whisper2 repo and a machine with Go 1.26+ (and Node if UI is in scope).
