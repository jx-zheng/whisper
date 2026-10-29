> Imported from Whisper2 @523e838 (MIT); Whisper2 retired. This file is historical design context. The Java implementation in this repository is the product; nothing here is required to build or test Whisper.

# 01 — Literature Survey: Steganography landscape (with 2023–2026 focus)

How to read this: each family gets the same four ratings, with citations. ✅ = verified this session (fetched or read primary source); ◐ = verified via search-result abstract / metadata only.

- **Extractability in practice**: can a receiver recover the *exact* bits after a realistic save/transfer? Encrypted payloads need **bit-exact** recovery, or ECC on top.
- **Security vs steganalysis**: how detectable it is against modern detectors (SRM-type rich models, SRNet/YeNet/XuNet CNNs).
- **Go feasibility**: can it run in pure Go, or with a well-maintained cgo dependency, on desktop CPUs?
- **Verdict for Whisper2.**

---

## 1. Content-adaptive embedding = distortion function + syndrome coding

### 1.1 Syndrome-Trellis Codes (STC)
- Filler, Judas, Fridrich, *Minimizing Additive Distortion in Steganography Using Syndrome-Trellis Codes*, IEEE TIFS 2011. PDF: https://dde.binghamton.edu/filler/pdf/Fill10tifs-stc.pdf ✅. DOI: https://doi.org/10.1109/tifs.2011.2134094 ✅. Toolbox (C++/Matlab): http://dde.binghamton.edu/download/syndrome/ ◐.
- How it works: the sender minimises the total sum of per-element costs subject to H·y = m (mod 2). H is a banded parity-check matrix built from a small h×w submatrix, and the minimisation is solved with the Viterbi algorithm. Time and space are linear in n, with 2^h trellis states (h=10 is typical). It operates "near the payload–distortion bound". The receiver only computes the syndrome H·y and never needs the costs.
- **Extractability:** bit-exact over a lossless channel. Any channel change propagates errors (Kin-Cleaves & Ker 2018, as summarised in Butora et al. below). Known implementation pitfall: when the message is very small relative to n, the pySTC wrapper of the C++ code sometimes produced ±2 changes (Butora et al. 2023, §IV-B). **Always re-extract after embedding and verify.**
- **Security:** the coding layer is near-optimal. Security depends entirely on the cost function and the payload size.
- **Go feasibility:** high. No Go implementation exists (search found Python and Rust only). Reference implementations: `daniellerch/pySTC` (MIT wrapper around the Binghamton C++), https://github.com/daniellerch/pySTC ✅, and Rust `stenoxide-core` (Apache-2.0, native STC) https://docs.rs/stenoxide-core ◐. The Binghamton toolbox has no clear OSS licence, so **write a clean-room port from the paper.**
- **Verdict:** MUST. This is the core of every v1 engine.

### 1.2 Spatial cost functions (lossless images: PNG/TIFF/BMP)
- **HILL**: Li, Wang, Huang, Li, *A new cost function for spatial image steganography*, ICIP 2014, https://doi.org/10.1109/icip.2014.7025854 ◐. Cost = LowPass15×15( 1 / LowPass3×3(|KB-highpass * X|) ). It's three convolutions, two of them box filters, so it's O(n) with integral images. Simple, fast, and still a strong baseline.
- **WOW / S-UNIWARD**: Holub, Fridrich, Denemark, *Universal distortion function for steganography in an arbitrary domain*, EURASIP JIS 2014, https://link.springer.com/article/10.1186/1687-417X-2014-1 ✅. Directional Daubechies-8 wavelet filter bank; relative change of wavelet coefficients. Caveat: with the original σ the spatial version has a probability-streaking flaw that can be exploited. Fix it by raising the stabilising constant σ (Denemark, Fridrich, Holub, SPIE 2014, https://doi.org/10.1117/12.2044803 ✅).
- **HUGO, MiPOD**: older and more complex respectively. Not needed for v1.
- Reference implementations for cross-checking costs: `uibk-uncover/conseal` (MPL-2.0, Python; simulates embedding only), https://github.com/uibk-uncover/conseal ✅. It covers HILL, HUGO, MiPOD, S-UNIWARD, WOW, J-UNIWARD, UERD, EBS, nsF5. Its J-UNIWARD and nsF5 are ports of the Binghamton Matlab code.
- **Extractability:** bit-exact if the file is saved losslessly and never re-encoded.
- **Security:** best-in-class for non-learned schemes. Rough practical ceiling: SteganoGAN's paper, quoting Ye et al. 2017, gives about 0.3 bpp (WOW), 0.4 (S-UNIWARD) and 0.5 (HILL) at a 20% detection error. Under today's CNN steganalysis with a known cover source, **≤0.1 bpp is the sensible default.** stenoxide hard-caps at 0.02 bpp.
- **Go feasibility:** high. HILL is trivial. S-UNIWARD needs 2-D convolutions with 16-tap filters, which is fine in pure Go and parallelises across goroutines.
- **Verdict:** HILL is MUST. S-UNIWARD (fixed σ) is SHOULD.

### 1.3 JPEG-domain cost functions
- **J-UNIWARD** (same EURASIP 2014 paper ✅). Best published security among classical JPEG schemes, but computing costs is expensive: each DCT change has to be pushed through a wavelet decomposition of a 23×23 neighbourhood.
- **UERD** (Guo, Ni, Su, Tang, Shi, TIFS 2015, cited in Butora 2023 ✅). Block energy × quantisation step. O(n), and much faster than J-UNIWARD. Butora et al. call it "a good tradeoff between complexity and practical security".
- **nsF5 / F5 / JSteg**: older. JSteg is broken by chi-square analysis. F5 shrinkage causes extraction desync unless wet-paper codes are used. Don't use either.
- **Measured detectability (lab, worst case for us):** SRNet with Low-High scan, `convert` coder, non-robust UERD. P_E = 0.1155 at 0.1 bpnzAC QF75; 0.2423 at 0.1 bpnzAC QF95; 0.0075 at 0.4 bpnzAC QF75 (Butora, Puteaux, Bas, arXiv 2211.04750, Table III) ✅. SRNet itself: Boroumand, Chen, Fridrich, TIFS 2019, https://ws2.binghamton.edu/fridrich/Research/SRNet.pdf ✅. Its J-UNIWARD 0.4 bpnzAC QF75 detection error is about 0.067 in the paper's ablation (§ layer study). **Lesson:** high-QF covers plus low payload give the largest margin, and 0.4 bpnzAC is not "safe".
- **Go feasibility:** medium. Go's `image/jpeg` decodes straight to pixels and **does not expose quantised DCT coefficients**. We need a coefficient-level reader/writer that reproduces the file byte-for-byte outside the scan data. Options are in `06-open-source-libs.md`: fork the stdlib decoder (BSD) plus our own Huffman encoder, audit the brand-new `github.com/0verkilll/jpeg` (MIT, v1.0.1, June 2026, 0 stars), or use cgo libjpeg-turbo. `lukechampine.com/jsteg` (MIT) shows how to fork the stdlib decoder, but its `Hide(out, img, data, nil)` API takes an `image.Image` and **re-encodes**, so it isn't suitable as-is.
- **Verdict:** UERD+STC is MUST. J-UNIWARD is SHOULD.

### 1.4 Adversarial embedding
- **ADV-EMB**: Tang, Li, Tan, Barni, Huang, *CNN-Based Adversarial Embedding for Image Steganography*, TIFS 2019, https://arxiv.org/abs/1803.09043 ◐, PDF https://usiena-air.unisi.it/retrieve/handle/11365/1080748/245190/Tang-2019-Cnn-based-adversarial-embedding-for.pdf ◐. Adjusts costs using gradients from a target CNN steganalyser.
- **Extractability:** still bit-exact, since it is still STC. **Security:** helps against the targeted detector. Gains shrink against a re-trained or unknown detector (arms race). **Go feasibility:** low. It needs a CNN forward/backward pass at embed time.
- **Verdict:** don't build. Possibly v3 via an optional ONNX plugin.

### 1.5 Robust (lossy-channel) JPEG steganography
- **Errorless robust JPEG steganography**: Butora, Puteaux, Bas, IEEE TDSC 2023, https://arxiv.org/abs/2211.04750 ✅. They build the set of DCT coefficients that survive recompression *by a known compressor* across 64 lattices, embed only there with STC, and get guaranteed extraction. It was validated against ImageMagick `convert`, mozjpeg, and **Slack's recompression** (identical to `convert`). Embedding success at QF≤94 is 100%. About 10 s per 512×512 on an M1. The authors stress that "even a single unexpected change can make the whole message unreadable if it is encrypted."
- Follow-up: steganographic polar codes, 2024, https://link.springer.com/article/10.1186/s13635-024-00173-4 ◐.
- **Go feasibility:** low-medium. It needs bit-exact emulation of the target platform's JPEG encoder (libjpeg-turbo/mozjpeg via cgo) and repeated recompression calls.
- **Verdict:** v2 candidate ("Survives Slack/X upload" mode). Not overnight.

## 2. Deep-learning steganography

### 2.1 SteganoGAN (2019)
- Zhang, Cuesta-Infante, Xu, Veeramachaneni, arXiv 1901.03892 ✅. Code: https://github.com/DAI-Lab/SteganoGAN (MIT; last push 2023) ✅.
- Claim: "4.4 bpp". **What it means:** RS-BPP = D·(1−2p). The Dense model at D=6 on COCO has **87% raw bit accuracy**, and 4.40 is the theoretical Reed–Solomon-corrected rate (Table 1). Security was evaluated against StegExpose (auROC 0.59), which is weak, and a Ye-Net detector that only works if the model weights are unknown. At 20% detection error the paper itself puts it at about 2 bpp.
- **Extractability:** probabilistic. You need heavy ECC, plus lossless PNG saving and float→uint8 rounding that matches training. **Go feasibility:** inference only via ONNX (cgo, `yalue/onnxruntime_go`, MIT ✅) with a model of tens of MB. **Verdict:** no.

### 2.2 Invertible networks: HiNet (ICCV 2021), ISN (CVPR 2021) and robust variants
- HiNet: Jing et al., https://openaccess.thecvf.com/content/ICCV2021/html/Jing_HiNet_Deep_Image_Hiding_by_Invertible_Network_ICCV_2021_paper.html ◐. Code https://github.com/TomTomTommi/HiNet (archived, no licence) ✅.
- ISN: Lu, Wang, Zhong, Rosin, https://openaccess.thecvf.com/content/CVPR2021/html/Lu_Large-Capacity_Image_Steganography_Based_on_Invertible_Neural_Networks_CVPR_2021_paper.html ◐. Code https://github.com/nkwr/ISN (no licence) ✅.
- **These hide an image in an image.** Recovery is judged by PSNR, not bit accuracy.
- **Practical-extractability failure documented:** PRIS (Yang et al., arXiv 2309.13620; Eng. Appl. AI 2024) ✅ points out that prior INN methods "ignore" 8-bit **rounding error**, which is unavoidable when saving. With rounding, HiNet's recovered secret is about 40 dB PSNR. Under JPEG QF80 it falls to about 11 dB when the model isn't trained for it (Table 6). PRIS improves robustness, but at 26–36 dB secret PSNR it is still lossy.
- **Verdict:** **SOTA on paper, unusable for encrypted payloads.** Don't build.

### 2.3 Generative / "coverless" diffusion steganography (2023–2026)
- **Pulsar** (Jois, Beck, Kaptchuk, ACM CCS 2024), https://eprint.iacr.org/2023/1758 ◐, code https://github.com/spacelab-ccny/pulsar (MIT) ✅. Uses the variance noise in diffusion sampling. Provably secure (output distribution preserved), about 320–613 bytes per image, under 3 s online on a laptop. Needs identical model and sampler on both ends.
- **CIF** (Qian et al., arXiv 2508.00434, 2025) ✅. On 512×512 with rectified flow: lossless extraction accuracy 99.02%, 92.21% after JPEG QF50. Capacity **0.0625 bpp** (16,384 bits/image). Measured on an NVIDIA L40 GPU. Baselines: GRDH 98.05% lossless, Diffusion-Stego 98.01%.
- Others: provably secure diffusion (SaTML 2025, https://doi.org/10.1109/satml64287.2025.00057 ◐), MDDM (ICML 2025, https://proceedings.mlr.press/v267/xu25ah.html ◐), latent-diffusion provably-secure robust stego (AAAI, https://ojs.aaai.org/index.php/AAAI/article/download/37125/41087 ◐).
- **Extractability:** about 1–8% BER, so ECC is mandatory. Inversion depends on floating-point determinism across GPUs and CPUs, which is an operational risk. **Security:** excellent in theory (distribution-preserving). **Go feasibility:** very low (multi-GB models, GPU). **Product mismatch:** it generates a new picture rather than hiding in the user's own photo.
- **Verdict:** keep as a research watch-item, possibly a v3 "generate a cover" plugin. Not v1.

### 2.4 Learned steganalysis (the adversary)
- SRNet (above ✅), YeNet, XuNet, SiaStegNet (used as detectors in CIF ✅). Aletheia (`daniellerch/aletheia`, MIT ✅) is a practical open-source steganalysis toolbox we can use for **regression testing of detectability**.

## 3. Provably-secure text steganography (LLM sampling)
- **Meteor** (Kaptchuk, Jois, Green, Rubin, CCS 2021), https://eprint.iacr.org/2021/686 ◐. **Discop** (Ding et al., IEEE S&P 2023), https://ding.ac.cn/publication/sp23_discop/ ◐, code https://github.com/comydream/Discop (no licence) ✅.
- **Extractability:** the receiver must reproduce *bit-identical* token distributions. GPU/CPU float non-determinism, model-version drift and tokenizer changes break decoding. **Go feasibility:** low (needs an LLM runtime). **Verdict:** no for v1. Interesting "creative carrier" research spike later.

## 4. Audio
- Survey: *Review and Analysis on audio steganography techniques* (2024), https://doi.org/10.33545/26633582.2024.v6.i1a.106 ◐. *Deep Learning for Steganalysis of Diverse Data Types* (arXiv 2308.04522) ◐.
- Adaptive audio: GAIE_MAS (IEEE TIFS 2024), https://doi.org/10.1109/tifs.2024.3417268 ◐. Weighted DCT energy for location selection plus suppression of micro-amplitude modifications.
- **Practical v1 design:** 16/24-bit PCM WAV (and FLAC, which decodes losslessly to PCM). Cost per sample comes from inverse local residual energy, the 1-D analogue of HILL: cheap in loud/noisy passages, wet (∞) in silence and digital-zero runs. Then STC.
- **Extractability:** bit-exact for WAV/FLAC. Destroyed by MP3/AAC/Opus transcoding (MP3Stego-style in-encoder embedding is a different, older line). **Go feasibility:** high (RIFF parsing is about 150 LOC; `mewkiz/flac` has an encoder since v1.0.6 ◐).
- **Verdict:** WAV is MUST; FLAC is SHOULD.

## 5. PDF
- *Hiding Sensitive Information Using PDF Steganography* (arXiv 2405.00865, 2024) ◐: TJ-operator kerning adjustments. Classic: Zhong et al., IJNS 2007, http://ijns.jalaxy.com.tw/contents/ijns-v4-n1/ijns-2007-v4-n1-p17-26.pdf ◐.
- **Extractability:** fragile. Any PDF re-save, linearisation or optimiser normalises content streams. **Security:** weak against structural analysis (unusual TJ numbers). **Go feasibility:** medium (`pdfcpu`, Apache-2.0 ✅, for parsing and writing).
- **Verdict:** NICE (v2). Don't ship append-to-PDF or extra-object "stego"; that's just hiding data in plain view.

## 6. Video
- Motion-vector and residual-coefficient stego in H.264/HEVC means modifying the encoder (x264/ffmpeg via cgo, GPL). Social platforms always re-encode. **Verdict:** out of scope. A NICE alternative is lossless animated PNG (APNG) frames through the spatial engine.

## 7. Practical-tool lessons (what breaks real tools)
- **steghide + stegseek:** stegseek "can run through the entirety of rockyou.txt in under 2 seconds" and "can also be used to extract steghide metadata without a password" (https://github.com/RickdeJager/stegseek, GPL-2.0 ✅). Lessons: memory-hard KDF, and no detectable header or metadata in the embedded stream.
- **LSB-replacement tools** (`auyer/steganography`, `DimitarPetrov/stegify`, both MIT ✅) write sequential LSBs. That's trivially detectable (chi-square, RS analysis, sample pairs) and offers no keyed positions.
- **stenoxide** (Rust, Apache-2.0, https://github.com/stenoxide/stenoxide ✅) is the closest modern reference design: HILL+STC, Argon2id → HKDF, XChaCha20-Poly1305, zstd, keyed permutation, **no header/salt/nonce stored**, a 0.02 bpp cap, and **PNG envelope fidelity** (copies the original's ancillary chunks and IDAT chunk sizes, because a re-encoded single-IDAT PNG is identifiable "with a hex viewer").
- Go-specific fingerprint: stdlib `image/png` writes IDAT through a 32 KiB `bufio.Writer` (`bufio.NewWriterSize(e, 1<<15)` in `src/image/png/writer.go` ✅). Photo software typically emits 8 KiB chunks.

## Summary matrix

| Family | Bit-exact extraction | Security (modern steganalysis) | Go feasibility | Whisper2 |
|---|---|---|---|---|
| HILL/S-UNIWARD + STC (PNG) | Yes (lossless channel) | Good at ≤0.1 bpp | High (pure Go) | **v1 MUST** |
| UERD/J-UNIWARD + STC (JPEG) | Yes (no recompression) | Good at ≤0.1–0.2 bpnzAC, high QF | Medium (DCT I/O work) | **v1 MUST (UERD)** |
| Audio PCM adaptive + STC | Yes (WAV/FLAC) | Moderate (less studied) | High | **v1 MUST (WAV)** |
| Robust errorless JPEG | Yes, for one known compressor | Slightly below non-robust | Low-med (cgo libjpeg) | v2 |
| Adversarial (ADV-EMB) | Yes | Better vs targeted CNN | Low | v3 maybe |
| SteganoGAN | No (87% raw at 4.4 RS-bpp) | Weak eval | Low (ONNX) | No |
| HiNet/ISN/PRIS | No (image-in-image, lossy) | n/a for bits | Low | No |
| Diffusion generative (Pulsar, CIF) | ~99% lossless, needs ECC | Strong (distribution-preserving) | Very low (GPU) | Watch |
| LLM text (Meteor/Discop) | Fragile (determinism) | Provable in theory | Low | Watch |
| PDF TJ kerning | Fragile | Weak | Medium | v2 NICE |
| LSB replacement | Yes | Broken | Trivial | Compatibility mode only, labelled insecure |
