package se.gendut.klipp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.Film

/** A preview frame for the film browser: the clip's frame with [film] in place of the node's film. */
typealias FilmPreview = suspend (film: Film) -> ImageBitmap?

/** One card of the film browser: what it shows ([film]), its name, a line under it, the group's name over the first. */
class FilmCardItem(val key: String, val label: String, val sub: String?, val film: Film, val group: String?)

/**
 * Klipp Lab: a strip of cards like the LUT browser – each the current frame through that film (or paper), made in the
 * background as the cards come into view. Tap = use it; hold = see it on the big picture while the finger is down
 * ([onHold] with the film, then null on release), without changing the grade.
 */
@Composable
fun FilmBrowser17u(
    items: List<FilmCardItem>,
    selected: String?,
    preview: FilmPreview?,
    onPick: (FilmCardItem) -> Unit,
    onHold: (Film?) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val start = items.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0))
    LazyRow(
        modifier.fillMaxWidth().testTag(tag), state = list, contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items.size, key = { items[it].key }) { i ->
            val it = items[i]
            FilmCard(it, it.key == selected, preview, { onPick(it) }, onHold, "$tag:${it.key}")
        }
    }
}

@Composable
private fun FilmCard(item: FilmCardItem, on: Boolean, preview: FilmPreview?, onTap: () -> Unit, onHold: (Film?) -> Unit, tag: String) {
    val haptics = rememberHaptics()
    val img by produceState<ImageBitmap?>(null, item.film, preview) { value = preview?.invoke(item.film) }
    // (the latest callbacks: the gesture detector lives on with the card)
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val film by rememberUpdatedState(item.film)
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(112.dp)) {
        Text(
            item.group ?: "", color = K.Primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Visible, modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 3.dp),
        )
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(K.SurfaceHigh)
                .then(if (on) Modifier.border(2.dp, K.Primary, shape) else Modifier)
                .pointerInput(Unit) {
                    var held = false
                    detectTapGestures(
                        onPress = {
                            held = false
                            tryAwaitRelease()
                            if (held) { held = false; hold(null) }
                        },
                        onLongPress = { held = true; haptics.tick(); hold(film) },
                        onTap = { tap() },
                    )
                }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            val i = img
            if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(item.label, color = K.OnSurfaceVar, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
            if (on) Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(RoundedCornerShape(9.dp)).background(K.Primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Check, null, tint = K.Surface, modifier = Modifier.size(12.dp)) }
        }
        Text(
            item.label, color = if (on) K.OnSurface else K.OnSurfaceVar, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
        if (item.sub != null) Text(
            item.sub, color = K.Outline, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
        )
    }
}

/**
 * Klipp Lab: the film browser's card pictures, kept for the session (about 24 MB): a strip closed and opened again, another
 * tab or the editor reopened show them at once while the frame (clip and playhead to ½ s), the rest of the grade and the
 * film stay the same. Before, every card was baked again (one at a time) each time the strip came back.
 */
object FilmPreviewCache {
    data class Key(val where: String, val base: se.gendut.klipp.engine.ClipGrade, val node: Int, val film: Film, val detail: Boolean)

    private val cache = object : android.util.LruCache<Key, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(k: Key): ImageBitmap? = cache.get(k)
    fun put(k: Key, v: ImageBitmap) { cache.put(k, v) }
}
