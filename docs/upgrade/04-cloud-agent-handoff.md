# 04 — CloudAgent handoff (phased, paste-ready)

## Notes for the launcher (parent agent / Kevin), read first
- **Precondition.** The in-flight Whisper1 PR (optional/split keys + dark-only + resize) must be **merged to `main`** before this starts. This PR rebases on it and must not duplicate it.
- **Attach the pack.** A CloudAgent cannot see the box. Attach or paste these four files with the prompt below: `00-scope.md`, `01-crypto-port.md`, `02-schemes-port.md`, `03-payload-ux.md`, all from `/workspace/whisper1-upgrade/executor-pack/`. In Phase 0 the agent commits them to `docs/upgrade/`.
- **Keep Whisper2 alive until this merges.** The agent reads it during Phase 0 to port code and generate golden vectors. Delete Whisper2 only **after** this PR merges and the vectors and docs live in `whisper`. A backup is at `/workspace/whisper1-upgrade/archive/Whisper2-523e838.bundle`.
- **What the agent needs:**
  - write access to `jx-zheng/whisper`
  - read access to `jx-zheng/Whisper2` (private)
  - JDK 21 + Maven
  - Go ≥1.26 (Phase 0 golden vectors only)
  - Xvfb or headless Monocle for the GUI layout test
- **If budget is tight, do phases in this order:**
  1. Phases 0–4: crypto + HILL. That meets H2 and H3, plus half of H1.
  2. Phase 5: JPEG, the second scheme.
  3. Phases 6–7: CLI + UI.
  4. Phases 8–9.
- **After the agent pushes:** run the **adversarial-review** skill before the PR is marked ready. Don't merge without Kevin.

---

```
# Task: Upgrade Whisper (Java/JavaFX) to modern adaptive steganography, AEAD crypto,
# verified payloads, and Text | File | Image payload UX

Repo: https://github.com/jx-zheng/whisper (Java 21, Maven, JavaFX 21.0.13; CLI + desktop app).
Donor (read-only, will be DELETED after this ships): https://github.com/jx-zheng/Whisper2 @ 523e838 (Go, MIT, same author).
Whisper (Java) is the ONLY surviving product. The result must be fully self-contained:
no build/runtime/test/doc dependency on Whisper2. Keep the JavaFX UI (extend it). No Wails/web UI.

Attached design docs are NORMATIVE (commit them in Phase 0):
  00-scope.md, 01-crypto-port.md, 02-schemes-port.md, 03-payload-ux.md
If anything below conflicts with them, the docs win; note the conflict in the PR.

## Preconditions / do-not-duplicate
- Branch from main AFTER the in-flight PR "optional/split keys + dark-only + resize" has merged.
  Verify: main has no Light/Dark toggle and has the split-key UI. If not, STOP and report.
- Reuse that PR's key model/widgets and responsive rules. Add NO theme code and NO new resize logic,
  only make new widgets grow/shrink properly + one layout regression test.
- Branch: cursor/modern-stego-upgrade. One PR, commits per phase, main stays green.

## Hard requirements (acceptance criteria; all mandatory)
H1 ≥2 modern stego schemes besides Zhang–Tang: `hill-stc` (HILL + binary STC, PNG/BMP→PNG)
   and `uerd-stc` (UERD + STC on nonzero luma AC, JPEG→JPEG, no recompression).
   If JPEG coefficient I/O fails its gate (Phase 5), implement `suniward-stc` instead so ≥2 still holds,
   and explain in the PR.
H2 ≥2 AEADs: ChaCha20-Poly1305 (default) and AES-256-GCM (JDK built-ins), keys via Argon2id
   (BouncyCastle bcprov-jdk18on 1.86) → HKDF-SHA-256, age-style 64 KiB STREAM. Legacy AES-128-GCM/PBKDF2 = decrypt-only.
H3 Verification: AEAD tag (or HMAC-SHA-256 in integrity-only mode) as primary + SHA-256(content) inside the
   authenticated payload frame; UI/CLI show "Verified · SHA-256 …" or a specific failure (01 §6, F1–F14).
   Never return or display unauthenticated or hash-mismatched content.
H4 Performance/quality: memory-efficient, fast, modular. Pluggable StegoScheme / AeadSuite / PayloadKind
   (+ JavaFX PayloadEditor/PayloadViewer per kind). Budgets in 01 §7 and 02 §5 measured and reported.
H5 Payload UX: embed Text | File | Image (visibly different editors); extract shows text / file card + Save /
   image preview (auto-detected by frame kind, then magic sniff). Fit-image-to-capacity. 03 in full.
H6 README rewritten (03 §8). No "undetectable" claims.

## Non-negotiables
- Encrypt the PAYLOAD only; never encrypt/scramble the cover. Embedded bits are bit-exact recoverable.
- No plaintext magic/banner in the embedded stream. No clear hash outside the AEAD.
- No custom crypto, no AES-CBC/ECB, no java.util.Random for salts/nonces/permutations, no passphrase logging.
- No Binghamton STC code (clean-room from the TIFS 2011 paper; port Whisper2's own clean-room Go).
- No GPL/AGPL in the shipped JAR (JMH test-scope only is OK). Only new runtime dep: bcprov-jdk18on.
  Justify anything else in the PR body.
- No network calls, telemetry, or auto-update.
- Every scheme self-extracts and compares before writing; output written atomically (tmp + ATOMIC_MOVE).

## Phases (each checkpoint: `mvn -B verify` green, commit, continue)

### Phase 0: Vendor + golden vectors (Whisper2 still exists)
- Commit attached docs to docs/upgrade/; copy Whisper2 docs/research/ (00–08 + sources.json) to docs/research/
  with a header "imported from Whisper2 @523e838 (MIT); Whisper2 retired".
- Clone Whisper2; write a tiny Go program in tools/golden-gen/ (README; NOT part of the Maven build) dumping JSON vectors:
  permute.Order(n=1000, seed=00..1f); stc.Embed/Extract fixed cases h∈{7,10}; HILL costs on a 64×64 crop of
  testdata/png/photo.png; UERD costs on testdata/jpeg/noise.jpg; frame publicLayout indices n=100000 at 0.10/0.05/0.02.
  Save under src/test/resources/golden/.
- Copy Whisper2 testdata/{png,jpeg} into src/test/resources/fixtures/; add 1 QF75 4:2:0 photo JPEG + 1 24-bit BMP (<2 MB total).
- With CURRENT main (pre-change) generate legacy fixtures: zhang-tang plain + zhang-tang -e, known key/message.
- Add .github/workflows/ci.yml: temurin 21, `mvn -B verify` headless (Monocle or xvfb-run for FX tests).
✅ CI green, no behaviour change.

### Phase 1: Architecture refactor (no behaviour change)
- Packages per 02 §4: StegoScheme + SchemeRegistry, AeadSuite + CipherRegistry, ops facade (HideRequest, RevealResult,
  Progress), carrier/Carrier + CarrierCache. ZhangTangScheme behind StegoScheme unchanged.
- StegoOperations.schemes()/ciphers() come from registries (GUI combos auto-populate via Forms.choice).
- Remove the double decode: EmbedPane preview + embed share CarrierCache (02 §5).
✅ Existing tests green; legacy fixtures extract.

### Phase 2: Crypto (01 in full)
- Argon2idKdf (BC), Hkdf (BC HKDFBytesGenerator), Stream, ChaCha20Poly1305Suite, AesGcmSuite, IntegrityOnlySuite,
  Envelope v2 (01 §4.1), PayloadFrame with SHA-256 (01 §4.2), DEFLATE auto, KeyCache, single/split key schedule (01 §3)
  wired to the in-flight PR's key model; LegacyAes128GcmPbkdf2 decrypt-only.
- Preallocated buffers; single-pass streaming read+hash (01 §7).
✅ 01 §8 tests incl. RFC vectors, bit-flip sweep, frame fuzz, wipe checks; Argon2id bench reported.

### Phase 3: STC + permutation + framing
- Port stc (segmented Viterbi, 8192-col checkpoints, bit-packed long[] backpointers, float[] costs), KeyedPermutation
  (JDK ChaCha20 keystream, u32 LE, rejection sampling), ParityFraming (public salt w=ceil(1/cap), keyed 4-byte prefix
  span 256, payload), RatePreset {STANDARD .10, LOW .05, PARANOID .02}, Capacity/UserBudget.
✅ Bit-for-bit match with Go golden vectors; property tests; STC 1M cols ≤1.5 s.

### Phase 4: hill-stc + PngCodec (+ BMP input, cover converter)
- PngCodec port of pngx (8 KiB IDAT, chunk whitelist, adaptive filters), BmpReader, CoverConverter. HillCost with
  separable prefix sums + float[] ping-pong scratch. HillStcScheme on green/grey plane, alpha untouched; reveal tries
  3 presets with KeyCache.
✅ Round trips (02 §6); PNG fidelity; χ²/RS informational test; 12 MP in -Xmx512m; timings reported.

### Phase 5: uerd-stc + JpegCoefficients (GATE)
- Port jpegx: baseline Huffman, restart markers, byte-identical non-scan segments; reject progressive/arithmetic/12-bit.
- UerdCost; vector = nonzero luma AC, |c|=1 wet, DC wet, shrink toward zero; .jpg output allowed only for this scheme.
- GATE: all JPEG fixtures + 50 seeded payloads round-trip; only luma AC |c|≥2 change by ±1; output decodes in ImageIO.
  If not achievable with reasonable effort: park JPEG on a spike branch, ship `suniward-stc` (02 §1), document it.
✅ Gate passed (or fallback shipped); timings reported.

### Phase 6: Ops facade + CLI (03 §6)
- --text-file/--file/--image (+fit), --scheme, --cipher, --preset, --kdf, --out-dir, inspect --json; exit codes
  0 verified / 2 auth-integrity / 3 nothing found / 1 usage; "Verified · SHA-256" on stderr.
✅ CliDriverTest covers kind × scheme × cipher + legacy.

### Phase 7: JavaFX UI (03 in full)
- Segmented Text|File|Image editors (distinct looks), CapacityMeter, Fit-to-capacity, Protection card, fingerprint row,
  extended Open-in-Extract; Extract viewers (Text / File card / Image preview + zoom), VerifiedBadge (F1–F14 wording),
  sniff-based image detection, safety rules (03 §5), progress stages + Cancel. CSS only in whisper.css (dark-only).
- Accessibility per 03 §7; headless layout regression at 780×560, 1080×760, 1600×1000.
✅ GUI tests green; screenshots of 3 editors + 3 viewers in docs/img/.

### Phase 8: Performance/quality pass + docs
- Run -Pbench; table of measured vs target (01 §7, 02 §5) in PR body; fix regressions.
- New classes ≤400 LOC, no FX imports outside gui/, javadoc on public types, warning-free build.
- README (03 §8); docs/ARCHITECTURE.md (package diagram + one-page "add a scheme / cipher / payload kind" guides);
  version 1.0.0; CHANGELOG ("Whisper2 merged into Whisper; legacy 0.9 images still readable").
✅ CI green.

### Phase 9: PR
- Title: "Modern adaptive stego (HILL/UERD + STC), AEAD crypto, verified Text/File/Image payloads".
- Body: H1–H6 checklist with evidence, bcprov justification, ported Whisper2 files + commit, limitations,
  "Whisper2 can be deleted after merge: docs + golden vectors now live here." Do NOT merge; request review.

## Success criteria (check all in the PR body)
- [ ] hill-stc and uerd-stc (or suniward-stc fallback) round-trip on all fixtures; zhang-tang + legacy 0.9 still work
- [ ] ChaCha20-Poly1305 + AES-256-GCM selectable in GUI and CLI; Argon2id default; RFC vectors pass
- [ ] Every single-bit envelope flip fails; SHA-256 shown on success; F1–F14 messages implemented
- [ ] Text, File (any type), Image embed; extract auto-shows text / file card / image preview
- [ ] Measured: Argon2id ≤1.5 s, STC 1M ≤1.5 s, 12 MP hill-stc hide ≤6 s excl. KDF, 12 MP within -Xmx512m
- [ ] Nothing requires Whisper2 to build, test, or understand the code
- [ ] No theme/resize duplication; layout regression test passes
- [ ] README + ARCHITECTURE updated; CI green
```
