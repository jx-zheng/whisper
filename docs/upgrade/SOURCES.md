# SOURCES — Whisper1 upgrade pack (executor-pack, 2026-10-29)

Status key:
- **inspected**: read via `gh` or a clone on the box this session.
- **fetched**: opened this session.
- **search-verified**: confirmed through web search results this session.
- **inherited**: comes from `/workspace/whisper2-research/sources.json`, which was fetched in the earlier session and not re-fetched.

## Repositories
These were inspected via `gh` and cloned only on the box, at `/workspace/w1u-src/`. Nothing was cloned to user machines.

| What | Ref | Used in |
|---|---|---|
| jx-zheng/whisper (Java) | `main` @ `cd85cbe` "Add JavaFX desktop GUI… (#5)". PRs #1–#5 are all merged. As of 11:35 EDT there was no open PR or branch for the in-flight keys/dark/resize work. | 00–04 |
| — key files | `schemes/{AbstractScheme,ZhangTangScheme}.java`, `crypto/KeyedPrng.java`, `encryption/AesCipher.java`, `ops/{StegoOperations,PayloadPreview}.java`, `media/ImageFormats.java`, `gui/{WhisperApp,EmbedPane,ExtractPane}.java`, `pom.xml`, `README.md` | 01–03 |
| jx-zheng/Whisper2 (Go, MIT, private) | `main` @ `523e838` "Fix desktop tabs staying on the Hide form (#2)". v1 was merged in #1. | 01, 02, 04 |
| — key files | `internal/crypto/{crypto,stream,compress}.go`, `internal/stego/{frame,rate,engine}.go`, `internal/stego/stc/stc.go`, `internal/stego/permute/permute.go`, `internal/stego/costs/{hill,uerd}.go`, `internal/engine/{spatial,jpeg}/*.go`, `internal/carrier/{pngx,jpegx}/*.go`, `internal/ui/service.go`, `frontend/index.html`, `docs/research/08-stego-framing.md`, `go.mod`, `.github/workflows/ci.yml` | 01, 02 |
| Whisper2 backup | `/workspace/whisper1-upgrade/archive/Whisper2-523e838.bundle` and `archive/whisper2-docs-research/` | 00, 04 |

## Prior research pack
- Read in full: `/workspace/whisper2-research/00-executive-summary.md`, `02-crypto-design.md`, `03-engine-shortlist.md`, `04-go-architecture.md`, `05-ui-toolkit.md`, `07-cloud-agent-handoff.md`, `sources.json`.
- Not read: `01-literature-survey.md` and `06-open-source-libs.md`. Their findings are summarized in 00.

## Java platform and libraries (this session)
| Fact | Source | Status |
|---|---|---|
| SunJCE has had ChaCha20-Poly1305 since JDK 11 (`Cipher.getInstance("ChaCha20-Poly1305")`, 12-byte nonce via `IvParameterSpec`) | https://openjdk.org/jeps/329 | search-verified |
| `javax.crypto.KDF` with HKDF-SHA256 is final only in JDK 25 (JEP 510; preview in 24), so the Java 21 baseline uses BouncyCastle HKDF | https://openjdk.org/jeps/510 · https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/KDF.html | search-verified |
| BouncyCastle `bcprov-jdk18on` latest is **1.86** (Maven Central lastUpdated 2026-09-11) | https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/maven-metadata.xml | fetched |
| BC has `Argon2BytesGenerator` and `Argon2Parameters.ARGON2_id`; BC 1.86 reproduces the RFC 9106 §5.3 vector | https://github.com/bcgit/bc-java/blob/main/core/src/main/java/org/bouncycastle/crypto/generators/Argon2BytesGenerator.java · https://downloads.bouncycastle.org/java/docs/bcprov-jdk18on-javadoc/org/bouncycastle/crypto/params/Argon2Parameters.html · https://mojoauth.com/security-guides/argon2-in-java | search-verified |
| aircompressor-v3 3.14 (pure-Java zstd) targets **JDK 25** and is Apache-2.0, so it is not used on Java 21. JDK DEFLATE is used instead. | https://github.com/airlift/aircompressor (README) · https://repo1.maven.org/maven2/io/airlift/aircompressor-v3/3.14/aircompressor-v3-3.14.pom | fetched |
| zstd-jni latest is 1.5.7-25 (JNI; not chosen) | https://repo1.maven.org/maven2/com/github/luben/zstd-jni/maven-metadata.xml | fetched |

## Standards and papers
All of these are inherited from the Whisper2 research (ids are from `sources.json`).
- RFC 9106 Argon2 (§4 parameters, §5.3 vector), RFC 8439 ChaCha20-Poly1305, RFC 5869 HKDF, NIST SP 800-38D GCM, and the C2SP age STREAM construction. Design is in `02-crypto-design.md`.
- Filler, Judas, Fridrich, *Minimizing Additive Distortion in Steganography Using Syndrome-Trellis Codes*, IEEE TIFS 2011: https://dde.binghamton.edu/filler/pdf/Fill10tifs-stc.pdf (`stc-paper-pdf`).
- Li et al., *A new cost function for spatial image steganography (HILL)*, ICIP 2014: https://doi.org/10.1109/icip.2014.7025854 (`hill-icip`).
- Holub, Fridrich, Denemark, UNIWARD, EURASIP 2014: https://link.springer.com/article/10.1186/1687-417X-2014-1 (`uniward-springer`).
- Denemark et al., S-UNIWARD σ: https://doi.org/10.1117/12.2044803 (`suniward-streaking`).
- UERD as implemented in Whisper2 `costs/uerd.go`, and the Butora et al. detectability figures (arXiv 2211.04750), via `01-literature-survey.md`.
- Zhang & Tang, ICMLC 2007: https://ieeexplore.ieee.org/document/4370824 (cited in the whisper README).

## Instructions folded in
- The original brief: port the schemes and crypto, keep the UI extensible, support Text/File/Image payloads, update the README, then hand off to a CloudAgent.
- Steering 1: hard minimums of ≥2 schemes, ≥2 AEADs, and hash verification.
- Steering 2: Whisper2 will be deleted, so the Whisper1 JavaFX app is the only product and must be self-contained.
- Steering 3: a performance, memory, and modularity pass with concrete guidance.
- Owner message pt426u (resize "to reasonable limits"): handled by the in-flight PR. This pack only adds a regression check.
