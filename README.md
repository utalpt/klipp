# 🎬 Klipp

> 🧪 **This is Klipp Lab**, a fork of [Klipp](https://github.com/Flabbjaxaren/klipp) by Flabbjaxaren (GPL-3.0-only)
> on the `xiaomi-17u` branch. Its focus is log video and Dolby Vision from George's Stock camera mods, while
> staying a general video editor. It runs on any Android 10+ phone (64-bit); built and tested
> on a Xiaomi 17 Ultra. HLG export needs a 10-bit HDR HEVC encoder; the Dolby Vision options only show on phones
> with a Dolby Vision encoder (the 17 Ultra has one; the 15 and 14 Ultra not checked yet). It installs beside Klipp as **Klipp Lab** (`x17u.klipp.editor`). Fixes that suit everyone are offered to
> Klipp as pull requests; issues with the fork's own features go here, not to Klipp.

## 🧪 What Klipp Lab adds

- 🌈 **HLG export from the grade:** each clip's last OpenDRT node renders Rec.2100 HLG, 10-bit HEVC, tagged
  BT.2020 / HLG (Google Photos shows it as HDR). Ungraded clips, titles and captions are converted from SDR
  (BT.2408, SDR white at 75 % HLG), and the export dialog shows what will actually be made. *(Both sent to Klipp too:
  in Klipp 0.21.0-beta.2.)*
- 🎥 **Dolby Vision export from the grade:** the same HLG encoded by the phone's Dolby Vision encoder as Profile 8.4
  (HLG compatible), the dynamic metadata made from the edited frames.
- 🔁 **Dolby Vision in, Dolby Vision out:** Dolby Vision clips cut and edited come out as Dolby Vision 8.4 again
  (Export → Dolby Vision · keep HDR).
- 📈 **Bitrates above the encoder's listed limit:** the phone lists 160 Mbps for Dolby Vision; Klipp Lab passes the
  chosen bitrate on, up to 300 Mbps. Use CBR for Dolby Vision – in VBR the encoder keeps the bitrate low.
- 🎚️ **EI per clip:** the EI a log clip was shot at and the EI it is rated at, applied before all nodes, LUTs and
  OpenDRT; for the 17 Ultra mods also where the sensor clips (the 0.0072 × EI relation from George Kiarie's
  RawViewer).
- 🌡️ **White balance per clip:** Temperature and Tint in the clip's input (the EI chip on the Color page), in the
  linear light before all nodes, as in a raw developer – live while dragging.
- 📸 **MotionCam RAW (.mcraw) in:** the video picker's MotionCam RAW chip lists the .mcraw files of MotionCam's folder; importing
  develops each one into a log video (S-Log3, F-Log2, LogC4, DaVinci Intermediate…) at an EI of your choice or Auto,
  with the camera's white balance. The RAW settings stay changeable afterwards, as in a raw developer: on the Color page
  pick another EI or log and Develop again (the clip is swapped in place, with a new proxy), and the clip's
  Temperature/Tint can be baked into the develop. Exporting HLG adds OpenDRT to MotionCam clips that lack it. *(Early:
  feedback welcome.)*
- 🐢 **Slow-mo:** a high frame rate clip at the project's rate, every source frame used once (120 → 24, 120 → 30).
- 🗂️ **LUT browser:** previews and groups, .zip and multi import, multi-select delete.
- 🖼️ **Video picker** with previews (log clips included), opening in DCIM/Camera; clips keep their file names.
- 🔓 **Video picker with limited access** (Android 14+, "select some"): "Choose videos" adds more, and Allow access asks
  the system again before sending you to the settings. *(Sent to Klipp too: in Klipp 0.21.0-beta.2.)*
- ➕ **Add to the timeline** in the order selected, or straight on import.
- ⏱️ **Frame rates:** phone clips give a standard project rate (a 60 fps clip measured at 58.6 makes a 60 fps
  project), and the rate chosen for a new project is kept.
- 🎨 Files without a colour transfer keep their matrix and range.
- ✅ Select several projects and delete them at once.
- 🔲 The selection box scrolls the timeline at the edges.
- 🧲 **Close gaps:** hold a track's header → Close gaps, or Close gap beside Delete for a selected clip – the clip
  moves back to the one before it, linked clips follow.
- 💾 **Storage page** (Settings → Storage): what the app keeps on the phone in one place – MotionCam develops,
  proxies, node cache and renders, each with its Clear, plus Clear caches (proxies, node cache and renders at once;
  the MotionCam develops stay) – and "Warn when Klipp Lab uses more than" 5 / 10 / 20 / 50 GB (or off); opening a
  project then says what each kind takes.
- 🎓 **Grading for beginners** (Settings): the fundamental steps from log or MotionCam RAW footage to a finished image.
- ❓ **Long-press help:** hold a slider's name, a drop-down field, a Color page button or a timeline action to see what
  it does (controls that already had a long-press keep it).
- 🖥️ **Interface:** full screen (status and navigation bars hidden, the top bar just under the camera hole); interface
  size Normal, Compact (default) or Small (Settings → Appearance); Edit, Color and Audio always show their names; in
  landscape, drag the grip between the picture and the timeline (double tap to reset).

Everything the fork changes, entry by entry: [CHANGELOG-17U.md](CHANGELOG-17U.md).

**⬇️ Test build** (the latest CI build of `xiaomi-17u`, installs over the previous one):
https://github.com/utalpt/klipp/releases/download/debug/klipp17u-debug.apk

🙏 Credits: [Klipp](https://github.com/Flabbjaxaren/klipp) by Flabbjaxaren, on the
[FilmCraft](https://github.com/storytold/filmcraft) engine. Film simulation from
[spektrafilm](https://github.com/andreavolpato/spektrafilm) by Andrea Volpato et al. [OpenDRT](https://github.com/jedypod/open-display-transform) by Jed Smith. EI relation
and slow-mo idea from George Kiarie's RawViewer. MotionCam RAW read with MotionCam's own decoder library,
[motioncam-decoder](https://github.com/mirsadm/motioncam-decoder) (Apache-2.0, with the MIT-licensed JSON for Modern
C++ by Niels Lohmann and SIMDe). Dolby Vision approach from Dolby's
[dolby-vision-editor](https://github.com/DolbyLaboratories/dolby-vision-editor) sample (BSD-3-Clause-Clear).
HLG with deleted clips: the same fix as Klipp's 2797f03 by Flabbjaxaren.

📐 Methods (our own code, after published work): Malvar, He and Cutler's demosaic (2004) for MotionCam RAW; Kim et
al.'s Planckian locus fit (2002) for the white balance; ITU-R BT.2100 (HLG) and BT.2408 (SDR in HLG).

---

*Everything below is Klipp's own README.*

A multi-track video editor for Android phones, built for touch. A Kotlin/Jetpack Compose app on top of the
[FilmCraft](https://github.com/storytold/filmcraft) editing engine (Rust, via JNI); playback and export with Media3.

<p align="center">
  <img src="docs/screenshots/edit.png" width="24%" alt="The edit page: a title, captions, video and two audio tracks on the timeline">
  <img src="docs/screenshots/title.png" width="24%" alt="Add › Title: a preview, then the text, then the look">
  <img src="docs/screenshots/captions.png" width="24%" alt="Add › Captions: write a line and it goes in at the playhead">
  <img src="docs/screenshots/color.png" width="24%" alt="The color page: color wheels with the clips of the timeline above">
</p>

> 🚧 **Status:** a hobby project in active development. Android 10+ (arm64). Tested on a **vivo X300 Ultra** and a
> **OnePlus 12**. It comes with **no warranty** – keep copies of footage you care about. Issues are welcome; support is
> best effort.

## 📲 Install

Download `Klipp-<version>.apk` from [Releases](https://github.com/Flabbjaxaren/klipp/releases/latest) and open it on
the phone (allow installing from your browser or file manager when Android asks). Updates install over the old
version and keep your projects. Requires Android 10+ on a 64-bit ARM phone.

## ✨ Features

- ✂️ **Editing:** three video and three audio tracks, insert/overwrite from a source monitor, ripple, roll, slip,
  razor, linked audio, compound clips, markers, sync by sound (camera + separate recorder), undo/redo for everything.
- 👆 **Touch first:** swipe down through a clip to cut it, drag a clip up out of the timeline to delete it, drag any
  edge to trim (with a loupe and a two-frame trim view), long-press to lift, pinch to zoom; scrub on the ruler or the
  picture and slide the finger down for ½, ¼, ⅛ speed; two-finger tap = undo, three = redo (not yet inside the Add
  sheets).
- 📝 **Text-based editing:** on-device speech recognition (whisper.cpp with voice activity detection; Swedish,
  English and multilingual models in three quality steps) turns the audio into text. In the timeline: delete words to
  cut the video, remove pauses and filler words. In the source monitor (tap a clip in Media): read it as sentences and
  tap the ones you want to insert – one transcript per clip, shared by both. Automatic captions.
- 🎨 **Color:** node-based grading (wheels, curves, qualifiers, power windows with object tracking), LUTs, scopes,
  spektrafilm film simulation, OpenDRT, floating point through the whole video chain. Log input from phones and
  cinema cameras: Apple Log, vivo Log, S-Log3, V-Log, LogC3/LogC4, F-Log2, Log3G10, DaVinci Intermediate, ACEScct, T-Log.
- 🎞️ **Video layers:** transitions, titles and templates, captions, blend modes, masks with keyframes and tracking,
  keyframed motion, opacity and volume – linear, easy ease, ease in/out or hold, and a value graph with bezier
  handles (evaluated with the engine's own curve math, so preview and export match).
- 🎧 **Audio:** volume lines on the clips in the timeline (tap for a keyframe, drag the line or a keyframe, double tap
  to delete), mixer with automation, effects (noise reduction, speech enhance, EQ, compressor …), auto ducking,
  loudness measurement, voice-over, downloadable sound effect packs.
- ⚡ **Performance:** proxies, pre-rendering of heavy parts, render cache, HDR and 10-bit.
- 🔒 **Private:** nothing leaves the phone: speech recognition and all processing run locally; the network is only used to
  download speech models and sound packs you ask for.

## 📁 Project layout

- `app/` – the Android app (Compose, Media3 for playback and export)
- `rust/` – `klipp-bridge`, the JNI bridge to FilmCraft (`Session::execute`)
- `third_party/` – Media3 effect (modified), whisper.cpp (trimmed), the Silero VAD model – see `THIRD_PARTY.md`

## 🌍 Languages

English (default) and Swedish. Translations live in `app/src/main/res/values-*/` (Swedish in `values-sv/`); the
app language can be chosen per app in the system settings (Android 13+).

## 🎞️ Video layers

- **Export:** Media3 Transformer with the multi-layer compositor (`LayerCompositor`): V3 → V2 → V1 plus cross dissolve
  layers, titles and captions as overlays (`TimedOverlay`), templates and animations in `Titles.kt`.
- **Preview:** Media3's CompositionPlayer doesn't support multiple video sequences yet, so the main player shows the
  bottom layer and `UpperLayers` draws the upper layers (their own muted ExoPlayers in a TextureView, synced to the
  playhead), titles and captions on top. While you drag a slider, `LivePreview` draws a still frame.
- Long-press "Program" in the viewer for a diagnostics view.

## 🛠️ Building

Requirements: Android Studio (or SDK + JDK 21), Rust (rustup), Android NDK.

1. Install the Rust target and cargo-ndk (once):

   ```
   rustup target add aarch64-linux-android
   cargo install cargo-ndk
   ```

2. Build the Rust bridge into `app/src/main/jniLibs` (about 10–20 min the first time). The built library is not in the
   repository; GitHub Actions builds it on every push (`.github/workflows/build.yml`) and keeps the APK as an artifact:

   ```
   cd rust
   export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/<version>
   RUSTFLAGS="--remap-path-prefix=$HOME=~" cargo ndk -t arm64-v8a --platform 29 -o ../app/src/main/jniLibs build --release
   ```

3. Open the project in Android Studio and run, or build from the terminal:

   ```
   ./gradlew assembleDebug
   ```

### 🔑 Signing

Android only updates an installed app with an APK signed by the same key. The release build reads its key from
`~/.klipp/keystore.properties` (outside the repository; or a file named by the `KLIPP_KEYSTORE` environment variable):

```
storeFile=/path/to/klipp-release.jks
storePassword=…
keyAlias=klipp
keyPassword=…
```

Without it (e.g. on CI) the release build is signed with the debug key. `-Pklipp.debugSigned=true` forces the debug key,
to update an install that was signed with it. Keep the key and its password backed up – without them no update can
replace an installed Klipp.

## ⚠️ Known limitations

- Clips imported through the system photo picker get generic names (e.g. `28.mp4`) – Android hides the file names.
- Projects live inside the app: back them up with their media (Projects → long-press → Back up to a folder… or Save a
  project package with media…) before uninstalling Klipp or moving to another phone.
- vivo Log has no published specification; its curve was measured and its colors fitted to vivo's own vivo Log →
  Rec.709 LUT.
- Speech recognition runs on the CPU (the GPU path was tried and dropped: unstable drivers, slower on phones).

## 🧪 Tests

- Rust (the bridge against real files): `KLIPP_TEST_MEDIA=<folder> cargo test --release` – the folder needs
  `klipp_a.mp4`, `klipp_b.mp4` and `tal.m4a`; the workflow shows how to generate them with ffmpeg.
- Kotlin (gestures, snapping, ripple, the Rust → Kotlin contract, video layers, properties, screenshots):
  `./gradlew testDebugUnitTest` and `./gradlew recordRoborazziDebug`
- GPU (on a connected phone): the film and the grade nodes rendered by the phone's GPU, as playback runs them, against
  the CPU path: `./gradlew connectedGpuTestAndroidTest`. They run in their own app (`se.gendut.klipp.gputest`), so
  the installed Klipp and its projects are never touched.

The FilmCraft version is pinned in `rust/Cargo.toml` (`rev`). Update it deliberately.

## 📜 License

Klipp is free software: as distributed, the app and this repository as a whole are offered under the **GNU General
Public License, version 3 only** (`GPL-3.0-only`, see `LICENSE` and `docs/licensing.md`); the JNI bridge's own code
(`rust/`) is `GPL-3.0-or-later`. Everything
it contains or downloads from other projects, with licenses and changes, is listed in **`THIRD_PARTY.md`**; the full
inventory, license texts and notices are in **`LICENSES/`** and in the app (Settings → About → Licenses and source code).
Each release's Corresponding Source: `docs/source-release.md`.

Parts from other projects:

- **spektrafilm** (Andrea Volpato et al., https://github.com/andreavolpato/spektrafilm) – spectral film simulation,
  GPL-3.0. The color pipeline is ported to `app/src/main/java/se/gendut/klipp/engine/Spektra.kt`; the tables in
  `app/src/main/assets/spektrafilm` are computed with spektrafilm's code (`tools/spektrafilm/export_tables.py`).
  The film profiles and everything that encodes them (tables, LUTs) are **CC BY-SA 4.0** – see
  `app/src/main/assets/spektrafilm/SPEKTRAFILM_LICENSE.txt`. The spectral upsampling tables behind the film tables are
  Klipp's own (`tools/spectra`, after Jakob & Hanika 2019).
- **OpenDRT** (Jed Smith, https://github.com/jedypod/open-display-transform) – GPL-3.0, in `engine/OpenDrt.kt`.
- **motioncam-decoder** (MotionCam, https://github.com/mirsadm/motioncam-decoder) – Apache-2.0, fetched at a pinned commit
  and built into `libklipp_mcraw.so` (Klipp Lab, reading .mcraw), with the MIT-licensed JSON for Modern C++ and SIMDe
  headers it includes.
- **FilmCraft** (the editing engine, Rust), **Media3** (Apache-2.0), **whisper.cpp** (MIT).
- **Media3 effect** (Apache-2.0) as source code in `third_party/media3-effect`, modified so that the whole video chain
  computes in floating point (RGBA16F) – see `third_party/media3-effect/KLIPP.md`.
- **Media3 transformer** (Apache-2.0) as source code in `third_party/media3-transformer`, modified for live playback's cuts
  with one video renderer – see `third_party/media3-transformer/KLIPP.md`.
- **Media3 ExoPlayer** (Apache-2.0) as source code in `third_party/media3-exoplayer`, modified so a clip ending before an
  open GOP's keyframe keeps its last frames – see `third_party/media3-exoplayer/KLIPP.md`.

### The spektrafilm tables

```
python -m venv venv && venv/bin/pip install numpy scipy colour-science scikit-image opt-einsum numba matplotlib \
    exiv2 rawpy lensfunpy OpenImageIO pyfftw
git clone https://github.com/andreavolpato/spektrafilm
venv/bin/python tools/spektrafilm/export_tables.py spektrafilm/src app/src/main/assets/spektrafilm reference.json
```

`reference.json` (every stage for a few films and settings) is used by `SpektraTest` – copy it to
`app/src/test/resources/spektrafilm_ref.json`.
