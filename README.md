# Picknick

Picknick is a JavaFX desktop app for culling large batches of Nikon NEF (RAW) photos
(JPEGs are supported too). It groups your imports into **sessions** by capture time, then
lets you rapidly sort each shot into `keep`, `skip`, or `maybe` with single-key shortcuts.
For rapid-fire bursts it offers a side-by-side **burst review** mode to quickly pick the
best frame.

## Table of Contents

- [Features](#features)
- [Prerequisites](#prerequisites)
- [Directory Layout](#directory-layout)
- [Running the Application](#running-the-application)
- [Usage](#usage)
  - [Home / Sessions](#home--sessions)
  - [Review (viewer)](#review-viewer)
  - [Burst review](#burst-review)
  - [Gallery](#gallery)
- [Keyboard Shortcuts](#keyboard-shortcuts)
- [How It Works](#how-it-works)
- [Windows Installer](#windows-installer)
- [Troubleshooting](#troubleshooting)

## Features

- **Sessions:** Imports are automatically grouped into sessions by capture time (a gap of
  more than one hour starts a new session).
- **Fast culling:** Sort each image into `keep`, `skip`, or `maybe` with one keystroke.
- **Burst review:** Photos shot within ~2 seconds of each other are detected as a burst and
  reviewed king-of-the-hill style — a reigning "champion" frame is compared against each
  challenger in turn so the best shot rises to the top. You can bank several keepers from one
  burst, undo any decision, and nothing is committed to disk until the burst ends.
- **Gallery:** Browse a session's thumbnails color-coded by state, and split a session at any
  image.
- **Merge & split:** Drag one session onto another to merge them; split a session around any
  photo from the gallery.
- **Archiving & rename:** Archive finished sessions and rename them inline.
- **Zoom & rotate:** Double-click to zoom, drag to pan, rotate in 90° steps.
- **Fast previews:** Converted JPEGs are cached on disk (in `.cache`) keyed by file content, and
  opening a session pre-converts its RAWs in the background so images and burst pairs appear
  instantly. Capture dates and thumbnails are cached in memory (the thumbnail cache is bounded).
- **Window state:** Window position, size, and full-screen state are remembered between runs.

## Prerequisites

1. **Java Development Kit (JDK) 17 or higher** — e.g. [Adoptium / Temurin](https://adoptium.net/).
2. **dcraw** — a command-line RAW decoder, used to convert NEF files to JPEG for display.
   It must be installed and available on your `PATH`. Verify with `dcraw` in a terminal;
   it should print usage information.

## Directory Layout

Picknick works inside a single root directory. By default this is hardcoded to
`x:/Dropbox/picknick` (see `rootDirectoryPath` in
[`Picknick.java`](src/main/java/com/efsavage/picknick/Picknick.java)); change that constant
to point at your own location. On startup Picknick creates the following subfolders:

```
<root>/
  import/    <- drop new NEF/JPG/MOV files here, then click "Rescan"
  session/   <- one folder per session: YYYYMMDD-N (and "unknown" for undated files)
  mov/       <- .MOV video files are routed here, untouched
  .cache/    <- cached JPEG previews (safe to delete; regenerated on demand)
```

Within each session folder, sorted images are moved into `keep/`, `skip/`, and `maybe/`
subfolders; unsorted images sit in the session root. A small `session.toml` records the
session's display name, creation time, archived flag, and total count.

## Running the Application

From the project directory:

```powershell
# Windows — builds (incrementally) and launches
.\run.cmd
```

`run.cmd` is just a convenience wrapper around the Maven wrapper. The equivalent direct commands:

```powershell
# Windows
.\mvnw.cmd compile javafx:run
```

```bash
# macOS / Linux
./mvnw compile javafx:run
```

Run from the project directory — the Maven wrapper resolves its configuration relative to the
current folder.

## Usage

### Home / Sessions

The home screen shows a card per session with a thumbnail, capture date range, progress, and
three actions:

- **Review** — open the viewer and start culling unsorted images.
- **View** — open the read-only gallery of all images in the session.
- **Archive / Unarchive** — hide a finished session (toggle "Show archived" to see them).

Other gestures:

- **Rescan** — scan the `import/` folder and file new media into sessions.
- **Double-click a card title** — rename the session.
- **Drag one card onto another** — merge the dragged session into the target.

### Review (viewer)

Each unsorted image is shown full-window. Sort it with `k` / `s` / `m` (or the toolbar
buttons); the file moves to the matching subfolder and the next image loads. The next several
images are converted and preloaded in the background for snappy browsing. When all images are
sorted, a completion message appears and you return to the home screen.

If the next image belongs to a detected burst, Picknick automatically switches to burst
review.

### Burst review

Bursts are reviewed **king-of-the-hill** style (the default; toggle **King of the hill** off in
the burst toolbar to use the older random tournament instead). The reigning **champion** is shown
on the **left** (green border, 👑) and the next **challenger** in capture order on the **right**,
with a filmstrip of the whole burst above.

- **←** — champion stays; the challenger is discarded.
- **→** — the challenger wins and becomes the new champion; the old champion is discarded.
- **↓** — discard both and move on.
- **B** — **bank** the champion as a definite keeper and keep comparing the rest (use this to keep
  more than one distinct moment from a single burst). Banked frames get a green border in the
  filmstrip.
- **A** — keep all remaining candidates.
- **U** — **undo** the last decision.
- **Click any filmstrip thumbnail** to pull it in as the challenger — even one already seen or
  discarded (the champion can't challenge itself).
- Scroll to zoom, drag to pan (both images move together).

**Nothing is moved on disk until the burst ends** — so changing your mind is free and `U` always
works. When the burst finishes, banked frames and the champion you chose go to `keep` and the
rest go to `skip` (shown via a brief progress dialog for large bursts). If you skip straight
through a burst without ever choosing a champion, the leftover frame is **not** kept — it falls
through to normal single-image review so a whole bad batch can be rejected.

### Gallery

A grid of thumbnails for the session, bordered by state (keep = green, maybe = orange,
skip = red, unsorted = grey). Toggle **Show rejected** to include skipped images. Right-click
any image to **split the session before/after** that photo into a new session.

## Keyboard Shortcuts

| Context | Key | Action |
| --- | --- | --- |
| Viewer | `k` | Keep |
| Viewer | `s` | Skip |
| Viewer | `m` | Maybe |
| Viewer | `r` | Rotate clockwise |
| Viewer | `e` | Rotate counter-clockwise |
| Viewer | `F11` | Toggle full screen |
| Burst | `←` | Champion stays; discard challenger |
| Burst | `→` | Challenger wins; discard old champion |
| Burst | `↓` | Discard both |
| Burst | `B` | Bank champion as a keeper, keep comparing |
| Burst | `A` | Keep all remaining |
| Burst | `U` | Undo last decision |

Double-click an image in the viewer to zoom; drag to pan while zoomed. In burst review, click a
filmstrip thumbnail to make it the challenger.

## How It Works

- **Capture time** is read from EXIF (`DateTimeOriginal`, falling back to `DateTime`), refined
  with sub-second precision when available, via the
  [metadata-extractor](https://github.com/drewnoakes/metadata-extractor) library.
- **Sessions** are formed by sorting imports by capture time and starting a new group whenever
  the gap exceeds one hour. Bursts are formed similarly with a 2-second gap.
- **Display previews** are produced by invoking `dcraw -e -c` to extract the embedded JPEG,
  cached under `.cache/` keyed by a SHA-1 of the source path, size, and modified time. Opening a
  session warms this cache in the background, so by the time you reach an image it is usually
  already decoded.
- **File moves are verified** — each move copies to a temp file, compares size and MD5 hash
  against the source, then atomically renames and deletes the original. Name collisions with
  identical content are de-duplicated; collisions with different content get a numeric suffix.

## Windows Installer

To build an app-image with a Start Menu shortcut (requires JDK 17+ with `jpackage` on `PATH`,
`dcraw` on `PATH`, and `icon.ico` in the repo root):

```powershell
.\scripts\windows\package.ps1
```

For an `.exe` or `.msi` installer (requires the [WiX Toolset](https://wixtoolset.org/) on
`PATH`):

```powershell
.\scripts\windows\package.ps1 -Type exe
.\scripts\windows\package.ps1 -Type msi
```

## Troubleshooting

- **dcraw not found** — ensure `dcraw` is installed and on your `PATH`; test by running
  `dcraw` in a terminal.
- **Images fail to load** — a NEF that dcraw can't decode is automatically moved to the
  session's `skip` folder and skipped.
- **Wrong root directory** — edit the `rootDirectoryPath` constant in `Picknick.java`.
- **Performance** — previews are cached after first view; the first pass over a large session
  is the slowest. Lower `PRELOAD_COUNT` in the code to reduce memory use during review.
</content>
</invoke>
