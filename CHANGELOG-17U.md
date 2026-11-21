# Klipp 17U - changelog

Klipp 17U is a fork of [Klipp](https://github.com/Flabbjaxaren/klipp) by Flabbjaxaren (GPL-3.0-only), kept on the
`xiaomi-17u` branch of [utalpt/klipp](https://github.com/utalpt/klipp). Each version is Klipp's version plus the
fork's release number (`0.21.0-beta.1-17u.1` = Klipp 0.21.0-beta.1, fork release 1). Everything in Klipp's own
changelog for that version applies too; this file lists only what the fork adds or changes.

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
- Own app id (x17u.klipp.editor) and name (Klipp 17U): installs beside Klipp. The two apps don't share projects.
  Version code: Klipp's x 100 + the fork's release number.

Credits
- The HLG encoding (the P3-limited Rec.2020 matrices, white point normalisation and the inverse HLG EOTF) is ported from
  OpenDRT.dctl v1.1.0 by Jed Smith (GPL-3), the same source as Klipp's existing OpenDRT port.
