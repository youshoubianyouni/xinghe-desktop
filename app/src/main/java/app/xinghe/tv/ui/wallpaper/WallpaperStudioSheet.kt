package app.xinghe.tv.ui.wallpaper

import android.content.Intent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.xinghe.tv.R
import app.xinghe.tv.ui.components.Pressable
import app.xinghe.tv.ui.components.consumeTaps
import app.xinghe.tv.ui.components.dismissOnTap
import app.xinghe.tv.ui.components.liquidGlass
import app.xinghe.tv.ui.theme.Glyph
import app.xinghe.tv.ui.theme.GlyphIcon
import app.xinghe.tv.ui.theme.Motion
import app.xinghe.tv.ui.theme.Palette
import app.xinghe.tv.ui.theme.Type
import app.xinghe.tv.wallpaper.ReceivedFile
import app.xinghe.tv.wallpaper.Uploads
import app.xinghe.tv.wallpaper.Wallpaper
import app.xinghe.tv.wallpaper.WallpaperKind
import app.xinghe.tv.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything a phone has sent, and the code that sends more. Press a picture and it sits behind
 * Home; press a film and it plays there; the rest of the files are kept in the list under them,
 * where an app is installed and anything is let go of. One sheet, because sending and choosing
 * are the same thought: what arrives is meant for the wall behind all of this.
 *
 * Reached from Home's top right, next to the status pill. Back, or a press beside the sheet,
 * closes it.
 */
@Composable
fun WallpaperStudioSheet(
    visible: Boolean,
    phoneUrl: String?,
    phoneQr: ImageBitmap?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val photos = Wallpapers.items.filter { it.kind == WallpaperKind.Photo }
    val videos = Wallpapers.items.filter { it.kind == WallpaperKind.Video }
    // The files, followed as they come: the counter moves when a phone sends one, or when one
    // here is let go of, and the list reads the folder anew each time.
    val changes by Uploads.changes.collectAsState()
    var files by remember { mutableStateOf(emptyList<ReceivedFile>()) }
    LaunchedEffect(visible, changes) {
        if (visible) files = withContext(Dispatchers.IO) { Uploads.all(context) }
    }
    val first = remember { FocusRequester() }
    BackHandler(enabled = visible, onBack = onDismiss)
    LaunchedEffect(visible, photos.size, videos.size, files.size) {
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
                    if (photos.isEmpty() && videos.isEmpty() && files.isEmpty()) {
                        BasicText(
                            stringResource(R.string.wp_studio_empty),
                            style = Type.Body.copy(color = Palette.SecondaryLabel),
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                    // Focus starts on the first control there is, whichever of the three that is.
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
                    if (files.isNotEmpty()) {
                        if (firstTaken) Spacer(Modifier.height(14.dp)) else Spacer(Modifier.height(4.dp))
                        SectionHead(R.string.up_title)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(vertical = 4.dp),
                        ) {
                            files.forEach { f ->
                                ReceivedRow(f, if (!firstTaken) first.also { firstTaken = true } else null, onDelete = {
                                    Uploads.delete(context, f.id)
                                })
                            }
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

/**
 * One file that arrived: its name and size, and how to be rid of it — every row lets go, an app
 * also carries "Install", which opens the system's installer for it.
 */
@Composable
private fun ReceivedRow(file: ReceivedFile, focusRequester: FocusRequester?, onDelete: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 18.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlyphIcon(if (file.apk) Glyph.Bag else Glyph.Folder, Palette.SecondaryLabel, 18.dp)
        Column(Modifier.weight(1f)) {
            BasicText(file.name, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(
                sizeText(file.size),
                style = Type.Caption.copy(color = Palette.TertiaryLabel),
            )
        }
        if (file.apk) {
            Pressable(
                onClick = {
                    // Through a FileProvider: the file lives in the app's own folder, which the
                    // installer may not read by path.
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file.file)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                focusedScale = 1.06f,
            ) { state ->
                val bg = if (state.focused) Palette.Focus else Palette.Fill
                val fg = if (state.focused) Palette.OnFocus else Palette.Label
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(bg).height(36.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GlyphIcon(Glyph.Download, fg, 14.dp)
                    BasicText(stringResource(R.string.up_install), style = Type.Body.copy(color = fg))
                }
            }
        }
        Pressable(onClick = onDelete, focusedScale = 1.06f) { state ->
            val bg = if (state.focused) Palette.Focus else Color.Transparent
            val fg = if (state.focused) Palette.OnFocus else Palette.SecondaryLabel
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(bg),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Trash, fg, 17.dp) }
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

/** A size as people read it: 1.2 MB, 340 KB. */
private fun sizeText(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}
