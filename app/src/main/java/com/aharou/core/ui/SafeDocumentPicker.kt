package com.aharou.core.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.aharou.core.util.FileLogger

private const val TAG = "SafeDocumentPicker"

/**
 * 容错的「选一个文件」入口，用来兜住系统文件选择器缺席时的闪退。
 *
 * 现场（Issue #47）：`ActivityResultContracts.OpenDocument` 的 createIntent 就是
 * `Intent(ACTION_OPEN_DOCUMENT).setType(通配 MIME)`，`launch()` 内部直接 startActivityForResult，
 * 既不预检处理者也没有 try/catch。部分定制 ROM（HyperOS 等）会精简或停用系统「文件」组件
 * （DocumentsUI），分身 / 工作资料空间里也常常看不到它；此时 startActivityForResult 抛
 * ActivityNotFoundException 并冒到主线程，用户侧看到的就是「应用遇到未知错误」+ 设置页闪退，
 * 崩溃日志里只剩一句 `typ=通配 MIME (has extras)`。
 *
 * 三层防御：
 *  1. 启动前用 PackageManager 预检处理者，按 OPEN_DOCUMENT → GET_CONTENT(+EXTRA_MIME_TYPES)
 *     → GET_CONTENT（带 MIME 过滤）逐级降级。很多第三方文件管理器只注册 GET_CONTENT，降级后这类
 *     机器仍能正常导入，不至于直接把功能判死。
 *  2. 预检命中但启动瞬间仍抛异常（组件被冻结 / 卸载的竞态）时捕获，回调 [onUnavailable]。
 *  3. 无处理者时落一条日志，便于按机型复查同类报告。
 *
 * 包可见性：manifest 已声明 `QUERY_ALL_PACKAGES`，故 [resolveActivity] 探针结果可信，
 * 不会被 Android 11+ 的可见性过滤误判成「没有处理者」。
 */
object SafeDocumentPicker {

    /** 降级阶梯：靠前的入口能力更完整（可拿到持久化授权），靠后的兼容性更好。 */
    internal fun candidates(mimeTypes: Array<String>): List<Intent> {
        val mimes = mimeTypes.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
        return listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, mimes)
                .addCategory(Intent.CATEGORY_OPENABLE),
            Intent(Intent.ACTION_GET_CONTENT)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, mimes)
                .addCategory(Intent.CATEGORY_OPENABLE),
            Intent(Intent.ACTION_GET_CONTENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE),
        )
    }

    /** 第一个真有处理者的 Intent；一个都没有时返回 null。 */
    fun resolve(context: Context, mimeTypes: Array<String>): Intent? =
        candidates(mimeTypes).firstOrNull { it.resolveActivity(context.packageManager) != null }
}

/**
 * 在 createIntent 时才决议真实 action：OPEN_DOCUMENT 不可用就退 GET_CONTENT。
 * 三级全不可用时返回 OPEN_DOCUMENT 原型，由 [rememberSafeDocumentPicker] 提前拦下，
 * 不会真的走到 launch 抛异常那一步。
 */
class SafeOpenDocumentContract : ActivityResultContract<Array<String>, Uri?>() {

    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = SafeDocumentPicker.resolve(context, input)
            ?: Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, input)
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * 记住一个「不崩」的选文件动作，可直接当点击回调用：
 * ```
 * val pickSkillZip = rememberSafeDocumentPicker(
 *     arrayOf("application/zip", "application/octet-stream"),
 *     onUnavailable = { Toast.makeText(context, R.string.document_picker_unavailable, Toast.LENGTH_LONG).show() },
 * ) { uri -> viewModel.importSkillsFromZip(uri, scope) }
 * ...
 * onClick = pickSkillZip
 * ```
 * [onUnavailable] 只在确实无法启动任何选择器（或启动瞬间抛异常）时调用，提示文案由调用方决定。
 */
@Composable
fun rememberSafeDocumentPicker(
    mimeTypes: Array<String>,
    onUnavailable: () -> Unit,
    onPicked: (Uri) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    // 契约无状态，记住实例即可，避免每次重组都新建一个传给 rememberLauncherForActivityResult。
    val contract = remember { SafeOpenDocumentContract() }
    // 回调经 rememberUpdatedState 读取：launcher 与返回的闭包都会被 remember 长期持有，
    // 直接捕获 lambda 会让重组后仍调旧回调——调用点的 onPicked 常包着可变状态（如导入作用域），
    // 捕获旧值会导到错的作用域。
    val currentPicked by rememberUpdatedState(onPicked)
    val currentUnavailable by rememberUpdatedState(onUnavailable)
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        uri?.let { currentPicked(it) }
    }
    // Array 用引用相等，按内容哈希做 key，避免每次重组都重建闭包。
    return remember(mimeTypes.contentHashCode(), launcher) {
        {
            val resolved = SafeDocumentPicker.resolve(context, mimeTypes)
            if (resolved == null) {
                FileLogger.w(TAG, "no handler for OPEN_DOCUMENT / GET_CONTENT, mimes=${mimeTypes.toList()}")
                currentUnavailable()
            } else {
                try {
                    launcher.launch(mimeTypes)
                } catch (e: ActivityNotFoundException) {
                    // 预检命中、启动瞬间处理者被停用 / 卸载的竞态：只提示，不崩。
                    FileLogger.w(TAG, "picker rejected ${resolved.action}", e)
                    currentUnavailable()
                }
            }
        }
    }
}
