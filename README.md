# Jotter

A native Android note-taking app where handwriting comes first. Built with Kotlin and Jetpack Compose,
designed for tablets and styluses (works with a finger too).

## Features

**Start page**
- ☰ menu with **All notes**, your **folders** (alphabetical, with note counts) and **Archive**; back from a folder or the archive returns to All notes
- Folders are flat (no folders inside folders). Create one with **+ → Folder**; rename or delete it from its ⋮ menu. Deleting a folder keeps its notes
  unless you tick "Also delete the N notes in it" (unticked by default, with Undo)
- Grid of notes with thumbnail, title, date and a **star**; starred notes always come first, whatever the sort order
- **Search** field in the top bar (filters the current page by title), sort by name or date (tap again to flip ascending/descending)
- Per-note menu: rename, move to folder, share (PNG per page), archive, delete
- Delete shows a snackbar with **Undo** (10 seconds); a new note you never touched is discarded when you leave it
- New handwriting note: pick the paper, or reuse the last one; every name box opens with the keyboard up and a capital first letter

**Paper types**: blank, college ruled (3 sizes), Cornell, Cornell ruled, grid (3 sizes), cross grid, dot grid,
Séyès, Séyès with margin.

**Editor**
- Pages are as wide as the screen and infinitely tall; scroll with two fingers (or one, when finger drawing is off); several pages per note
- Tools: pen, highlighter (half transparent), calligraphic pen (flat 45° nib), object eraser (removes whole strokes), a **shape** tool and a **text box** tool
- Tap selects a tool, **tap-and-hold** opens a settings popup (thickness slider with a true-size preview; for shapes also the shape, fill and border; for text the font, style and size)
- **Shapes**: rectangle (default), ellipse, triangle, line, arrow, 5-point star and a spiky "price label" burst. Each has a fill and a border
  (both can be "none"), colours from the palette or your custom colour, and a border thickness
- **Text boxes**: tap the paper (or drag out a box of the width you want) and type with the keyboard; the text wraps inside the box and the first letter of a sentence is capitalised.
  Five fonts (Open Sans, serif, monospace, handwriting, Gochi Hand), bold, italic, underline, left/centre/right alignment, size and colour. Tap a box with the text tool to edit it again;
  an empty box is discarded. Style changes apply to the box you are typing in or have selected, and are remembered for the next one
- **Shapes, text boxes and images can be selected, moved, resized and rotated** with the shape tool (the text tool does the same for text boxes): drag inside to move, drag a corner to resize,
  drag the extra blue handle outside the bottom-right corner to rotate (snaps to 15°). Lines and arrows are reshaped by their end points
- Colour palette plus one custom colour (RGB sliders), pressure sensitivity, insert image, undo/redo, page navigation
- Toolbox at the top (default), bottom, left or right; the kebab menu holds rename, "Draw with finger" and pressure
- "Draw with finger" off = palm protection: only a stylus draws
- Thickness, colours and the toggles are remembered between notes
- Autosave; the committed page is cached in a bitmap, so drawing latency does not grow with what is already written

**Export and backup** (Settings → Export and backup)
- Pick a folder once with Android's folder picker, for example a folder in Google Drive; Drive for desktop then syncs it to your PC
- Every drawing is saved there as an **A4-wide PDF** (595 pt), as tall as its last item; each page of a drawing is one PDF page. The PDFs are vector
- `Jotter-backup.jotter` holds **all** drawings (a zip of the note files and images, plus folders) and can be restored from Settings
- Runs 10 seconds after the last change, when you leave a note and when the app goes to the background, and only for notes that changed.
  The status line in Settings shows the last export or the error

**Look and feel**: light, dark or follow-system theme; contrast, touch targets and screen-reader labels follow WCAG 2.2 AA.
The paper itself stays white in dark mode.

## Build and run

Requirements: JDK 17 and the Android SDK (platform 35). Point Gradle at the SDK in `local.properties`
(`sdk.dir=...`, not committed).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A tablet emulator (for example the Pixel Tablet profile with an API 34 image) is the best place to try it.

### Release build

`assembleRelease` signs with a keystore described in `keystore.properties` (git-ignored):

```properties
storeFile=jotter-release.jks
storePassword=...
keyAlias=jotter
keyPassword=...
```

Create a key once with `keytool -genkeypair -keystore jotter-release.jks -alias jotter -keyalg RSA -keysize 2048 -validity 10000`
and keep a backup: an app signed with another key cannot be installed over this one without uninstalling it (which deletes the notes).

```bash
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

## Project layout

```
app/src/main/java/com/follet/jotter/
  Model.kt       notes, folders, strokes, shapes, text boxes, images, file store (notes, trash, backup/restore), settings
  Draw.kt        paper patterns, stroke and shape rendering, selection/move/resize/rotate geometry, eraser hit-testing
  Editor.kt      handwriting editor, toolbox, gestures, page cache (and the text editor)
  Export.kt      automatic PDF export and backup into the chosen folder
  Screens.kt     start page (drawer, folders, search, stars), paper picker, settings, sharing
  Theme.kt       light/dark colour schemes
  MainActivity.kt
app/src/main/assets/fonts/   bundled Open Sans and Gochi Hand (plus their licences)
app/src/main/res/drawable/ic_launcher_*.xml   the adaptive app icon, drawn from docs/app-icon.svg
docs/app-icon.svg            source artwork of the app icon
```

Notes live in app-private storage: a small JSON file for metadata, a compact binary file for the strokes, shapes and image placements, and a PNG
thumbnail per note; text boxes are stored as UTF-8 text with their style; folders are a small JSON list. There is no database, and the only dependencies are Compose, Material 3, Activity and DocumentFile.

## Fonts

Open Sans and Gochi Hand are bundled in `app/src/main/assets/fonts/` (both under the SIL Open Font License, see the `OFL-*.txt` files next to them);
the serif, monospace and handwriting fonts are the system ones, so those look a little different from one device to the next.

## Known limits

- The Cornell papers have no summary box (pages have no bottom)
- Shared PNGs are capped at 6000 page units of height
- Saving runs on the UI thread, which is fine for normal notes but could be moved off it for very large ones
- Text notes exist in the code but are hidden from the UI for now; they are not exported as PDFs, and neither are empty drawings
- Deleting a note does not delete its PDF in the export folder, but the note disappears from the backup the next time it is rewritten
- A text box has one style for all its text (no mixed bold or colours within a box)
- Whether a given cloud folder (such as Google Drive) accepts overwriting files is up to its provider; the Settings status line shows any error
