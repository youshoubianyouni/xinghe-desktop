package app.xinghe.tv.ui.wallpaper

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xinghe.tv.R
import app.xinghe.tv.ui.components.consumeTaps
import app.xinghe.tv.ui.components.dismissOnTap
import app.xinghe.tv.ui.components.liquidGlass
import app.xinghe.tv.ui.theme.Motion
import app.xinghe.tv.ui.theme.Palette
import app.xinghe.tv.ui.theme.Type
import app.xinghe.tv.wallpaper.Wallpaper
import app.xinghe.tv.wallpaper.WallpaperKind
import app.xinghe.tv.wallpaper.Wallpapers

/**
 * One's own pictures and films, the ones a phone has sent, in a sheet of their own: press one and
 * it is the wallpaper — a picture behind Home, or a film playing behind it. The code on the right
 * sends more, and it is the same phone page Personalize uses.
 *
 * It is reached from Home's top right, next to the status pill, rather than from Personalize: it
 * grows with every upload, and a row of cards that long pushed the rest of the settings out of
 * sight. Back, or a press beside the sheet, closes it.
 */
@Composable
fun WallpaperStudioSheet(
    visible: Boolean,
    phoneUrl: String?,
    phoneQr: ImageBitmap?,
    onDismiss: () -> Unit,
) {
    val photos = Wallpapers.items.filter { it.kind == WallpaperKind.Photo }
    val videos = Wallpapers.items.filter { it.kind == WallpaperKind.Video }
    val first = remember { FocusRequester() }
    BackHandler(enabled = visible, onBack = onDismiss)
    LaunchedEffect(visible, photos.size, videos.size) {
        if (visible) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(180)), modifier = Modifier.fillMaxSize()) {
        // A tap outside the sheet closes it, as Back does.
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.25f)))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)) + slideInVertically(tween(380, easing = Motion.Drawer)) { it / 3 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200, easing = Motion.EaseOut)) { it / 4 },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .consumeTaps()
                    // The same glass as Personalize's sheet, one drawer higher.
                    .liquidGlass(30.dp, bottomCorners = false)
                    .padding(start = 40.dp, end = 32.dp, top = 24.dp, bottom = 24.dp),
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    BasicText(stringResource(R.string.wp_wallpaper), style = Type.LargeTitle)
                    BasicText(
                        stringResource(R.string.wp_studio_hint),
                        style = Type.Callout,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    )
                    if (photos.isEmpty() && videos.isEmpty()) {
                        BasicText(
                            stringResource(R.string.wp_studio_empty),
                            style = Type.Body.copy(color = Palette.SecondaryLabel),
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                    // Focus starts on the first card there is, whichever of the two that is.
                    var firstTaken = false
                    fun takeFirst() = if (firstTaken) null else first.also { firstTaken = true }
                    if (photos.isNotEmpty()) {
                        SectionHead(R.string.wp_my_photos)
                        CardGrid(photos, takeFirst()) { wp, requester ->
                            WallpaperCard(wp, requester, onFocused = {}, onClick = { Wallpapers.select(wp.id) })
                        }
                    }
                    if (videos.isNotEmpty()) {
                        if (firstTaken) Spacer(Modifier.height(14.dp))
                        SectionHead(R.string.wp_my_videos)
                        CardGrid(videos, takeFirst()) { wp, requester ->
                            WallpaperCard(wp, requester, onFocused = {}, onClick = { Wallpapers.select(wp.id) })
                        }
                    }
                    // Room under the last row, as the grid above it has.
                    Spacer(Modifier.height(20.dp))
                }
                Spacer(Modifier.width(28.dp))
                PhoneCard(
                    phoneUrl, phoneQr,
                    title = stringResource(R.string.wp_phone_title),
                    body = stringResource(R.string.wp_studio_phone),
                )
            }
        }
    }
}

/** Three cards to a row, as the videos folder has; past that the sheet scrolls inside itself. */
@Composable
private fun CardGrid(
    items: List<Wallpaper>,
    /** Handed to the first card of the sheet, whatever row it sits in. */
    firstRequester: FocusRequester?,
    card: @Composable (Wallpaper, FocusRequester?) -> Unit,
) {
    Column(
        // Room for the focused card's 1.08x scale, so the edge rows are not clipped.
        Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                row.forEach { wp ->
                    card(wp, if (firstRequester != null && wp === items.first()) firstRequester else null)
                }
            }
        }
    }
}

@Composable
private fun SectionHead(label: Int) {
    BasicText(stringResource(label), style = Type.Headline, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}
