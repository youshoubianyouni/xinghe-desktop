package app.xinghe.tv.ui.wallpaper

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a phone sends to the TV, and the code that sends it. Pictures and films go to the
 * wallpapers; anything else — a document, an app — is kept here, newest first, and an APK is
 * handed to the system's installer from this list.
 *
 * Reached from Home's top right, beside the wallpapers: sending is done often enough to want it
 * one press away, and the code is only worth showing while someone is looking for it.
 */
@Composable
fun UploadSheet(
    visible: Boolean,
    phoneUrl: String?,
    phoneQr: ImageBitmap?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Watched while the sheet is up: a phone sending files changes this list under it.
    var stamp by remember { mutableStateOf(0) }
    val files by produceState(emptyList(), visible, stamp) {
        value = withContext(Dispatchers.IO) { Uploads.all(context) }
    }
    val first = remember { FocusRequester() }
    BackHandler(enabled = visible, onBack = onDismiss)
    LaunchedEffect(visible, files.size) {
        if (!visible) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
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
                    // The same glass as the other sheets from Home's top right.
                    .liquidGlass(30.dp, bottomCorners = false)
                    .padding(start = 40.dp, end = 32.dp, top = 24.dp, bottom = 24.dp),
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    BasicText(stringResource(R.string.up_title), style = Type.LargeTitle)
                    BasicText(
                        stringResource(R.string.up_body),
                        style = Type.Callout,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    )
                    if (files.isEmpty()) {
                        BasicText(
                            stringResource(R.string.up_empty),
                            style = Type.Body.copy(color = Palette.SecondaryLabel),
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    } else {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(vertical = 4.dp),
                        ) {
                            files.forEachIndexed { i, f ->
                                ReceivedRow(f, if (i == 0) first else null) {
                                    Uploads.delete(context, f.id)
                                    stamp++
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.width(28.dp))
                PhoneCard(
                    phoneUrl, phoneQr,
                    title = stringResource(R.string.up_phone_title),
                    body = stringResource(R.string.up_phone_body),
                )
            }
        }
    }
}

/**
 * One file that arrived: its name and size, and how to be rid of it. An APK takes the place of a
 * name with "Install" on it, which opens the system's installer for it.
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
        } else {
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
}

/** A size as people read it: 1.2 MB, 340 KB. */
private fun sizeText(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}
