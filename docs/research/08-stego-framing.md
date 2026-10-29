> Imported from Whisper2 @523e838 (MIT); Whisper2 retired. This file is historical design context. The Java implementation in this repository is the product; nothing here is required to build or test Whisper.

# Stego framing (v2)

The crypto envelope itself is unchanged (version nibble 1). What changed is
where those bytes sit in the carrier.

The visit order is HKDF(Argon2id(passphrase, salt)), so the receiver needs the
salt before it can read the ciphertext. The public prefix is the 16-byte salt
alone. Profile and flags stay inside the keyed envelope. There is no public
profile byte and no plaintext magic.

The salt is coded with a public syndrome-trellis code on a public Fisher–Yates
subset of the cover:

- message: 16 salt bytes (128 bits)
- block width `ceil(1/cap)`, where `cap` is the same resolved rate used for the
  payload, including `--paranoid` and an engine's default (WAV is 0.02, so the
  salt width is 50)
- the local salt rate is therefore at most `cap`
- if the cover cannot hold that width plus the keyed length prefix, embed fails
  rather than narrowing the width (a narrower width would exceed the cap)
- hide and reveal derive the width the same way, so `--rate` and `--paranoid`
  must match
- the subset is spread over the whole cover, not the first rows
- on JPEG the samples are the nonzero luminance AC coefficients, not every AC
  coefficient. Zeros are not in that vector. Magnitude 1 is wet, so a change
  of ±1 cannot create or destroy a nonzero coefficient, and hide and reveal
  see the same subset. `ceil(1/cap)` is then a local bpnzAC, not a rate diluted
  by zeros. PNG and WAV already use one sample per rate unit, so they do not
  need this restriction

Everything else — flags, profile, file nonce, plaintext length, and STREAM
ciphertext — is the keyed payload.

Reveal does not read a profile from the carrier. Without a key file it tries
the default Argon2id profile (3 passes, 64 MiB, 4 lanes). It tries the high
profile (1 pass, 2 GiB) only when reveal was asked to (`--profile high`, or
the desktop profile menu set to high), and only after the default profile
fails to open the file. A cover written with `high` stays closed unless reveal
is asked to try it. A wrong passphrase on the default profile does not
allocate 2 GiB. A key file forces the key-file profile and does not mix the
stored salt into the visit order.

Syndrome-trellis embedding is checkpointed in segments of 8192 columns so a
large cover does not allocate a backpointer for every sample. Golden vectors
are the property tests in `internal/stego/stc` (round trip, wet positions, and
exhaustive minimum cost on tiny instances). There is no pySTC helper script.
