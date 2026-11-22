package se.gendut.klipp

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Klipp Lab: long-press help. A long press on a control shows what it does; a tap or a drag works as before. Clickable
 * controls take it as their own long click (combinedClickable), labels through [help17u]. The text shows in
 * [Help17uHost] (one, at the app's root).
 */
object Help17u {
    var shown by mutableStateOf<Pair<String, String>?>(null)
}

/** Long press on a label (a text, a slider's name): shows [text]. Clickable controls use [onLongHelp17u] instead. */
fun Modifier.help17u(title: String, text: String): Modifier = this.pointerInput(title, text) {
    detectTapGestures(onLongPress = { Help17u.shown = title to text })
}

/** For a control's own combinedClickable: the long click that shows the help, or null when there is none. */
fun onLongHelp17u(title: String, text: String?): (() -> Unit)? = text?.let { t -> { Help17u.shown = title to t } }

@Composable
fun Help17uHost() {
    val h = Help17u.shown ?: return
    AlertDialog(
        onDismissRequest = { Help17u.shown = null },
        confirmButton = { FlatTextButton({ Help17u.shown = null }) { Text("OK") } },
        title = { Text(h.first) },
        text = { Text(h.second) },
    )
}

/** The help texts (English for now). [of]: by a control's test tag (sliders, drop-down fields, dock actions). */
object Help17uText {
    fun of(tag: String?): String? = tag?.let { BY_TAG[it] ?: BY_TAG[it.substringBefore('_') + "_"] }

    private val BY_TAG: Map<String, String> = mapOf(
        // clip input (Color → the EI chip)
        "clipInput:curve" to "The log curve and gamut the clip was recorded in. The EI and white balance here decode it, " +
            "change the light and encode it again on the same curve, before every node.",
        "clipInput:shot" to "The EI the clip was shot at (its log's own exposure index). Needed for Rated at EI.",
        "clipInput:rated" to "Rate the footage at another EI, as on a cinema camera: the linear light is scaled by rated / shot " +
            "before the nodes – a brighter or darker picture with the same highlights' room in the file.",
        "clipInput:temp" to TEMP, "clipInput:tint" to TINT,
        "raw:out" to OUT, "mcraw:out" to OUT,
        // the Color page: the clip's input, nodes, viewer, tools
        "clipInput" to "The clip's input, before every node: the log curve it was shot in, its EI and rating, white balance " +
            "and, for a MotionCam clip, its RAW develop.",
        "addNode" to "Adds a node after the last one. Nodes are applied left to right, each on the result of the one before.",
        "colorCompare" to "Split screen against a still from the gallery or the clip without its grade; drag the line.",
        "scopes" to "Waveform, RGB parade or vectorscope over the picture.",
        "editor:Hjul" to "Colour wheels: Primaries (Lift, Gamma, Gain, Offset) or Log wheels (shadows, midtones, highlights by range).",
        "editor:Kurvor" to "Curves: the tone curve and hue/saturation curves.",
        "editor:Justera" to "Contrast, pivot, saturation, hue, temperature, tint and the node's mix.",
        "editor:LUT" to "A LUT on this node, or OpenDRT to turn log into a display image (needed for HLG and Dolby Vision export).",
        "editor:Kvalificerare" to "Qualifier: picks a part of the picture by hue, saturation and brightness, so the node only changes that part.",
        "editor:Fönster" to "Window: limits the node to a shape (circle, rectangle …), which can be tracked.",
        "editor:Film" to "Film emulation (Spektrafilm): a film stock and print paper, with halation, grain and the rest.",
        "wheels:0" to "Primaries: Lift (darks), Gamma (midtones), Gain (brights) and Offset (everything) – they overlap, as in DaVinci.",
        "wheels:1" to "Log wheels: shadows, midtones and highlights, each only within its range (Low range / High range below).",
        "wheels:reset" to "Resets all the wheels of this mode on this node.",
        "wheels:wb" to "Tap something in the picture that should be neutral (white or grey): the node is balanced so it is.",
        "wheels:copy" to "Copies this clip's grade; on another clip the button becomes Paste grade.",
        "range:Lift" to "Lift: moves the darks the most, the brights hardly at all. The puck: colour; the slider under: brightness.",
        "range:Gamma" to "Gamma: moves the midtones; blacks and whites stay where they are.",
        "range:Gain" to "Gain: scales the image – the brights move the most, black stays black.",
        "range:Offset" to "Offset: moves every tone by the same amount (in log, the same as changing exposure).",
        "range:LogShadow" to "Shadows: only the tones below Low range.",
        "range:LogMid" to "Midtones: only the tones between Low range and High range.",
        "range:LogHi" to "Highlights: only the tones above High range.",
        // primaries
        "temp" to "Warmer (+) or cooler (−) on this node, after the clip's input.",
        "tint" to "Magenta (+) or green (−) on this node.",
        "contrast" to "Spreads the tones away from the pivot (above 1) or pulls them towards it (below 1).",
        "pivot" to "The tone contrast turns around: it stays where it is when contrast changes.",
        "sat" to "Colour intensity of this node. 50 = unchanged.",
        "hue" to "Turns every hue around the colour wheel.",
        "mix" to "How much of this node is applied: 100 % = all of it, 0 = none.",
        "denoise" to "Reduces noise on this node. Stronger = smoother, but fine detail goes too.",
        "logLow" to "Where the Log wheels' shadows end: below this tone the Shadow wheel works.",
        "logHigh" to "Where the Log wheels' highlights start: above this tone the Highlight wheel works.",
        "master" to "The wheel's brightness (luma) – the ring under the colour.",
        // OpenDRT
        "drtGrey" to "How bright 18 % grey is shown on the display, in nits. Higher = a brighter picture overall.",
        // LUT, qualifier, window, stabilization, motion blur
        "nodeLutMix" to "How strongly the node's LUT is applied: 100 % = the LUT as it is.",
        "qHue" to "The hue the qualifier picks.", "qHueW" to "How wide a range of hues around it is picked.",
        "qLumLo" to "The darkest tone that is picked.", "qLumHi" to "The brightest tone that is picked.",
        "qSatLo" to "The least saturated colour that is picked.", "qSatHi" to "The most saturated colour that is picked.",
        "qSoft" to "Softens the qualifier's edges so the change fades in instead of cutting.",
        "wX" to "The window's centre, across.", "wY" to "The window's centre, up and down.",
        "wRx" to "The window's width.", "wRy" to "The window's height.", "wAngle" to "Turns the window.",
        "wSoft" to "Softens the window's edge.",
        "wBlur" to "How strongly the inside of the window is blurred.", "wPix" to "The size of the pixelation's cells.",
        "stabZoom" to "How far stabilization may zoom in to hide the moving edges. More = steadier, but more of the picture is cut.",
        "mblurShutter" to "Motion blur as a shutter angle: 180° is the usual film look, 360° the longest blur.",
        // film (Spektrafilm)
        "filmEv" to "Exposure of the film negative, in stops.",
        "filmPrint" to "Exposure of the print, as when printing in a darkroom: brighter or darker print.",
        "filmGamma" to "The negative's contrast.", "filmPrintGamma" to "The print paper's contrast.",
        "filmM" to "Enlarger magenta filter: shifts the print's colour between magenta and green.",
        "filmY" to "Enlarger yellow filter: shifts the print's colour between yellow and blue.",
        "filmDir" to "DIR couplers: the film's layers hold each other back – more saturation and edge contrast.",
        "filmHal" to "Halation: red glow around bright lights, from light reflected in the film base.",
        "filmHalSize" to "How far the halation spreads.",
        "filmScatter" to "Light scattered inside the film: a soft glow on bright areas.", "filmScatterSize" to "How far it spreads.",
        "filmGrain" to "The size of the film's grain.", "filmGrainAmount" to "How strong the grain is.",
        "filmGrainBlur" to "Softens the grain.", "filmGrainColor" to "How coloured the grain is (0 = grey grain).",
        "filmGlare" to "Glare: a veil of stray light over the print.", "filmGlareRough" to "How uneven the glare is.",
        "filmLens" to "Lens softness of the camera.", "filmScanBlur" to "Softness of the scan.", "filmSharp" to "Sharpening of the scan.",
        // properties
        "opacity" to "How see-through the clip is: 100 % = solid.", "scale" to "Size of the clip in the frame.",
        "rotation" to "Turns the clip.", "x" to "Position across.", "y" to "Position up and down.",
        "cropL" to "Cuts from the left edge.", "cropR" to "Cuts from the right edge.", "cropT" to "Cuts from the top.", "cropB" to "Cuts from the bottom.",
        "cropFeather" to "Softens the crop's edges.", "maskFeather" to "Softens the mask's edge.",
        "keyTol" to "How close to the key colour a pixel must be to be made transparent.",
        "keySoft" to "Softens the key's edge: a gradual fade instead of a hard cut.",
        "keySpill" to "Removes the key colour reflected onto the subject (green or blue edges).",
        "volume" to "The clip's loudness.",
        // export
        "exp:preset" to "Ready settings for a target. Changing a setting below makes it Custom.",
        "exp:res" to "The size of the exported video.", "exp:fps" to "Frames per second of the export.",
        "exp:codec" to "The video format. HEVC (H.265) is smaller for the same quality and needed for 10-bit, HLG and HDR; H.264 plays everywhere.",
        "exp:bits" to "8-bit for SDR everywhere; 10-bit for log, HLG and HDR, or to avoid banding in gradients.",
        "exp:q" to "Bitrate: higher = better quality and a bigger file.",
        "exp:key" to "How often a full frame is stored. Shorter = faster seeking and editing later, slightly bigger file.",
        "exp:akbps" to "Audio bitrate.", "exp:sr" to "Audio sample rate.", "expBitrate" to "The video bitrate, in Mb/s.",
        // settings and the project
        "set:toneMap" to "How HDR clips are shown in an SDR project: tone-mapped to SDR.",
        "set:previewShort" to "The resolution the preview plays at. Lower = smoother playback; the export is not affected.",
        "set:renderFull" to "The resolution of background renders (layers, effects, transitions).",
        "fmt" to "The project's frame shape and size.", "fps" to "The project's frame rate: clips at other rates are converted to it.",
        "proxySize" to "Size of the lighter proxy files used for playback; the export always uses the originals.",
        "proxyQuality" to "Bitrate of the proxy files.", "proxyBits" to "Bit depth of the proxy files.",
        // dock
        "add:media" to "Add videos, photos or MotionCam RAW to the timeline.", "add:title" to "Add a text title.",
        "add:captions" to "Make captions from the speech, or add them by hand.", "add:sound" to "Add music or sound.",
        "add:voice" to "Record a voice-over at the playhead.",
        "ctx:split" to "Cut the clip at the playhead.", "ctx:delete" to "Remove the clip (the gap stays: Close gap moves the rest back).",
        "ctx:closeGap" to "Moves the clip back to the end of the clip before it, closing the empty space; linked clips follow.",
        "ctx:copy" to "Copy the clip.", "ctx:speed" to "Play the clip faster or slower.", "ctx:volume" to "The clip's loudness.",
        "ctx:picture" to "Crop, position, key, mask, blend and stabilization of the clip.",
        "ctx:power" to "Turn the clip off without removing it (it is skipped in playback and export).",
        "ctx:label" to "Give the clip a colour label.", "ctx:more" to "More actions for the clip.",
    )

    const val EI = "Where the sensor's clip lands in the log file, as the EI of a cinema camera. It changes only how bright " +
        "the picture is and how much room the highlights get – nothing is lost either way, the RAW is not touched. " +
        "Higher EI = brighter picture. Auto measures the clip and picks the EI that puts its average at 18 % grey: " +
        "the one to use when you just want to import and export."
    const val OUT = "The log curve and gamut of the developed clip on the timeline (S-Log3 / S-Gamut3.Cine by default). " +
        "Pick the one your LUTs or your grading habits expect. It is not the export's format: Rec.709, HLG or PQ are " +
        "chosen when exporting, from any of these."
    const val DEVELOP = "Develops the .mcraw again with these settings, in the background (about 1.5–2× the clip's length). " +
        "A white balance set live above goes into the develop, on the raw before the highlights are clipped (clipped " +
        "highlights stay white), and the live one goes back to As shot. " +
        "The clip switches to the new develop when it is done and keeps its grade; its input and OpenDRT nodes follow " +
        "the new curve. The old develop is removed when no project uses it."
    const val RAW = "This clip is a develop of a MotionCam .mcraw. The EI and the output are baked into the develop and " +
        "can be changed here at any time (a new develop). White balance and the rated EI above are live, on top of it."
    const val TEMP = "White balance in the linear light, before the nodes, as in a raw developer. As shot = the clip as it " +
        "is. Lower K = the light was warmer = a cooler picture. Double tap: back to as shot. On a MotionCam clip, " +
        "Develop (below) puts it into the develop, where clipped highlights stay white."
    const val TINT = "Green (−) to magenta (+), in the linear light before the nodes. Double tap: back to 0."
}
