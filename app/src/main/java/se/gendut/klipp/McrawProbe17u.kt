package se.gendut.klipp

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.Mcraw

/**
 * Klipp Lab: Settings → the MCRAW test (MotionCam RAW). Pick a .mcraw file; the result shows how fast the phone
 * decodes it and a quick preview of the middle frame. The full log is in Android/data/<app>/files/mcraw_probe.txt.
 */
@Composable
fun McrawProbeRow(ctx: Context) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<Mcraw.Probe?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        running = true; progress = 0f
        scope.launch {
            result = withContext(Dispatchers.Default) { Mcraw.probe(ctx, uri) { progress = it } }
            running = false
        }
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("MCRAW test (Klipp Lab)", color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if (running) "Decoding… ${(progress * 100).toInt()} %" else "Pick a MotionCam .mcraw file: how fast this phone decodes it, and a preview of one frame",
                color = K.OnSurfaceVar, fontSize = 12.sp)
        }
        FlatTextButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !running, modifier = Modifier.testTag("set:mcrawProbe")) { Text("Pick file") }
    }
    result?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { result = null },
            confirmButton = { FlatTextButton(onClick = { result = null }) { Text("OK") } },
            title = { Text("MCRAW test") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    r.preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
                    Text(r.summary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    r.logFile?.let { Text("Log: ${it.absolutePath}", fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
                    Text(r.log, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            },
        )
    }
}
