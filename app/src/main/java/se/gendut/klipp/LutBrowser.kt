package se.gendut.klipp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.LutStore

/** A preview frame for the LUT browser: the image with [lut] in place of the node's LUT (null = no LUT). */
typealias LutPreview = suspend (lut: String?) -> ImageBitmap?

/**
 * Klipp 17U: the LUT library as a strip of cards – each shows the current frame with that LUT ([preview]; without it,
 * the name only), grouped by the folders of the zip they were imported from. Tap = use it; hold = select several, to
 * delete them. [selected] = the LUT in use (null = none).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LutBrowser(
    selected: String?,
    onPick: (String?) -> Unit,
    preview: LutPreview?,
    onImport: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val names = LutStore.names.toList()
    val builtIn = stringResource(R.string.lutbrowser_builtin)
    val imported = stringResource(R.string.lutbrowser_imported)
    fun groupLabel(n: String) = if (LutStore.isBuiltIn(n)) builtIn else LutStore.groupOf(n).ifEmpty { imported }
    val groups = names.map(::groupLabel).distinct().sortedWith(compareBy({ it != builtIn }, { it != imported }, { it.lowercase() }))
    // the group shown: the one of the LUT in use, else all
    var chosen by remember { mutableStateOf(selected?.takeIf { it in names }?.let { groupLabel(it) }) }
    // a group that no longer exists (all its LUTs deleted) shows all
    val group = chosen?.takeIf { it in groups }
    val shown = names.filter { group == null || groupLabel(it) == group }
    // selection for deleting (built-in LUTs can't be selected)
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val selecting = picked.isNotEmpty()
    var askDelete by remember { mutableStateOf(false) }

    Column(modifier) {
        // groups
        LazyRow(
            Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { ToggleChip(stringResource(R.string.lutbrowser_all), group == null, { chosen = null }) }
            items(groups) { g -> ToggleChip(g, group == g, { chosen = g }) }
        }
        Spacer(Modifier.height(8.dp))
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.lutbrowser_selected, picked.size), color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                PillButton(stringResource(R.string.lutbrowser_select_all), { picked = picked + shown.filterNot { LutStore.isBuiltIn(it) } }, filled = false)
                PillButton(stringResource(R.string.lutbrowser_delete), { askDelete = true }, filled = true)
                PillButton(stringResource(R.string.lutbrowser_cancel), { picked = emptySet() }, filled = false)
            }
            Spacer(Modifier.height(8.dp))
        }
        // the cards
        LazyRow(
            Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!selecting) item(key = "\u0000none") {
                LutCard(stringResource(R.string.colortools_none), null, selected == null, false, preview,
                    onTap = { onPick(null) }, onHold = {})
            }
            items(shown, key = { it }) { n ->
                LutCard(Str.name(LutStore.shortName(n)), n, selected == n && !selecting, n in picked, preview,
                    onTap = {
                        if (selecting) { if (!LutStore.isBuiltIn(n)) picked = if (n in picked) picked - n else picked + n }
                        else onPick(n)
                    },
                    onHold = { if (!LutStore.isBuiltIn(n)) { haptics.heavy(); picked = picked + n } },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onImport != null) PillButton(stringResource(R.string.colortools_import_cube), onImport, filled = false)
            Spacer(Modifier.width(10.dp))
            if (!selecting) Text(stringResource(R.string.lutbrowser_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.lutbrowser_delete_title, picked.size)) },
            text = { Text(stringResource(R.string.lutbrowser_delete_text)) },
            confirmButton = {
                FlatTextButton(onClick = {
                    val gone = picked
                    askDelete = false
                    picked = emptySet()
                    if (selected != null && selected in gone) onPick(null)
                    LutStore.delete(gone)
                    haptics.confirm()
                }) { Text(stringResource(R.string.lutbrowser_delete)) }
            },
            dismissButton = { FlatTextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.lutbrowser_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LutCard(label: String, lut: String?, on: Boolean, picked: Boolean, preview: LutPreview?, onTap: () -> Unit, onHold: () -> Unit) {
    val img by produceState<ImageBitmap?>(null, lut, preview) { value = preview?.invoke(lut) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(112.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(K.SurfaceHigh)
                .then(if (on || picked) Modifier.border(2.dp, if (picked) K.Error else K.Primary, shape) else Modifier)
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onLongClick = onHold, onClick = onTap)
                .testTag(if (lut == null) "nodeLut:none" else "nodeLut:$lut"),
            contentAlignment = Alignment.Center,
        ) {
            val i = img
            if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(label, color = K.OnSurfaceVar, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
            if (on || picked) Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (picked) K.Error else K.Primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Check, null, tint = K.Surface, modifier = Modifier.size(12.dp)) }
        }
        Text(
            label, color = if (on) K.OnSurface else K.OnSurfaceVar, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}
