# 03 — Payload UX: Text | File | Image (embed); text / save-file / image-preview (extract)

This builds on the existing JavaFX app: `WhisperApp` with its Embed / Extract / Analyze tabs, plus `EmbedPane`, `ExtractPane`, `ImageCard`, `Forms`, `StatusBar`, `Choosers`, `PassphraseField`, and `whisper.css`. **The dark-only theme and the resize behaviour come from the in-flight PR.** Every new node here must follow that PR's responsive rules:
- `maxWidth = Double.MAX_VALUE` where a node should stretch.
- No fixed pixel heights, except thumbnails, whose `fitWidth` is bound to the parent.
- Use the stacked layout below the 820 px breakpoint, as `EmbedPane.arrange()` already does.

## 1. Layout at a glance
```
EMBED                                                    EXTRACT
┌ Cover (ImageCard, unchanged) ┐ ┌ What to hide ───────┐  ┌ Stego (ImageCard) ┐ ┌ Result ───────────────────┐
│ preview · 4032×3024 · PNG    │ │ [Text][File][Image] │  │ preview           │ │ ✅ Verified · SHA-256 1a2b…│
│ Holds ~150 KB (Standard)     │ │ <kind editor>       │  │ Auto-detected:    │ │ <kind viewer>              │
│ Cover/Stego toggle           │ │ CapacityMeter ▓▓▓░░ │  │  Adaptive (HILL)  │ │ [Save…] [Copy] …           │
└──────────────────────────────┘ ├ Protection ─────────┤  └───────────────────┘ └────────────────────────────┘
                                 │ Scheme ▾  Cipher ▾  │
                                 │ Keys (in-flight PR) │
                                 │ Stealth: Std/Low/Par│
                                 └ [Embed]  fingerprint┘
```

## 2. Embed: the "What to hide" segmented control
Today there is a `Text`/`File` radio pair, and File mode only reads **UTF-8 text**. Replace it with a **segmented toggle**: three `ToggleButton`s in one `ToggleGroup`, styled `segmented` to match the top `mode-bar`. Each kind has its **own editor node** inside a `StackPane`; only the selected one is visible and managed. **The three kinds must look different at a glance.**

| Kind | Editor look | Behaviour |
|---|---|---|
| **Text** | Large `TextArea` (wrap on) on a soft "paper" card. Live counter: `312 characters · 318 bytes`. | UTF-8. Ctrl/Cmd+Enter runs Embed. |
| **File** | A **drop tile**: dashed border, file glyph, "Drop any file or Browse…". Once a file is chosen it becomes a **file card**: a type icon by MIME (doc/archive/audio/video/generic), name, size, detected type ("PDF document"), and a "Compresses well / already compressed" hint. | Any bytes, any extension. The sanitized basename is kept so Extract can suggest it. Size is checked against capacity **before** reading (`Files.size`), and the file is read once with a streaming SHA-256 (01 §7). Hard cap is the scheme's `maxBytes`; a huge file is never loaded. |
| **Image** | A **thumbnail tile**: checkerboard behind transparency, up to 220 px, aspect ratio kept, showing dimensions, format, and file size. Below it, a **"Fit to capacity"** panel. | Accepts PNG/JPEG/GIF/BMP, plus WebP if an ImageIO plugin is present. Sent as IMAGE with the original bytes when it fits. If it doesn't fit, the panel offers **Keep original** ("needs 1.4 MB, cover holds 150 KB"), **Re-encode as JPEG** with a quality slider (40–90), and **Max edge** (512 / 1024 / 2048 / original). It shows the live resulting size and a before/after preview. Re-encoding sets `imageReencoded` and is labelled "the hidden image will be a smaller copy". **The cover is never re-encoded.** |

All three kinds share a **`CapacityMeter`** below the editor:
- The bar shows the payload against capacity: a green zone up to the recommended preset size, amber up to the hard cap, and a red overflow.
- The label reads like `18.2 KB of 150 KB · +123 B overhead`.
- On overflow, Embed is disabled and the meter suggests fixes: "Use a larger cover, Re-encode the image, or switch to Legacy (high capacity — easier to detect)".
- It updates live on every change.

**Protection card:**
- **Scheme** combo, filled from `SchemeRegistry` and showing each `SchemeInfo` subtitle:
  - *Adaptive (HILL) · recommended for PNG*
  - *Adaptive (JPEG) · keeps your JPEG as JPEG*
  - *Legacy (high capacity) · easier to detect*

  The default is `Auto`. Schemes that don't fit the current cover are disabled, with a tooltip explaining why.
- **Cipher** combo, filled from `CipherRegistry`:
  - *ChaCha20-Poly1305 (recommended)*
  - *AES-256-GCM*
  - *None — integrity only*, but only if the in-flight PR made encryption optional. It shows an amber "Not encrypted" chip.
- **Keys**: the in-flight PR's widgets, unchanged.
- **Stealth**: a Standard / Low / Paranoid segmented control. The recipient doesn't have to match it.
- **Advanced** (collapsed `TitledPane`): KDF profile Default or High (2 GiB, with a RAM warning), Keep metadata, Compression Auto/Off.

**Output:** the existing "Stego image" field. `suggestionName()` becomes scheme-aware: the JPEG scheme suggests `-stego.jpg`, every other scheme `.png`. A scheme-aware variant of `ImageFormats.requireLosslessOutput` allows `.jpg` **only** for `uerd-stc`.

**After embed:**
- A success banner: `Embedded 18.2 KB · Adaptive (HILL) · ChaCha20-Poly1305 · Verified ✓`.
- A **fingerprint row**: `SHA-256 1a2b3c4d…9f3a [Copy]`, with the hint "Share this fingerprint separately so the recipient can confirm it."
- Keep the Cover/Stego preview toggle and the **Open in Extract** handoff. Extend the handoff to pass scheme, cipher, and the in-flight PR's key handles.

## 3. Extract: the viewer depends on what came out
The result card swaps in one of three distinct **viewers**. Above whichever viewer is showing sit two things:
- A **`VerifiedBadge`**, using the 01 §6 wording: green ✅ Verified; amber ⚠ Not encrypted or Unverified legacy; red ❌ plus the specific failure.
- A meta line, e.g. `Adaptive (HILL) · ChaCha20-Poly1305 · Standard · 18.2 KB`.

| Detected | Viewer | Actions |
|---|---|---|
| **TEXT**: frame kind 0, or a legacy payload that `PayloadPreview` judges printable UTF-8 | Read-only "paper" `TextArea`, selectable, wrapped. | **Copy**, **Save as .txt…** |
| **FILE**: frame kind 1, or a legacy payload that is binary and not an image | **File card**: big type icon, original name, size, and type ("ZIP archive"). A "Show hex" disclosure reuses `PayloadPreview.hex` for the first 4 KB. | **Save file…** (`FileChooser` preset to the sanitized name), then **Show in folder**. **Never auto-open or execute.** Executable types (`.exe .app .sh .bat .jar .command .js .msi .dmg .scr`) get the warning "Only open files from people you trust." |
| **IMAGE**: frame kind 2, **or** a FILE/legacy payload whose magic bytes sniff as PNG/JPEG/GIF/BMP/WebP | **Image preview**: `fitWidth` bound to the card with `preserveRatio`, a checkerboard behind alpha, and a click-to-zoom dialog (fit / 100%). Caption like `photo.jpg · 1024×768 · JPEG · 212 KB`. If the sender re-encoded the image, a chip says "Resized by sender to fit". | **Save image…**, **Copy image** (to the clipboard), **Save as file instead**. When a FILE sniffed as an image, there is also a "Show as file" toggle. |

**Detection order:**
1. The frame's kind byte.
2. A magic-byte sniff (`MimeSniffer`): PNG `89 50 4E 47`, JPEG `FF D8 FF`, GIF `GIF8`, BMP `BM`, WebP `RIFF….WEBP`.
3. A printable UTF-8 check.
4. Otherwise, treat it as a file.

Never trust the extension or the MIME string alone.

**Renamed and removed controls:**
- "Extract message" / "Save message…" become **Extract** plus the per-viewer actions.
- The `Decrypt payload (AES-GCM)` checkbox goes away for new formats, because the envelope self-describes.
- Legacy 0.9 images are tried automatically. An "Advanced → Legacy: payload was encrypted with -e" option exists only for ambiguous legacy cases.

**Progress** (the `StatusBar.run` stages): Reading → Finding payload (preset 1/3…) → Deriving keys → Decrypting → Verifying → Done. **Cancel** works during key derivation.

## 4. Legacy payloads (Whisper1 ≤0.9)
Legacy payloads have no frame, so they go through the sniff path. Their badge reads ⚠ "Unverified (legacy format — re-embed to get verification)".

## 5. Safety rules for untrusted output
1. **Images.** Read the dimensions first with `ImageReader.getWidth/getHeight`, without decoding. Refuse anything over 40 MP or with an edge over 20,000 px, and show it as a File with "Too large to preview". Decode the preview with `ImageReadParam.setSourceSubsampling` down to ≤2048 px on a `Background` thread, so memory stays small.
2. **Names.** `NameSanitizer` keeps the basename only. It strips control characters, `/ \ : * ? " < > |`, and leading dots, rejects Windows reserved names (CON, NUL, COM1…), and caps names at 255 bytes. Fallback: `whisper-payload.<ext from sniff>`.
3. **Text.** Show it in a plain `TextArea`, never HTML or a WebView. If the text contains bidi overrides (U+202A–202E, U+2066–2069), show a warning chip.
4. **Writes.** Never write without a Save dialog. Overwrites go through the existing `Choosers.confirmOverwrite`.
5. **Memory.** `Arrays.fill` the payload bytes when the user clears the result, re-extracts, or leaves the tab after saving.

## 6. CLI parity (drivers/CliDriver)
```
whisper embed  -i cover.png -o out.png (-m "text" | --text-file note.txt | --file report.pdf | --image secret.jpg [--fit-quality 75 --fit-max-edge 1024])
               [--scheme auto|hill-stc|uerd-stc|zhang-tang] [--cipher chacha20-poly1305|aes-256-gcm|none]
               [--preset standard|low|paranoid] [--kdf default|high] [--keep-metadata]
               keys: per the in-flight PR (prefer --key-env VAR / interactive prompt; keep -k with a warning)
whisper extract -i stego.png [-o path | --out-dir dir]   # text → stdout/file; FILE/IMAGE → --out-dir/<sanitized name>
               stderr: "Verified · SHA-256 <64 hex>"; exit 0 verified, 2 auth/integrity failure, 3 nothing found, 1 usage
whisper inspect -i cover.png [--json]                     # capacity per scheme/preset
whisper analyze -i image.png                              # unchanged
```
`--message-file` stays as an alias of `--text-file`. `--file` accepts any bytes, with no UTF-8 restriction.

## 7. Accessibility and polish
- **Screen readers.** Every segmented button and every action has `setAccessibleText`. Badges never rely on color alone; each also has an icon and text.
- **Keyboard.** Ctrl/Cmd+1/2/3 switch tabs (existing). Alt+T / F / I switch the payload kind. Ctrl/Cmd+Enter runs Embed or Extract.
- **Drag and drop.** Dropping a file on the Embed form picks the File or Image kind by sniffing it. For an image, a toast says "Hiding as image — switch to File to keep it as a plain file".
- **Empty states and errors.** Each kind has an empty state with one line of guidance. Errors use the 01 §6 wording.
- **Resize.** No new logic. Add one headless layout regression test at 780×560, 1080×760, and 1600×1000 that checks there is no horizontal scroll and thumbnails scale with `fitWidth`.

## 8. README rewrite (Phase 8)
Sections, in order:
1. What Whisper does (one paragraph, no "undetectable" claims)
2. Quick start (desktop + CLI)
3. **Choosing a scheme**: the 02 §1 table, with capacity examples (1 MP ≈ 12 KB, 12 MP ≈ 150 KB at Standard)
4. **What you can hide**: Text, File, and Image, plus fit-to-capacity
5. **Verification**: what each badge means, how to share the fingerprint, and a summary of the failure table
6. Security model: payload-only encryption, Argon2id, the AEADs, and what Whisper does *not* protect against (recompression, resize, chat apps, a determined steganalyst, a weak passphrase)
7. Legacy 0.9 images
8. Build / test / bench
9. Credits and references (HILL, UERD, STC, RFC 8439/9106/5869, age STREAM)
10. License

Add screenshots of the 3 embed kinds and the 3 extract viewers under `docs/img/`.
