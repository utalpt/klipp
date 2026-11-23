package se.gendut.klipp

import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.McrawPlayer17u
import se.gendut.klipp.engine.OpenDrt

/**
 * Klipp Lab: Settings → MCRAW test → Play: a .mcraw played in real time from the file (phase 1 of RAW in the
 * timeline, see McrawPlayer17u) – play/pause, seek, EI and white balance live, Rec.709 (to look at) or S-Log3, and
 * what the phone keeps up with.
 */
@Composable
fun McrawPlayerScreen17u(uri: Uri, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember(uri) { McrawPlayer17u(ctx.applicationContext, uri) }
    var out by remember { mutableStateOf(OpenDrt.Input.REC709.key) }
    var surface by remember { mutableStateOf<Triple<android.view.Surface, Int, Int>?>(null) }
    LaunchedEffect(surface, out) { surface?.let { (s, w, h) -> withContext(Dispatchers.Default) { player.start(s, w, h, out) } } }
    DisposableEffect(player) { onDispose { player.stop() } }
    var pos by remember { mutableFloatStateOf(0f) }
    var stats by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var ei by remember { mutableIntStateOf(800) }
    var temp by remember { mutableIntStateOf(0) }
    var tint by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }
    // the EI Auto would pick, to start with
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) { se.gendut.klipp.engine.McrawConvert.autoEi(ctx, uri) }?.let { ei = it; player.ei = it }
    }
    LaunchedEffect(player) {
        while (true) {
            if (!seeking) pos = player.position
            stats = player.stats; playing = player.playing
            delay(100)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeArea17u().testTag("mcrawPlayer")) {
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {}
                            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) { surface = Triple(h.surface, w, hh) }
                            override fun surfaceDestroyed(h: SurfaceHolder) { player.stop(); surface = null }
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Text(stats, color = K.OnSurfaceVar, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Slider(value = pos, onValueChange = { seeking = true; pos = it; player.seekTo = it }, onValueChangeFinished = { seeking = false },
                modifier = Modifier.padding(horizontal = 12.dp).testTag("mcrawPlayer:seek"))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (playing) "Pause" else "Play", { player.playing = !player.playing }, filled = true, modifier = Modifier.testTag("mcrawPlayer:play"))
                Spacer(Modifier.width(10.dp))
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf(OpenDrt.Input.REC709 to "Rec.709", OpenDrt.Input.SLOG3 to "S-Log3")) { (i, label) ->
                        ToggleChip(label, out == i.key, { out = i.key })
                    }
                }
                FlatTextButton(onClick = onClose) { Text("Close") }
            }
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                item { Text("EI", color = K.OnSurfaceVar, fontSize = 13.sp) }
                items(listOf(400, 800, 1600, 3200, 6400)) { v -> ToggleChip("$v", ei == v, { ei = v; player.ei = v }) }
            }
            Text(if (temp == 0) "Temperature: as shot" else "Temperature: $temp K", color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Slider(value = if (temp == 0) 6500f else temp.toFloat(), valueRange = 2000f..12000f,
                onValueChange = { v -> temp = ((v / 50).toInt() * 50).let { if (it == 6500) 0 else it }; player.temp = temp },
                modifier = Modifier.padding(horizontal = 12.dp))
            Text("Tint: $tint", color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Slider(value = tint.toFloat(), valueRange = -100f..100f, onValueChange = { tint = it.toInt(); player.tint = tint },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp).padding(bottom = 8.dp))
        }
    }
}
