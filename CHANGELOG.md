# Changelog

## 1.0.0

Whisper2 has been merged into Whisper. This is the only product.

- Adaptive embedding: HILL + binary STC for PNG and BMP (`hill-stc`), UERD + STC on nonzero luminance AC for baseline JPEG (`uerd-stc`, no recompression).
- Payload encryption: ChaCha20-Poly1305 (default) and AES-256-GCM, keys from Argon2id and HKDF-SHA-256, age-style 64 KiB STREAM. Integrity-only HMAC when encryption is off.
- Every modern payload carries SHA-256 inside the authenticated frame. The UI and CLI show `Verified · SHA-256 …` or a specific failure. Legacy 0.9 images still extract and are labelled unverified.
- Embed Text, any File, or an Image. Fit-to-capacity re-encodes the hidden image, not the cover.
- Zhang–Tang remains available as the high-capacity legacy scheme.

## 0.9.0

Zhang–Tang LSB-pair embedding, optional AES-128-GCM, JavaFX embed / extract / analyze, split stego and encryption passphrases.
