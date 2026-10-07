# Jotter

A native Android note-taking app where handwriting comes first. Built with Kotlin and Jetpack Compose,
designed for tablets and styluses (works with a finger too).

## Features

**Start page**
- Grid of notes with thumbnail, title and date
- Sort by name or date (tap again to flip ascending/descending), archive view
- Per-note menu: rename, share (PNG per page), archive, delete
- Delete shows a snackbar with **Undo** (10 seconds); a new note you never touched is discarded when you leave it
- New handwriting note: pick the paper, or reuse the last one

**Paper types**: blank, college ruled (3 sizes), Cornell, Cornell ruled, grid (3 sizes), cross grid, dot grid,
Séyès, Séyès with margin.

**Editor**
- Pages are as wide as the screen and infinitely tall; scroll with two fingers (or one, when finger drawing is off); several pages per note
- Tools: pen, highlighter (half transparent), calligraphic pen (flat 45° nib), object eraser (removes whole strokes)
- Tap selects a tool, **tap-and-hold** opens a thickness slider with a true-size preview
- Colour palette plus one custom colour (RGB sliders), pressure sensitivity, insert image, undo/redo
- Toolbox at the top (default), bottom, left or right; the kebab menu holds rename, "Draw with finger" and pressure
- "Draw with finger" off = palm protection: only a stylus draws
- Thickness, colours and the toggles are remembered between notes
- Autosave; the committed page is cached in a bitmap, so drawing latency does not grow with what is already written

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
  Model.kt       notes, strokes, file store (notes, trash, images), settings
  Draw.kt        paper patterns, stroke rendering, eraser hit-testing, export
  Editor.kt      handwriting editor, toolbox, gestures, page cache (and the text editor)
  Screens.kt     start page, paper picker, settings, sharing
  Theme.kt       light/dark colour schemes
  MainActivity.kt
```

Notes live in app-private storage: a small JSON file for metadata, a compact binary file for the strokes, and a PNG thumbnail
per note. There is no database, and the only dependencies are Compose, Material 3 and Activity.

## Known limits

- Inserted images cannot be moved or resized yet (they can be erased)
- The Cornell papers have no summary box (pages have no bottom)
- Shared PNGs are capped at 6000 page units of height
- Saving runs on the UI thread, which is fine for normal notes but could be moved off it for very large ones
- Text notes exist in the code but are hidden from the UI for now
