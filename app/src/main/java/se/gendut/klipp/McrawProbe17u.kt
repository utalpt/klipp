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
    var lastUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var converting by remember { mutableStateOf(false) }
    var converted by remember { mutableStateOf<se.gendut.klipp.engine.McrawConvert.Result?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        running = true; progress = 0f; lastUri = uri
        scope.launch {
            result = withContext(Dispatchers.Default) { Mcraw.probe(ctx, uri) { progress = it } }
            running = false
        }
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("MCRAW test (Klipp Lab)", color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if (converting) "Converting to S-Log3… ${(progress * 100).toInt()} %" else if (running) "Decoding… ${(progress * 100).toInt()} %" else "Pick a MotionCam .mcraw file: how fast this phone decodes it, and a preview of one frame",
                color = K.OnSurfaceVar, fontSize = 12.sp)
        }
        FlatTextButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !running && !converting, modifier = Modifier.testTag("set:mcrawProbe")) { Text("Pick file") }
    }
    result?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { result = null },
            confirmButton = { FlatTextButton(onClick = { result = null }) { Text("OK") } },
            // develop the file into an S-Log3 clip (Movies/Klipp Lab) and time it
            dismissButton = {
                FlatTextButton(onClick = {
                    val uri = lastUri ?: return@FlatTextButton
                    result = null; converting = true; progress = 0f
                    scope.launch {
                        converted = withContext(Dispatchers.Default) {
                            se.gendut.klipp.engine.McrawConvert.convert(ctx, uri) { progress = it }.also { r ->
                                runCatching { java.io.File(ctx.getExternalFilesDir(null), "mcraw_convert.txt").writeText(r.summary + "\n" + r.log) }
                            }
                        }
                        converting = false
                    }
                }, modifier = Modifier.testTag("set:mcrawConvert")) { Text("Convert to S-Log3") }
                // a half-size draft (no demosaic): how fast a playback develop can be
                FlatTextButton(onClick = {
                    val uri = lastUri ?: return@FlatTextButton
                    result = null; converting = true; progress = 0f
                    scope.launch {
                        converted = withContext(Dispatchers.Default) {
                            se.gendut.klipp.engine.McrawConvert.convert(ctx, uri, draft = true) { progress = it }.also { r ->
                                runCatching { java.io.File(ctx.getExternalFilesDir(null), "mcraw_convert.txt").writeText(r.summary + "\n" + r.log) }
                            }
                        }
                        converting = false
                    }
                }, modifier = Modifier.testTag("set:mcrawDraft")) { Text("Draft") }
            },
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
    converted?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { converted = null },
            confirmButton = { FlatTextButton(onClick = { converted = null }) { Text("OK") } },
            title = { Text("MCRAW → S-Log3") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(r.summary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(r.log, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            },
        )
    }
}

/** Klipp Lab: Settings → Storage, under the storage summary: the MotionCam develops and the warning limit. */
@Composable
fun Storage17uRows(ctx: Context) {
    val scope = rememberCoroutineScope()
    var mcraw by remember { mutableStateOf(0L) }
    var rev by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(rev, se.gendut.klipp.engine.McrawStore17u.revision) { mcraw = withContext(Dispatchers.IO) { se.gendut.klipp.engine.McrawStore17u.bytes(ctx) } }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("MotionCam RAW develops", color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("%.1f GB · made when a .mcraw is imported; removed on their own when no project uses them. Clear: they are made again only by importing the .mcraw again.".format(mcraw / 1e9),
                color = K.OnSurfaceVar, fontSize = 12.sp)
        }
        FlatTextButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { se.gendut.klipp.engine.McrawStore17u.clearAll(ctx) }
                rev++
            }
        }, modifier = Modifier.testTag("set:clearMcraw")) { Text("Clear") }
    }
    var lim by remember { mutableStateOf(se.gendut.klipp.engine.StorageWarn17u.limitGb(ctx)) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Warn when Klipp Lab uses more than", color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("Checked when a project is opened (proxies, cache, renders, MCRAW, freeze frames).", color = K.OnSurfaceVar, fontSize = 12.sp)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            items(se.gendut.klipp.engine.StorageWarn17u.LIMITS_GB.size) { i ->
                val v = se.gendut.klipp.engine.StorageWarn17u.LIMITS_GB[i]
                ToggleChip(if (v == 0) "Off" else "$v GB", lim == v, { lim = v; se.gendut.klipp.engine.StorageWarn17u.setLimitGb(ctx, v) }, Modifier.testTag("set:warnGb$v"))
            }
        }
    }
}

/** Klipp Lab: Settings → Storage – the proxy files: their size and Clear (made again in the background when needed). */
@Composable
fun Proxy17uRow(ctx: Context) {
    val p = se.gendut.klipp.engine.Proxies
    var size by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(p.revision) { size = withContext(Dispatchers.IO) { p.size(ctx) } }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Proxies", color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("%.1f GB · lighter copies for playback; made again in the background when needed (Settings → Playback).".format(size / 1e9),
                color = K.OnSurfaceVar, fontSize = 12.sp)
        }
        FlatTextButton(onClick = { p.clear(ctx); size = 0 }, modifier = Modifier.testTag("set:clearProxies")) { Text("Clear") }
    }
}

/**
 * Klipp Lab: Settings → Storage – clears what is made again by itself (proxies, node cache, renders), after asking.
 * MotionCam develops are not touched: they are made again only by importing the .mcraw.
 */
@Composable
fun Clear17uAll(ctx: Context) {
    var ask by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Clear caches", color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Proxies, node cache and renders at once – all made again when needed. MotionCam develops stay.", color = K.OnSurfaceVar, fontSize = 12.sp)
        }
        FlatTextButton(onClick = { ask = true }, modifier = Modifier.testTag("set:clearAll")) { Text("Clear") }
    }
    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Clear caches?") },
        text = { Text("Proxies, node cache and renders are deleted. Playback may be slower until the proxies are made again.") },
        confirmButton = {
            FlatTextButton(onClick = {
                ask = false
                se.gendut.klipp.engine.Proxies.clear(ctx)
                se.gendut.klipp.engine.NodeCache.clear(ctx)
                se.gendut.klipp.engine.RenderCache.clear()
            }, modifier = Modifier.testTag("set:clearAllYes")) { Text("Clear") }
        },
        dismissButton = { FlatTextButton(onClick = { ask = false }) { Text("Cancel") } },
        containerColor = K.SurfaceHigh,
    )
}
