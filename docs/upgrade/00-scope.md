# Whisper1 upgrade — 00 Scope (2026-10-29)

**Repo:** https://github.com/jx-zheng/whisper (Java 21, Maven, JavaFX 21.0.13). Inspected at `main` = `cd85cbe` (pushed 2026-10-29 06:23 EDT).
**Donor:** https://github.com/jx-zheng/Whisper2 (Go, MIT, private). Inspected at `main` = `523e838` (pushed 2026-10-29 11:21 EDT).

## Product decision (Kevin, 2026-10-29)
- **Whisper1 (Java/JavaFX) is the only product that survives.** After this upgrade ships, the Whisper2 repo will be **deleted**. Kevin prefers the polished Whisper1 JavaFX UI.
- The upgrade must therefore be **self-contained**: no runtime, build, test, or doc dependency on Whisper2. Everything worth keeping from Whisper2 (design docs, algorithms, golden test vectors) is **ported or copied into the whisper repo** in this PR.
- The UI stays JavaFX. Extend the existing `EmbedPane` / `ExtractPane` / `AnalyzePane` + `Forms` / `ImageCard` / `StatusBar` patterns. **No Wails, no web UI.**
- A backup was made on the box before the deletion: `/workspace/whisper1-upgrade/archive/Whisper2-523e838.bundle` (full `git bundle --all`, 153 KB), plus `archive/whisper2-docs-research/` (00–08 + sources.json). Restore with `git clone Whisper2-523e838.bundle`.

## Sequencing (do not duplicate in-flight work)
An **in-flight Whisper1 PR** (another agent) already covers:
1. optional / split keys (a stego key separate from the encryption key; encryption optional),
2. dark-only theme (removes the Light/Dark toggle),
3. window-resize polish. Kevin, msg pt426u: "make sure that Whisper One doesn't have any weird behavior when we resize the window … things still look good after resizing. To reasonable limits".

At 11:35 EDT that PR was **not visible on GitHub**: no open PR and no unmerged branch, only PRs #1–#5, all merged. The upgrade PR **starts after that PR merges** and rebases on it. It must:
- reuse that PR's key model and widgets, not invent a second key UI,
- add no theme code (dark-only is settled),
- follow that PR's responsive layout rules for every new widget (payload editors, viewers, meter). It adds no new resize logic beyond making new nodes grow and shrink correctly, plus a regression check.

## Hard minimums (Kevin; non-negotiable acceptance criteria)
| # | Requirement | How this pack meets it |
|---|---|---|
| H1 | **≥2 modern steganography schemes** besides legacy Zhang–Tang | **HILL + STC** (spatial; PNG/BMP in, PNG out) and **UERD + STC** (JPEG DCT domain; JPEG in, JPEG out, no recompression). Both are content-adaptive with syndrome-trellis codes, the same classes Whisper2 ships. If JPEG coefficient I/O blocks, the documented fallback is **S-UNIWARD + STC** (spatial), which still gives 2 modern schemes. Zhang–Tang stays as "Legacy / high-capacity". |
| H2 | **≥2 modern AEADs** | **ChaCha20-Poly1305** (default) and **AES-256-GCM**, both JDK built-ins. Keys come from **Argon2id → HKDF-SHA-256** with an age-style 64 KiB STREAM. The old AES-128-GCM/PBKDF2 path becomes **read-only legacy**. |
| H3 | **Hash verification / checksum** of extracted contents | Two layers. Primary authenticity is the AEAD tag (or HMAC-SHA-256 when encryption is off). The payload frame also carries **SHA-256(content) inside the authenticated plaintext**, re-checked after decrypt and decompress. The UI shows **"Verified · SHA-256 1a2b…9f"** or **"Tampered / wrong key"**, and the sender sees the same fingerprint at embed time. Failure modes: `01-crypto-port.md` §6. |
| H4 | **Performance / quality pass** | Memory-efficient and fast. Mature OSS only where it pays off: BouncyCastle for Argon2id + HKDF; the JDK for AEAD, ChaCha20, DEFLATE, SHA-256. Schemes, ciphers, and payload kinds are pluggable modules. Concrete budgets and benchmarks are in 01, 02, and 04. |
| H5 | **Payload UX**: embed **Text \| File \| Image**. Extract shows an image as an image when detected, otherwise text, otherwise a savable file. The three kinds look visibly different. | `03-payload-ux.md` |
| H6 | Best UX/UI + updated README | 03 + Phase 8 of 04 |

## In scope
- Crypto port (01), scheme port (02), payload frame + UX (03), CLI parity for the new flags, a README and `docs/` rewrite, a CI workflow (whisper has no `.github/` today), and committing the research docs into `docs/research/` (because Whisper2 is going away).
- Read compatibility with stego images made by Whisper1 0.9.0 (Zhang–Tang, plus optional AES-128-GCM/PBKDF2).

## Out of scope (this PR)
- WAV/FLAC audio. Whisper2 has a WAV engine, but Whisper1 is an image tool; audio can be a later module.
- Robust-to-recompression JPEG; any DL/GAN/diffusion stego; video; PDF.
- Wire compatibility with Whisper2 files. Whisper2 is being deleted, so Whisper1 uses its **own domain labels** (`"whisper v2 …"`). The algorithms are ported; identical byte formats are not promised.
- Theme toggle, resize logic, and the split-key model (all in the in-flight PR).
- Public-key / recipient encryption.

## Key risks
1. **JPEG coefficient I/O in Java.** ImageIO has no quantized-DCT access. Port Whisper2 `jpegx` (≈860 LOC Go, baseline only) to pure Java and spike it first. The fallback is S-UNIWARD-STC, so H1 still holds.
2. **Capacity shock.** At the safe rate (0.10 bpp), a 12 MP photo holds about 150 KB and a 1 MP image only about 12 KB. Zhang–Tang held ~3 bpp, capped at 64 KB. File and Image modes must be honest about this, offer "fit image to capacity", and point to Legacy/high-capacity with a detectability warning.
3. **Argon2id in pure Java** (BouncyCastle) is slower than Go. Budget ≤1.5 s per derivation on a mid laptop at 64 MiB and benchmark it. Reveal may try up to 3 rate presets, so cache the derivation per salt.
4. **Untrusted extracted images and files** (decompression bombs, path traversal in names). See 03 §5.

## Deliverables
`00-scope.md` · `01-crypto-port.md` · `02-schemes-port.md` · `03-payload-ux.md` · `04-cloud-agent-handoff.md` · `SOURCES.md` · `../archive/` (Whisper2 bundle + research copy)
