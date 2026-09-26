package com.aharou.core.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.aharou.R
import com.aharou.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Image
import compose.icons.feathericons.X
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 图片来源。
 *
 * 刻意做成值类型而不是「每个调用点自带一个 suspend 加载 lambda」：值类型 equals 稳定，可以直接
 * 当 [produceState] 的 key；lambda 那种写法会把待发送附件最大 5MB 的 base64 字符串一直攥在手里，
 * 附件都发出去、内存里那份已经清空了，查看器还在替它续命。
 */
sealed interface ImageSource {
    /** 宿主本地文件绝对路径，可以直接解码。 */
    data class LocalFile(val path: String) : ImageSource

    /** AI 视角的容器 / 远程路径，需要先经文件访问抽象落到宿主文件。 */
    data class ContainerPath(val path: String) : ImageSource

    /** 内存里的 base64 图片数据。 */
    data class Base64(val data: String) : ImageSource
}

/**
 * 一次查看请求。
 *
 * [sources] 按顺序尝试，前一个拿不到就退到下一个 —— 远程 SSH 模式下已发送附件记的
 * `localPath` 是 `createTempFile` + `deleteOnExit` 的临时文件，进程重启或缓存被清掉后就没了，
 * 必须能退回容器路径重新拉一次。
 */
@Immutable
data class ImageViewerRequest(
    val sources: List<ImageSource>,
    /** 无障碍描述与失败提示用，通常是文件名；留空则回退到「图片预览」。 */
    val title: String = ""
)

/** 打开全屏查看器的句柄：调用方只管 show，不关心加载与手势。 */
@Stable
fun interface ImageViewer {
    fun show(request: ImageViewerRequest)
}

/**
 * 默认是空实现：没挂 [ImageViewerHost] 的组合树里点图片静默无反应，不会崩。
 *
 * 与 `LocalMarkdownImageTransformer` 同一套路 —— 用 CompositionLocal 而不是一路加回调参数，
 * 否则 `AgentMessageItem` / `ChatInputBar` 这些中间层都要为了转发一个 lambda 改签名。
 */
val LocalImageViewer = staticCompositionLocalOf<ImageViewer> { ImageViewer { } }

@Stable
class ImageViewerState : ImageViewer {
    var request by mutableStateOf<ImageViewerRequest?>(null)
        private set

    override fun show(request: ImageViewerRequest) {
        this.request = request
    }

    fun dismiss() {
        request = null
    }
}

@Composable
fun rememberImageViewerState(): ImageViewerState = remember { ImageViewerState() }

private sealed interface ViewerLoad {
    object Loading : ViewerLoad
    object Failed : ViewerLoad
    data class Ready(val bitmap: ImageBitmap) : ViewerLoad
}

/**
 * 全屏图片查看器宿主。挂在组合树里任意位置即可 —— [Dialog] 是独立 window，不占父布局尺寸。
 *
 * 用 Dialog 而不是组合内的 overlay Box，三条硬理由：
 * 1. `MainActivity` 把全局背景水印画在 `AppNavigation()` **之后**，overlay 会被那张水印叠在大图上；
 * 2. 平板双栏下聊天面板只占半屏，overlay 只能盖住聊天列，盖不满整屏；
 * 3. Dialog 自带返回键关闭，且窗口有焦点，优先级天然高于既有的「收侧栏 / 收右栏」BackHandler，
 *    不用去推敲它们的相对顺序。
 *
 * @param load 把 [ImageSource] 解成位图；实现方自行切 IO 线程，返回 null 表示这个来源拿不到。
 */
@Composable
fun ImageViewerHost(
    state: ImageViewerState,
    load: suspend (ImageSource) -> ImageBitmap?
) {
    val request = state.request ?: return
    val loadState by produceState<ViewerLoad>(ViewerLoad.Loading, request, load) {
        value = request.sources.firstNotNullOfOrNull { load(it) }
            ?.let { ViewerLoad.Ready(it) }
            ?: ViewerLoad.Failed
    }

    Dialog(
        onDismissRequest = state::dismiss,
        properties = DialogProperties(
            // 不设则 dialog window 是 WRAP_CONTENT，内容铺不满屏
            usePlatformDefaultWidth = false,
            // 不设则内容被系统栏 inset 挤进安全区，黑底在状态栏处断开、露出后面的聊天界面
            decorFitsSystemWindows = false
        )
    ) {
        // 浅色主题下系统栏图标是深色，压在黑底上看不见。dialog 有自己的 window，单独调它的图标色。
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 媒体查看器背板刻意不随主题走：浅色模式下也该是近黑，做成语义色反而是错的
                .background(Color.Black.copy(alpha = BACKDROP_ALPHA))
        ) {
            when (val loaded = loadState) {
                ViewerLoad.Loading -> CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )

                ViewerLoad.Failed -> LoadFailedPlaceholder(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable(onClick = state::dismiss)
                        .padding(Spacing.lg)
                )

                is ViewerLoad.Ready -> ZoomableImage(
                    bitmap = loaded.bitmap,
                    contentDescription = request.title.ifBlank { stringResource(R.string.common_image_preview) },
                    onTap = state::dismiss,
                    modifier = Modifier.fillMaxSize()
                )
            }

            IconButton(
                onClick = state::dismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // decorFitsSystemWindows = false 之后要自己让开系统栏
                    .systemBarsPadding()
                    .padding(Spacing.sm)
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = stringResource(R.string.common_close),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun LoadFailedPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = FeatherIcons.Image,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = stringResource(R.string.common_image_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

/** 背板不透明度。Compose 的 Dialog 本身还会给 window 加一层 dim，这里不必给满。 */
private const val BACKDROP_ALPHA = 0.92f

private const val MIN_SCALE = 1f

/**
 * 缩放上限的下界。超长图按 Fit 铺进屏幕后本身就已经很小，只给 5 倍根本看不清细节，
 * 所以实际上限另取「能放大到 1:1 解码像素」的值，见 [ZoomableImage] 里的 maxScale。
 */
private const val MAX_SCALE_FLOOR = 5f

private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOM_ANIM_MS = 220

/** 浮点比较容差：判断"是否还处在原始尺寸"。 */
private const val SCALE_EPS = 0.01f

/**
 * 图片按 [ContentScale.Fit] 放进容器后的实际绘制尺寸。
 *
 * 夹位移边界必须按这个尺寸算而不是按容器尺寸算，否则能把图拖进两侧黑边里。
 */
fun fitInside(image: IntSize, container: IntSize): Size {
    if (image.width <= 0 || image.height <= 0 || container.width <= 0 || container.height <= 0) {
        return Size.Zero
    }
    val ratio = minOf(
        container.width.toFloat() / image.width,
        container.height.toFloat() / image.height
    )
    return Size(image.width * ratio, image.height * ratio)
}

/** 单轴最大位移：放大后超出容器的部分对半分。没超出则为 0，图钉死在正中，杜绝漂移。 */
fun maxPan(contentPx: Float, containerPx: Float, scale: Float): Float =
    ((contentPx * scale - containerPx) / 2f).coerceAtLeast(0f)

/** 把位移夹回可视范围内。 */
fun clampPan(offset: Offset, content: Size, container: IntSize, scale: Float): Offset {
    val limitX = maxPan(content.width, container.width.toFloat(), scale)
    val limitY = maxPan(content.height, container.height.toFloat(), scale)
    return Offset(offset.x.coerceIn(-limitX, limitX), offset.y.coerceIn(-limitY, limitY))
}

/**
 * 以 [centroid]（容器内坐标）为锚点缩放后的新位移：捏合时手指底下那个像素保持不动。
 *
 * [graphicsLayer] 的 transformOrigin 默认在中心，所以一切以容器中心 [containerCenter] 为原点。
 * 记 v = centroid − containerCenter，内容点 p 的屏幕位置为 `p·scale + offset`；
 * 要让 v 处的内容点在手势后落到 v + pan，解得
 * `newOffset = v + pan − (v − oldOffset)·(newScale / oldScale)`。
 *
 * 两个退化情形可作校验：纯平移（newScale == oldScale）得 `oldOffset + pan`；
 * 以中心纯缩放（v = 0, pan = 0）得 `oldOffset · k`。
 */
fun zoomAround(
    centroid: Offset,
    containerCenter: Offset,
    oldScale: Float,
    newScale: Float,
    oldOffset: Offset,
    pan: Offset
): Offset {
    if (oldScale <= 0f) return oldOffset
    val v = centroid - containerCenter
    return v + pan - (v - oldOffset) * (newScale / oldScale)
}

/**
 * 双指捏合缩放 + 放大后单指拖动 + 双击在原始尺寸与放大之间切换的图片。
 *
 * 缩放与位移全走 [graphicsLayer]，不触发重新布局；边界由 [clampPan] 按 Fit 后的实际绘制尺寸
 * 夹取，所以松手不会漂移、也拖不进黑边。
 *
 * @param onTap 单击回调。仅在未放大时触发 —— 放大看细节时误触一下就关掉太容易了。
 */
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var zoomAnim by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val imageSize = remember(bitmap) { IntSize(bitmap.width, bitmap.height) }
    val content = remember(imageSize, container) { fitInside(imageSize, container) }
    val center = remember(container) { Offset(container.width / 2f, container.height / 2f) }
    // 上限取「回到 1:1 解码像素」，否则长截图 Fit 之后缩得极小，5 倍也看不清字
    val maxScale = remember(imageSize, content) {
        if (content.width <= 0f) MAX_SCALE_FLOOR else maxOf(MAX_SCALE_FLOOR, imageSize.width / content.width)
    }

    // 旋转屏幕、平板拖动分栏之后容器尺寸变了，旧位移可能越界。AndroidManifest 的 configChanges
    // 含 orientation，Activity 不重建、scale/offset 全部保留，所以必须在这里重新夹一次。
    // 刻意不把 scale 放进 key：那会让每次缩放都走一次重组，白费 graphicsLayer 的好处。
    LaunchedEffect(content, container) {
        offset = clampPan(offset, content, container, scale)
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { container = it }
            // tap 必须写在 transform 之前：同一 modifier 链里越靠后的越深、Main pass 先到。
            // 让 transform 先消费，捏合越过 touchSlop 后 tap 的 waitForUpOrCancellation 才会看到
            // isConsumed 而放弃；顺序写反的话，捏一下就把查看器关了。
            //
            // key 必须带上 imageSize / container：pointerInput 的 key 不变时不会重启已在运行的
            // 手势协程，只换 lambda 引用，于是回调里按值捕获的 content / center / maxScale 会一直
            // 停在旧尺寸上——旋转屏幕后就用错的中心点缩放、按错的边界夹位移。
            .pointerInput(imageSize, container) {
                detectTapGestures(
                    onTap = { if (scale <= MIN_SCALE + SCALE_EPS) onTap() },
                    onDoubleTap = { tap ->
                        val from = scale
                        val to = if (from > MIN_SCALE + SCALE_EPS) {
                            MIN_SCALE
                        } else {
                            minOf(DOUBLE_TAP_SCALE, maxScale)
                        }
                        val fromOffset = offset
                        val toOffset = if (to <= MIN_SCALE + SCALE_EPS) {
                            Offset.Zero
                        } else {
                            clampPan(
                                zoomAround(tap, center, from, to, fromOffset, Offset.Zero),
                                content,
                                container,
                                to
                            )
                        }
                        zoomAnim?.cancel()
                        zoomAnim = scope.launch {
                            animate(0f, 1f, animationSpec = tween(ZOOM_ANIM_MS)) { fraction, _ ->
                                scale = from + (to - from) * fraction
                                offset = lerp(fromOffset, toOffset, fraction)
                            }
                        }
                    }
                )
            }
            .pointerInput(imageSize, container) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    zoomAnim?.cancel()
                    val from = scale
                    val to = (from * zoom).coerceIn(MIN_SCALE, maxScale)
                    val moved = zoomAround(centroid, center, from, to, offset, pan)
                    scale = to
                    offset = clampPan(moved, content, container, to)
                }
            }
    ) {
        ComposeImage(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}
