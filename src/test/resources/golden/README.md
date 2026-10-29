# Golden vectors

Generated from Whisper2 @ `523e838` (MIT, same author) by `tools/golden-gen`.
Whisper2 is retired. These files are the source of truth for the Java port;
reproducing them does not require Whisper2 to build or test Whisper.

| File | Contents |
|---|---|
| `permute.json` | `permute.Order(n=1000, seed=00..1f)` |
| `stc.json` | `stc.Embed` / `Extract` for h ∈ {7, 10}. `null` costs are +Inf (wet). |
| `hill.json` | HILL costs on the 64×64 green plane of `fixtures/png/photo.png` |
| `uerd.json` | UERD cost head/tail and SHA-256 of the full float64 cost plane of `fixtures/jpeg/noise.jpg`. `null` is +Inf (DC). |
| `public-layout.json` | `publicLayout` width and salt indices for n=100000 at rates 0.10, 0.05, 0.02, plus SHA-256 of the big-endian int32 salt and rest index lists |

`costsSha256` / `saltSha256` / `restSha256` are SHA-256 over big-endian encodings
(float64 bits, or int32 indices).
