> Imported from Whisper2 @523e838 (MIT); Whisper2 retired. This file is historical design context. The Java implementation in this repository is the product; nothing here is required to build or test Whisper.

# 05 — UI Toolkit Recommendation

Goal: a **beautiful, Mac-friendly designer UI** for hide / reveal / inspect, sharing the same Go core as the CLI. Cross-platform: macOS (primary), Windows, Linux.

## Contenders (state as of 2026-10-29)

### 1. Wails v3 — **RECOMMENDED**
- **Status:** v3.0.0 stable since **2026-10-26**. Weekly minors + nightlies after that. Docs: https://v3.wails.io/ · Changelog: https://v3.wails.io/changelog/ ✅. Requires **Go ≥ 1.26**.
- **Licence:** MIT ✅.
- **Model:** Go backend + web frontend in a native WebView (WKWebView on macOS, WebView2 on Windows, WebKitGTK 6 / GTK4 on Linux).
- **Why it fits Whisper2**
  - Designer-grade UI is natural: layout, typography, drag-and-drop, dark mode, charts for capacity meters — all ordinary HTML/CSS/JS.
  - Native menus, file dialogs, drag-drop, notifications ship in v3.
  - Bindings layer maps cleanly onto `internal/app` use-cases.
  - Large community (~37k★), active release train.
- **Costs / risks**
  - Node/npm toolchain for the frontend (mitigate: pin pnpm, `minimum-release-age` as Wails templates now suggest).
  - Linux ships WebKitGTK; package it carefully (AppImage notes in the changelog).
  - Known limitation: treat any iframe as having access to bound methods — **don't embed untrusted content**.
  - Security advisories in the 3.0.0 notes (child-frame `wails:` messages, etc.) — stay on patched weekly releases.
- **Pin:** `github.com/wailsapp/wails/v3@v3.0.0` (or latest 3.0.x patch), not `@latest` nightlies for release builds.

### 2. Fyne v2.8 — strong pure-Go fallback
- Releases: v2.6.0 (2025-04, race-free threading via `fyne.Do`), v2.7.0 (2025-10), **v2.8.0 (2026-07)** ✅. Licence BSD-3-Clause. ~29k★.
- **Why consider it:** single binary, no Node, no WebView CVE surface, great for a utility aesthetic, passes Go race checks.
- **Why not primary:** custom theming to "designer beautiful" takes more work than CSS; some widgets still feel utilitarian; text-heavy inspector layouts are harder. Wayland-by-default in 2.8 is fine.
- **Use if:** Kevin wants zero web stack, or Wails packaging becomes painful.

### 3. Alternatives (not recommended as primary)
| Toolkit | Licence | Notes |
|---|---|---|
| Gio (`gioui.org`) | Unlicense OR MIT | Immediate-mode; powerful; steep design curve; smaller widget ecosystem. |
| Cogent Core | BSD-3 | Active 2026; still smaller mindshare than Fyne/Wails. |
| miqt (Qt bindings) | MIT | Native Qt look; pulls Qt libs; heavier redistribution. |
| therecipe/qt | LGPL-3 | Mature but maintenance soft (last push 2024-03). |
| WebView-only (`webview/webview_go`) | MIT | Too low-level; you'd reinvent Wails. |
| Electron / Tauri | — | Outside the "Go desktop" brief; Tauri would duplicate Wails' niche with a Rust side. |

## Recommendation
**Ship with Wails v3 + TypeScript + Svelte (or React — both are first-class Wails templates).** Keep the Go core UI-agnostic (`internal/app`) so a Fyne shell could be added later without rewriting engines.

### UI information architecture (v1 screens)
1. **Home / drop zone** — drag cover + optional payload file.
2. **Inspect** — capacity meter, engine selected, warnings (low QF, tiny image, already-stego heuristic).
3. **Hide** — passphrase (with strength + Argon2 profile), AEAD choice advanced, rate slider with red zone, metadata toggle.
4. **Reveal** — passphrase, output path, verify-on-open.
5. **Activity** — last jobs, durations, change counts (no secrets logged).
6. **Settings** — default engine, paranoid mode, theme, keychain toggle.

### Mac-friendly details
- Native share of menu bar (App / File / Edit), Traffic-light compliant frameless option only if carefully done; prefer framed for v1.
- `NSOpenPanel` / `NSSavePanel` via Wails dialogs.
- Respect reduced-motion and system dark mode.
- Notarised `.dmg` later; not a v1 coding-sprint requirement.

### Security UX
- Passphrase field never written to disk; optional keychain via `zalando/go-keyring`.
- Show a clear banner: **"Security against steganalysis depends on payload size and cover choice. Crypto protects confidentiality if the stego is discovered."**
- Refuse `--passphrase` on CLI; same spirit in UI (no "remember me" without keychain).
