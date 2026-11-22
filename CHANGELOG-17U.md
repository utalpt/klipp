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

- OpenDRT node: "Shot at EI" and "Rated at EI" for log footage. Rating at another EI scales the linear light by
  rated / shot, as rating a cinema camera's log footage; with an EI set the node shows where the sensor clips (for
  George's camera mods 0.0072 × EI scene linear: +5 stops over 18 % grey at EI 800). The 0.0072 × EI relation is from
  George Kiarie's RawViewer.
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
