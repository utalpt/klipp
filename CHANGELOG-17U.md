# Klipp Lab - changelog

Klipp Lab is a fork of [Klipp](https://github.com/Flabbjaxaren/klipp) by Flabbjaxaren (GPL-3.0-only), kept on the
`xiaomi-17u` branch of [utalpt/klipp](https://github.com/utalpt/klipp). Each version is Klipp's version plus the
fork's release number (`0.21.0-beta.1-17u.1` = Klipp 0.21.0-beta.1, fork release 1). Everything in Klipp's own
changelog for that version applies too; this file lists only what the fork adds or changes.

The fork's priority: log video, above all from George's camera mods for the Xiaomi Ultra phones (their S-Log3 and
other log profiles, HLG out); their Dolby Vision video next. All while staying a general video editor for any phone
and any footage.

## Next release (unreleased)

Built and tested on the test branch; goes into the next release.

Color and export
- LUT browser: in a node's LUT tab, one strip of cards, each showing the current frame graded with that LUT, grouped
  by pack (the group's name above its first card); opens at the LUT in use.
- LUT import: several .cube files and .zip packs at once; a zip's folders become groups. Hold a LUT to select
  several (tap a group's name for the whole group) and delete them.

- New project: the frame rate chosen in the dialog is kept when the size comes from the first clip (a project created
  at 24 fps became 120 fps with a 120 fps clip).
- Video picker with "select some" (Android 14+): a "Choose videos" button opens the system's selection again to add
  more; before, with none chosen the picker only said no videos were found.
- Video picker, Allow access: asks with the system's dialog again, and opens the app's settings only when the system
  no longer shows it (after "Allow none" in the selection it went straight to the settings).
- A new project's frame rate from a phone clip snaps to the nearest standard rate within 4 % (60, 120 and the other
  usual rates): a 60 fps clip whose variable rate averaged 58.6 made a 58.598 fps project, which dropped frames on export.
  The NTSC rates (59.94 …) only when the file really is on one.
- HLG / Dolby Vision from the grade: ungraded clips, titles and captions are no longer written as SDR values into the
  HDR file. They are converted after ITU-R BT.2408 (BT.709 → BT.2020, SDR white at the 203 cd/m² reference, 75 % HLG),
  so they sit at the right brightness and colour next to the graded clips (the known issue of #6 in Klipp's alpha 233).
- Export → Dolby Vision · keep HDR: Dolby Vision clips (their HLG base) cut and edited without a grade come out as
  Dolby Vision again, with new dynamic metadata made by the phone's encoder from the exported frames.
- Export → Quality: bitrates above the encoder's listed limit (on by default, "Above it (Klipp Lab)"). The phone's
  media_codecs XML lists 160 Mbps for the Dolby Vision encoder (HEVC 100-190), and Media3 cut the bitrate to it, but the
  encoders take more – George's mods record Dolby Vision at 200-240 Mbps. The chosen bitrate now goes to the encoder as
  it is (up to 300 Mbps); "Encoder's listed limit" keeps the old behaviour, and the summary shows "(listed 160)" when
  above it. For Dolby Vision use CBR: in VBR the encoder spent 31 Mb/s on a calm 4K60 clip, in CBR 125 Mb/s.
- EI per clip (Color → the "EI" chip before the nodes, or the grid → Clip grade): the clip's log curve, the EI it was
  shot at and the EI it is rated at. Applied before all the clip's nodes: the log is decoded, the light scaled by
  rated / shot (as rating a cinema camera's log footage) and encoded again, so every node, LUT and OpenDRT gets the
  rated footage. Shows where the sensor clips: with George's camera mods 0.0072 × EI scene linear, +5 stops over 18 %
  grey at EI 800, +2.7 at EI 160. "Same EI for all clips" copies it to the timeline's other clips. The 0.0072 × EI
  relation is from George Kiarie's RawViewer.
- Export → Dolby Vision · OpenDRT (on phones with a Dolby Vision encoder, e.g. the 17 Ultra's c2.qti.dv.encoder):
  the same HLG as "HLG · OpenDRT", encoded as Dolby Vision profile 8.4. The encoder makes the dynamic metadata from
  the exported frames, so edited and mixed clips get metadata that matches what is in the file; plays as HLG where
  there is no Dolby Vision. The approach follows Dolby's dolby-vision-editor sample (BSD-3-Clause-Clear); the
  encoding itself is Media3's.

Import and timeline
- MCRAW test (Settings → video tab): pick a MotionCam .mcraw file; it is read with MotionCam's own decoder library
  (motioncam-decoder, Apache-2.0) and shows how fast the phone decodes it (one and four threads), the file's metadata
  and a quick preview of the middle frame. First step towards importing .mcraw; the log goes to
  Android/data/x17u.klipp.editor/files/mcraw_probe.txt.
- MCRAW → S-Log3 (in the MCRAW test's result: Convert to S-Log3): develops the .mcraw on the GPU – the frame's own
  black level, MotionCam's lens shading map, as-shot white balance, demosaic, the camera's colour matrix, S-Gamut3 and
  S-Log3, the sensor's clip at EI 800 – into a 10-bit HEVC clip in Movies/Klipp Lab (BT.2020, SDR transfer tag,
  limited range, so Klipp edits it as log), with the file's sound as AAC, timed.
- Import a MotionCam .mcraw: Import → Files app now lists .mcraw files too; a picked one is developed to S-Log3 /
  S-Gamut3.Cine (as above, with a progress dialog) and the clip goes in with the rest – Klipp's default input, so no
  setting to change.
- MCRAW: the padding MotionCam leaves below the picture (a 4096×3072 frame holding a 4096×2560 picture, the rest at
  the black level) is left out: the clip is the picture's own size, without the black band at the bottom.
- Video picker → MotionCam RAW: the .mcraw files of MotionCam's folder (picked once, Documents/MotionCam by default)
  with a preview of the middle frame, size, frame rate, length and date; picked with the videos and developed to S-Log3
  on import. "Develop at EI" 400 / 800 / 1600 / 3200 sets where the sensor's clip lands (as on a log camera).
- MCRAW develops live in the app's own storage (not the gallery): a .mcraw imported again at the same EI reuses its
  develop, and a develop no project uses any more is removed when a project is opened or deleted. Settings → Playback
  shows their size, with Clear, and they count in the "Klipp uses" total above it.
- MCRAW → HLG without setting up OpenDRT: with MotionCam clips on the timeline, HLG · OpenDRT (and Dolby Vision ·
  OpenDRT) can be chosen in the export even when they have no OpenDRT yet; exporting adds it to their grades (on
  the last node when it does nothing yet, else a node of its own), with the develop's curve as its input – one undo
  step, and the Color page then shows it.
- MCRAW white balance in the develop: on a MotionCam clip, Develop puts the white balance set live in the clip's input
  into the develop – as camera gains on the raw, before the demosaic and the neutral highlight clip, so clipped
  highlights stay white – and the live one goes back to As shot. Kept through later develops (EI, output).
- MCRAW Auto EI (now the default, in the picker and the RAW settings): measures the clip's middle frame and picks
  the EI (one-third stops, 100–3200) that brings its average to 18 % grey – for import-and-export without choosing.
- Long-press help across the app: holding a control still explains what it does – the sliders (by their name),
  drop-down fields, dock actions, the tool rails, and on the Color page the EI chip, + node, Compare, Scope, the
  wheel modes and ranges, White balance and Copy grade: Color (primaries, Log ranges, qualifier, window, LUT, OpenDRT
  grey, film), the clip's input and white balance, MotionCam RAW, Properties (transform, crop, key, mask), Export,
  the project and Settings fields, and the timeline's actions. A tap or a drag works as before (the controls take it
  as their own long click, as Android's). (English for now.)
- MotionCam RAW settings, changeable at any time (Color → the EI chip → MotionCam RAW): "Develop at EI" and Output
  (any of Klipp's log curves and gamuts – S-Log3 / S-Gamut3.Cine, LogC3/LogC4, V-Log, DaVinci Intermediate, ACEScct …).
  Develop makes a new develop from the .mcraw in the background; the clip switches to it when done, keeps its grade,
  and its input and OpenDRT nodes follow the new curve. The picker's MotionCam RAW chip also has "Develop into".
- White balance in the clip's input (Color → the EI chip): Temperature (2000–12000 K, "As shot" in the middle) and
  Tint, applied in the linear light on the clip's own primaries before every node – as a raw developer's, live while
  dragging. Works on any log clip; for a MotionCam develop it moves away from the as-shot white. The chip shows "· WB".
- Storage warning: Settings → Playback → "Warn when Klipp Lab uses more than" 5 / 10 / 20 / 50 GB (20 by default, or
  off) – opening a project then says how much the proxies, cache, renders, MCRAW develops and freeze frames take.
- MCRAW: Malvar–He–Cutler demosaic (gradient-corrected, 5×5) in place of the plain bilinear one – sharper fine
  detail and fewer colour fringes on edges.
- Close gaps: hold a track's header → Close gaps moves every clip of the track back to the end of the one before (linked
  sound or picture with it; the space before the first clip stays), one undo. A selected clip has Close gap next to
  Delete: it moves back to the end of the clip before it (or to the start), the clips after it with it; nothing is
  deleted.
- Klipp Lab's own video picker: the phone's videos newest first, by folder, with previews of 10-bit log video (the
  system pickers show none), real file names, duration, resolution and date; hidden and ".pending" files left out.
  Picked in order, numbered. "Files app" opens the system picker for anything else. Asks for the videos read
  permission.
- The system picker, when used, opens in DCIM/Camera and clips keep their real names (the photo picker hid them).
- Imported clips go straight to the end of the timeline (Settings → Timeline, on by default here), in the order
  picked, or in name order (shooting order) from the files app.
- Media panel: with clips selected, "Add N to the timeline, in this order", each selected card numbered.
- Timeline selection box (hold on an empty area and drag): near the left or right edge the timeline scrolls on its
  own, so the box reaches clips off screen.
- Speed → Slow-mo (e.g. "Slow-mo 120→24", a conform): for a clip shot at a higher frame rate than the project's, sets the
  speed so every source frame is used once (120 → 24 fps = 20 %, 5× slower), no frame repeated or dropped. "≈" when
  the rates don't divide into a whole percent (120 → 25). Idea from George Kiarie's RawViewer.

Layout
- Full screen: status and navigation bars hidden (swipe from the edge to see them); the top bar moves up to just
  under the camera hole. Settings → Appearance.
- Interface size: Normal (Klipp's), Compact (default here) or Small. Settings → Appearance.
- Edit, Color and Audio always show their names.
- The monitor fits the project's shape (16:9, 4:3, vertical…), a height set by hand is kept per shape.
- Landscape: drag the grip between the picture and the timeline (30-75 %), double tap = back.

Projects
- Select several projects (✓ in the header, or "Select several" in a project's menu) and delete them at once.

Offered to Klipp (pull requests)
- #4 files without a transfer keep their matrix and range, #5 the LUT browser, #6 HLG from OpenDRT, #7 clips keep
  their names, #8 adding clips to the timeline (off by default there).

## 0.21.0-beta.1-17u.1

Based on Klipp 0.21.0-beta.1.

New
- HLG export ("HLG · OpenDRT"): HEVC Main10, tagged BT.2020 / HLG / limited range. The grade's OpenDRT renders with
  OpenDRT's own display encoding "Rec.2100 - HLG / Rec.2020 (P3 Limited)" (1000 nits peak) instead of its SDR one, so
  a log clip graded through OpenDRT comes out as HLG without a second grade.
- The HLG option is offered when, in every graded clip, the last active node uses OpenDRT with no film, no qualifier
  (key) and the node's mix at 100%. Otherwise the export menu says why it isn't available.

Fixes
- Video files with no transfer characteristic in their colour tags (for example full-range BT.2020 S-Log3 from some
  camera mods) keep their own matrix and range. Media3 read them as BT.709 limited range, which stretched the levels
  (Y = 1.16 x source - 74 in 10 bit) in playback and export.

App
- Own app id (x17u.klipp.editor) and name (Klipp Lab): installs beside Klipp. The two apps don't share projects.
  Version code: Klipp's x 100 + the fork's release number.

Credits
- The HLG encoding (the P3-limited Rec.2020 matrices, white point normalisation and the inverse HLG EOTF) is ported from
  OpenDRT.dctl v1.1.0 by Jed Smith (GPL-3), the same source as Klipp's existing OpenDRT port.
