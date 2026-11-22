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
