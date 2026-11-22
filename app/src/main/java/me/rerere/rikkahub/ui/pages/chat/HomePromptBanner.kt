package me.rerere.rikkahub.ui.pages.chat

import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.Alignment as RiveAlignment
import app.rive.runtime.kotlin.core.Fit as RiveFit
import coil3.compose.AsyncImage
import me.rerere.rikkahub.service.HomePromptItem
import me.rerere.rikkahub.service.HomePromptRiveLoader
import me.rerere.rikkahub.service.LoadedRive
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.compose.koinInject

private const val TAG = "HomePromptBanner"
private const val MAX_MEDIA_WIDTH_DP = 220f
private const val MAX_MEDIA_HEIGHT_DP = 128f

private sealed interface RiveMediaState {
    data object Idle : RiveMediaState
    data object Loading : RiveMediaState
    data class Ready(val data: LoadedRive) : RiveMediaState
    data object Failed : RiveMediaState
}

/**
 * 新对话空页正中心的无底板悬浮欢迎区（类似 Doodle）：按当前浅/深色主题展示小图或 .riv（Rive）动效与居中短语，
 * 点击任意位置将获取到的提示词填入输入框。未获取到素材和文字（或加载失败且没有文字）时完全不显示。
 */
@Composable
fun HomePromptBanner(
    item: HomePromptItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val riveLoader: HomePromptRiveLoader = koinInject()
    val mediaUrl = item.imageForTheme(LocalDarkMode.current)
    val isRive = mediaUrl.isNotBlank() && item.isRiveMedia(mediaUrl)

    var imageLoaded by remember(mediaUrl) { mutableStateOf(false) }
    var imageFailed by remember(mediaUrl) { mutableStateOf(false) }
    var riveRenderFailed by remember(mediaUrl) { mutableStateOf(false) }

    val riveState by produceState<RiveMediaState>(
        initialValue = if (isRive) RiveMediaState.Loading else RiveMediaState.Idle,
        key1 = mediaUrl,
        key2 = isRive,
    ) {
        if (!isRive) {
            value = RiveMediaState.Idle
            return@produceState
        }
        value = RiveMediaState.Loading
        val loaded = riveLoader.load(mediaUrl)
        value = if (loaded != null) RiveMediaState.Ready(loaded) else RiveMediaState.Failed
    }

    val mediaLoaded = if (isRive) {
        riveState is RiveMediaState.Ready && !riveRenderFailed
    } else {
        imageLoaded
    }
    val mediaFailed = if (isRive) {
        riveState is RiveMediaState.Failed || riveRenderFailed
    } else {
        imageFailed
    }

    val showMedia = mediaUrl.isNotBlank() && !mediaFailed
    val showPhrase = item.phrase.isNotBlank()
    if (!showMedia && !showPhrase) return

    val hasVisibleContent = showPhrase || mediaLoaded
    val clickable = hasVisibleContent && item.insertText.isNotBlank()

    Surface(
        onClick = onClick,
        enabled = clickable,
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.widthIn(max = 320.dp),
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .then(
                    if (hasVisibleContent) {
                        Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    } else {
                        Modifier
                    }
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (showMedia) {
                if (isRive) {
                    val ready = riveState as? RiveMediaState.Ready
                    if (ready != null) {
                        val loaded = ready.data
                        val (widthDp, heightDp) = fitRiveSize(loaded.aspectRatio)
                        key(mediaUrl) {
                            AndroidView(
                                factory = { context ->
                                    RiveAnimationView(context).apply {
                                        // 透传点击事件给外层 Surface，保证点动效本身也能把提示词填入输入框
                                        touchPassThrough = true
                                        runCatching {
                                            setRiveBytes(
                                                bytes = loaded.bytes,
                                                autoplay = true,
                                                fit = RiveFit.CONTAIN,
                                                alignment = RiveAlignment.CENTER,
                                            )
                                        }.onFailure { error ->
                                            Log.w(TAG, "Failed to render Rive animation: $mediaUrl", error)
                                            riveRenderFailed = true
                                        }
                                    }
                                },
                                onRelease = { view ->
                                    runCatching { view.stop() }
                                },
                                modifier = Modifier
                                    .size(width = widthDp.dp, height = heightDp.dp)
                                    .clip(MaterialTheme.shapes.large),
                            )
                        }
                    }
                } else {
                    AsyncImage(
                        model = mediaUrl,
                        contentDescription = item.phrase.ifBlank { null },
                        contentScale = ContentScale.Fit,
                        onSuccess = { imageLoaded = true },
                        onError = { imageFailed = true },
                        modifier = if (imageLoaded) {
                            Modifier
                                .sizeIn(
                                    minWidth = 56.dp,
                                    minHeight = 56.dp,
                                    maxWidth = MAX_MEDIA_WIDTH_DP.dp,
                                    maxHeight = MAX_MEDIA_HEIGHT_DP.dp,
                                )
                                .clip(MaterialTheme.shapes.large)
                        } else {
                            // 给 Coil 有界约束以便发起加载，加载完成前不占高度
                            Modifier
                                .widthIn(max = MAX_MEDIA_WIDTH_DP.dp)
                                .heightIn(max = MAX_MEDIA_HEIGHT_DP.dp)
                        },
                    )
                }
            }
            if (showPhrase) {
                Text(
                    text = item.phrase,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun fitRiveSize(aspectRatio: Float): Pair<Float, Float> {
    val ratio = aspectRatio.coerceIn(0.4f, 4f)
    return if (ratio >= MAX_MEDIA_WIDTH_DP / MAX_MEDIA_HEIGHT_DP) {
        MAX_MEDIA_WIDTH_DP to (MAX_MEDIA_WIDTH_DP / ratio)
    } else {
        (MAX_MEDIA_HEIGHT_DP * ratio) to MAX_MEDIA_HEIGHT_DP
    }
}
