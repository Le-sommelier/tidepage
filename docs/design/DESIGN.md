# pdfa — Design (working name)

Offline. No account. No ads. No telemetry. Kotlin + Compose (M3) over a Rust engine. Tokens: `tokens.json`. Prototype: `mockup.html`.

## 1. Principles
1. **Document first.** Chrome fades while reading; tools appear when relevant.
2. **Instant and honest.** Low-res page now, sharp in a moment. Real progress, always cancellable.
3. **Nothing is final.** Undo/redo everywhere, autosave, originals never overwritten without consent.
4. **One obvious thing.** Each screen has one primary action; the rest is quiet.
5. **Quiet product.** No upsell, no sign-in, no tips, no first-run screens.

## 2. Information architecture
```
Launcher ─▶ Home (recents) ─┬▶ Viewer ─┬▶ Tool tray ─▶ Organize | Merge/Split/Extract | Protect | Annotate | Forms
Open with / Share sheet ────┘          ├▶ Search · Bookmarks · Thumbnails (sheets)
Home "+" ─▶ Scan / Image→PDF / Merge   └▶ Save / Save copy
Home ─▶ Settings (appearance, default zoom, storage, about/licences)
```
Intents go straight to Viewer (Home is the Back destination). Max depth: 3.

## 3. Screens
| Screen | Primary action | Notes and states |
|---|---|---|
| Home | Open file (system picker) | Recents list with thumbnails; "+" speed-dial: Scan, Images to PDF, Merge. **Empty:** one line plus Open button. **Loading:** skeleton rows. **Error:** missing file row greyed, "Remove from list". |
| Viewer | Read | Continuous vertical scroll, pinch/double-tap zoom, fast-scroll with page badge. Top bar auto-hides; bottom tool tray. **Loading:** low-res tiles then sharpen. **Password-required:** inline sheet, 3 tries before cool-down hint. **Corrupted:** "Repaired view" offer, else read-only partial open with banner. **Safe mode:** engine restarted, banner "Reloaded safely. Your edits are saved at [time]", unsaved ink kept in journal. **Save conflict:** file changed elsewhere, dialog: Save as copy (default) / Replace / Cancel. |
| Search | Next match | Bottom-docked field, match count, highlights; no results state is plain text. |
| Bookmarks / Thumbnails | Jump to page | Bottom sheet, two tabs. |
| Page organizer | Select pages | Grid, long-press to multi-select, drag to reorder, contextual bar: rotate, delete, duplicate, insert. Undo/redo in top bar; autosave to journal; **Done** commits. |
| Merge / Split / Extract | Merge | Merge: ordered file list, drag handle, per-file page range. Split: by ranges or every N. Extract: choose pages. All show estimated size, progress, Cancel. |
| Protect | Save protected | Dialog: password + confirm + show toggle, strength hint, warning "No recovery if forgotten". Unlock mirrors it. |
| Annotate | Draw/highlight | Contextual bar: pen, highlighter, text note, eraser, colour, width, undo. Stylus switches to ink instantly; finger scrolls unless Pen mode is on. |
| Forms | Fill next field | Fields tinted accent; Next/Prev chips above keyboard; Clear field. |
| Scan / Images to PDF | Shutter | Camera, edge detect, batch strip, crop/filters, Done → name sheet → Viewer. **No-permission:** explains camera use, offers Pick images instead. |
| Settings | — | Theme, system colours, default zoom, dim pages, keep-screen-on, licences. Nothing about accounts. |

## 4. Key flows
**Open from Files/intent:** 1 Tap PDF in Files or share. 2 Viewer opens with low-res page 1 under 300 ms. 3 Sharpening completes; recents updated silently. (Encrypted: password sheet at step 2.)

**Reorder pages:** 1 Viewer ▸ tray ▸ Organize. 2 Long-press a page, drag. 3 Haptic tick; neighbours slide. 4 Undo icon enabled; autosaved. 5 Done ▸ "Saved" snackbar with Undo.

**Merge 3 files:** 1 Home "+" ▸ Merge. 2 Pick files (multi-select). 3 Drag to order; adjust ranges. 4 Merge ▸ progress with Cancel. 5 Result opens in Viewer; snackbar "Saved as Merged.pdf".

**Protect and save:** 1 Tray ▸ Protect. 2 Enter and confirm password. 3 Save protected copy (default) or replace. 4 Snackbar "Protected copy saved" with Open.

**Scan to PDF:** 1 Home "+" ▸ Scan. 2 Grant camera (once). 3 Shutter; page auto-cropped; strip grows. 4 Done ▸ rename ▸ Save. 5 Viewer.

## 5. Visual language
**Decision:** light is default and the reading sheet is always white. Dark theme darkens the chrome only; optional "Dim pages" tints sheets. Dynamic colour is **opt-in** (default off) to keep a recognisable identity and guaranteed contrast.

**Palette "Tidewater"** (accent Tidewater Teal `#0B6E6B`)
| Token | Light | Dark | Contrast |
|---|---|---|---|
| canvas | `#FFFFFF` | `#0F1514` | — |
| surface | `#F4F7F7` | `#1A2322` | — |
| ink | `#14201F` | `#E6EDEC` | 16.7 / 15.6 |
| inkMuted | `#4A5958` | `#A9B8B7` | 7.3 / 9.0 |
| accent | `#0B6E6B` | `#5CCFC9` | 6.1 / 9.9 on canvas |
| onAccent | `#FFFFFF` | `#00201F` | 6.1 / 9.2 |
| accentContainer / on | `#E0F2F1` / `#064B49` | `#12302F` / `#9FE8E3` | 8.6 / 10.2 |
| danger | `#B3261E` | `#FFB4AB` | 6.5 / 10.9 |
| highlight | `#FFF3B0` | `#6B5200` | 14.9 / 6.3 |
| outline (non-text, 3:1) | `#829291` | `#6F8180` | — |

**Type** (Roboto Flex/system, sp): display 32/40 · title 22/28 · titleSmall 16/24 · body 16/24 · bodySmall 14/20 · label 14/20 medium · caption 12/16.
**Grid:** 4 dp unit; gutters 16 (phone) / 24 (tablet). **Radii:** 4 · 8 · 12 · 16 · 28 · full. **Elevation:** tonal surfaces and hairlines first; shadow only on sheets, dialogs, snackbars. **Motion:** 80 / 150 / 250 / 350 ms, standard easing `0.2,0,0,1`; sheets 250, page transitions 150, nothing over 350.
**Icons:** outline, 24 dp, 1.75 stroke, Material Symbols/Lucide style; no brand marks.

## 6. Components
Top bar (auto-hide, 64 dp) · bottom tool tray (5 slots + More, labels always visible) · page thumbnail grid (min 104 dp, selection check, drag handle) · contextual annotation bar (slides above tray, replaces it while active) · bottom sheets (search, bookmarks, tool options, name/save) · snackbar with Undo (8 s, queue of one) · dialogs (only for destructive/irreversible: protect, replace, discard) · progress card with Cancel · banner (safe mode, corrupted).

## 7. Accessibility
TalkBack: every icon has a verb label ("Rotate page 3 clockwise"); pages announce "Page 3 of 42"; reorder via actions "Move earlier/later" (no drag required). 48 dp minimum targets. Layouts reflow to 200% font scale (tray goes to 2 rows, grid to 2 columns). Reduced motion removes slides and springs. High-contrast mode uses ink-on-canvas and 2 dp outlines. Reach: primary actions and the tool tray sit in the bottom 40%; top bar holds only navigation and overflow. Colour is never the only signal.

## 8. Performance-linked UX rules
Never block the main thread; engine calls are async with cancellation. Show cached or low-res tile within one frame, sharpen later, never a blank page or spinner over content. Operations over ~400 ms show progress; over 2 s show Cancel and keep the app usable. Autosave off-thread, debounced 2 s. Engine crash returns to last journal state (safe mode), never data loss.

## 9. Copy tone
Plain, short, calm, active voice, no exclamation marks, no jargon, tell what happens to the file.
1. "Open a PDF" 2. "No recent files yet" 3. "Page 3 deleted" · Undo 4. "Saved to Documents/Merged.pdf" 5. "This file is protected. Enter its password." 6. "Wrong password. Try again." 7. "No recovery if you forget this password." 8. "This file changed elsewhere. Save a copy instead?" 9. "Reloaded safely. Your edits are kept." 10. "This PDF looks damaged. Open what can be read?" 11. "Merging 2 of 3…" · Cancel 12. "Camera is only used to scan. Nothing leaves your phone." 13. "No matches" 14. "Working offline, always." (Settings footer) 15. "Discard 4 changes?"

## 10. What we deliberately do not do
No account, sign-in, cloud, upsell, trial, rating nag, tips carousel, promos, notifications, analytics, crash upload, or "Pro" badge. No dark patterns on Save. No silent overwrite. No custom brand mark in the UI chrome.

## 11. Adobe feel vs our own
| # | Adobe-style tool | Ours |
|---|---|---|
| 1 | Red brand accent | Tidewater Teal, restrained |
| 2 | Home full of cloud, promos, tool cards | Home = recents plus one Open button |
| 3 | Tools gated, labelled "Fill & Sign", "Export PDF" | Plain verbs: Fill forms, Save as… |
| 4 | Sign-in/onboarding at first launch | Zero screens, straight to Open |
| 5 | Top toolbar crowded with modes | Bottom tray in thumb reach; contextual bar on demand |
| 6 | Floating edit pencil FAB | Tray "Annotate" swaps in a bar; no FAB in Viewer |
| 7 | Rich sharing/cloud save dialogs | Save here, Save a copy; local only |
| 8 | Subscription prompts after actions | Nothing after actions except Undo |
