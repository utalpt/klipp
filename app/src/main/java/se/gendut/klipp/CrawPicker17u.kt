package se.gendut.klipp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.CrawImport

/**
 * Klipp Lab: Cinematic RAW Studio (.CRAW, matthew777777's app) in the video picker, as MotionCam RAW: the .CRAW files of
 * one folder (picked once – the app records where you choose, Download/RawLens by default), each with a preview of its
 * middle frame, length and size; picked like the videos, and developed on import with the settings asked then
 * (CrawDevelopDialog).
 */
object CrawFolder17u {
    private fun p(c: Context) = c.getSharedPreferences("klipp17u_craw", Context.MODE_PRIVATE)
    fun tree(c: Context): Uri? = p(c).getString("tree", null)?.let(Uri::parse)
    fun setTree(c: Context, u: Uri) { p(c).edit().putString("tree", u.toString()).apply() }

    fun list(c: Context): List<McrawFolder17u.File> {
        val tree = tree(c) ?: return emptyList()
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<McrawFolder17u.File>()
        runCatching {
            c.contentResolver.query(kids, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ), null, null, null)?.use { k ->
                while (k.moveToNext()) {
                    val name = k.getString(1) ?: continue
                    if (!name.endsWith(".craw", true)) continue
                    out += McrawFolder17u.File(DocumentsContract.buildDocumentUriUsingTree(tree, k.getString(0)), name, k.getLong(2), k.getLong(3))
                }
            }
        }
        return out.sortedByDescending { it.time }
    }
}

@Composable
fun ColumnScope.CrawPanel17u(picked: List<Uri>, onList: (List<Uri>) -> Unit = {}, onToggle: (Uri) -> Unit) {
    val ctx = LocalContext.current
    var tree by remember { mutableStateOf(CrawFolder17u.tree(ctx)) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            CrawFolder17u.setTree(ctx, u); tree = u
        }
    }
    val startAt = remember { Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FRawLens") }
    if (tree == null) {
        Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Pick the folder Cinematic RAW Studio records into (its own setting; Download/RawLens by default): its .CRAW files show here. On import you choose the log and EI they are developed into.",
                color = K.OnSurfaceVar, fontSize = 15.sp)
            PillButton("Choose folder", { pickFolder.launch(startAt) }, filled = true, modifier = Modifier.testTag("craw:folder"))
        }
        return
    }
    val files by produceState<List<McrawFolder17u.File>?>(null, tree) { value = withContext(Dispatchers.IO) { CrawFolder17u.list(ctx) } }
    val list = files
    LaunchedEffect(list) { onList(list.orEmpty().map { it.uri }) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tree?.lastPathSegment?.substringAfterLast(':')?.ifEmpty { null } ?: "Cinematic RAW", color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.weight(1f))
        FlatTextButton(onClick = { pickFolder.launch(startAt) }) { Text("Other folder", fontSize = 12.sp) }
    }
    when {
        list == null -> Box(Modifier.fillMaxWidth().weight(1f))
        list.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp)) { Text("No .CRAW files in this folder.", color = K.OnSurfaceVar, fontSize = 15.sp) }
        else -> LazyVerticalGrid(
            GridCells.Adaptive(150.dp), Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.uri.toString() }) { f -> CrawCard(f, picked.indexOf(f.uri) + 1) { onToggle(f.uri) } }
        }
    }
}

private object CrawThumbs { val cache = mutableStateMapOf<String, CrawImport.Thumb>() }

@Composable
private fun CrawCard(f: McrawFolder17u.File, order: Int, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val key = f.uri.toString()
    val thumb = CrawThumbs.cache[key]
    LaunchedEffect(key) {
        if (CrawThumbs.cache[key] == null) withContext(Dispatchers.IO) { CrawImport.thumb(ctx, f.uri)?.let { CrawThumbs.cache[key] = it } }
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.clip(shape).background(K.SurfaceHigh)
            .then(if (order > 0) Modifier.border(3.dp, K.Primary, shape) else Modifier)
            .clickable(onClick = onClick).testTag("craw:${f.name}")
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(K.Bg)) {
            thumb?.bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop) }
            thumb?.let {
                Text(
                    durText(it.seconds), color = K.OnSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(K.Bg.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text("CRAW", color = K.OnPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(K.Primary).padding(horizontal = 6.dp, vertical = 2.dp))
            if (order > 0) Box(
                Modifier.align(Alignment.Center).size(30.dp).clip(RoundedCornerShape(15.dp)).background(K.Primary),
                contentAlignment = Alignment.Center,
            ) { Text("$order", color = K.OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }
        Text(f.name, color = K.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp))
        val meta = listOfNotNull(
            thumb?.let { "${it.width}×${it.height} · ${"%.0f".format(it.fps)} fps" },
            "%.1f GB".format(f.bytes / 1e9),
            android.text.format.DateFormat.format("d MMM HH:mm", f.time).toString(),
        ).joinToString(" · ")
        Text(meta, color = K.OnSurfaceVar, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp))
    }
}
