# Image fixtures

PNG files and `jpeg/noise.jpg` are copied from Whisper2 @523e838 `testdata/`
(MIT, same author). `jpeg/photo-qf75.jpg` is a baseline 4:2:0 QF75 JPEG and
`bmp/sample.bmp` is an uncompressed 24-bit BMP, both generated for this
repository. `legacy/` holds Zhang–Tang images produced by Whisper 0.9.0
(`565ebd3`) before this upgrade:

- `zhang-tang-plain.png` — message `legacy-plain-v0.9`, stego key `legacy-stego-key`
- `zhang-tang-aes.png` — message `legacy-encrypted-v0.9`, stego key `legacy-stego-key`,
  encryption passphrase `legacy-enc-key` (AES-128-GCM / PBKDF2)

Cover for both legacy images: `png/photo.png`.
