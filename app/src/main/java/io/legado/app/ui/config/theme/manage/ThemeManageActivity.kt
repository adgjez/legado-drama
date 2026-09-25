package io.legado.app.ui.config.theme.manage

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.PreferKey
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import io.legado.app.constant.EventBus
import io.legado.app.utils.observeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 主题管理容器 Activity
 *
 * 为什么只留这点代码：
 * 作为纯粹的容器，仅充当系统级组件（如文件选择器 Intent、ColorPicker 碎片对话框）与界面的桥梁。
 * 所有的业务状态流转和逻辑校验已经下沉到了 [ThemeManageViewModel]，
 * 此类仅负责把外部系统回调转换成 ViewModel 的方法调用，绝对禁止在此类中硬编码任何 UI 状态。
 */
class ThemeManageActivity :
    BaseComposeActivity(),
    ColorPickerDialogListener {

    private val viewModel: ThemeManageViewModel by viewModels {
        ThemeManageViewModelFactory(application)
    }

    private var pendingColorKey: String? = null

    private val selectImage = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val backgroundsDir = externalFiles
                        .getFile(PreferKey.bgImage)
                        .apply { mkdirs() }
                    val extension = when (uri.scheme) {
                        "content" -> {
                            val mimeType = contentResolver.getType(uri)
                            when (mimeType) {
                                "image/jpeg" -> "jpg"
                                "image/png" -> "png"
                                "image/webp" -> "webp"
                                else -> "jpg"
                            }
                        }
                        else -> uri.path?.substringAfterLast('.', "jpg") ?: "jpg"
                    }
                    val destFile = File(backgroundsDir, "theme_bg_${System.currentTimeMillis()}.$extension")
                    contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val oldPath = viewModel.editDraft.value?.backgroundImgPath
                    if (!oldPath.isNullOrBlank() && oldFileInside(backgroundsDir, oldPath)) {
                        File(oldPath).delete()
                    }
                    viewModel.updateDraftBackgroundImage(destFile.absolutePath)
                    toastOnUi(R.string.success)
                } catch (e: Exception) {
                    toastOnUi(R.string.select_image_failed)
                }
            }
        }
    }

    private fun oldFileInside(dir: File, path: String): Boolean {
        runCatching {
            val canonicalDir = dir.canonicalPath
            val canonicalFile = File(path).canonicalPath
            return if (canonicalFile == canonicalDir) {
                false
            } else {
                canonicalFile.startsWith(canonicalDir + File.separator)
            }
        }.onFailure {
            return false
        }
        return false
    }

    private var recreatePending = false

    /**
     * 日夜主题切换的重建路径（模拟器实测）：
     *
     * `setDefaultNightMode` 走的是 AppCompat 的**系统级 relaunch**（ActivityThread
     * 直接重建新实例），**不会回调 onConfigurationChanged**（manifest 的
     * configChanges="uiMode" 只能阻断配置变化自动重建，挡不住这次强制 relaunch）。
     * 因此在 [onConfigurationChanged] 里打时间戳是死代码——它根本不会执行。
     *
     * 一次日夜切换的真实时序：`setDefaultNightMode` → 立即 relaunch（新实例创建，
     * [onCreate] 记录 [instanceCreateTime]）→ 1.5s 防抖窗口结束后事件总线再来一条
     * RECEIVE 广播。这条迟到的广播若再放行重建一次，会与刚完成的窗口过渡对撞，
     * 在部分 ROM（HyperOS 实测）上残留层吞掉触摸输入 → 页面定格、仅局部可点。
     *
     * 判定口径：**新实例创建时刻 + 2s 宽限**，窗口内到来的 RECEIVE 直接忽略。
     * 窗口略大于防抖延迟（1500ms）即可兜住迟到广播；再次点击发生在 2s 窗口内的
     * 极少数二次应用场景，日夜切换仍由 relaunch 生效，仅纯色变会被延后重绘。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        instanceCreateTime = System.currentTimeMillis()
        recreate()
    }

    /**
     * 重建路径统一走「销毁 + 全新 startActivity」。
     *
     * 第 8 轮模拟器实测：`recreate()` 走 ActivityTaskManager 的「原地重启动」，
     * 重建出的实例存在[缺陷]——应用层输入正常、snapshot 状态写入成功，但
     * Compose 的重组/重绘调度完全不再触发（首帧组合后定格；日志实证：冷启动
     * 实例重组<5ms 内触发，recreate 实例状态变更后永不触发）。而同一进程内
     * 全新 startActivity 的实例一切正常（重组/动画/帧全链路健康）。
     *
     * 因此在主题切换等需要重建的场景，用「先启动新实例、再销毁旧实例」替代
     * 原地重建，让新窗口以全新启动路径建立，规避该窗口层级的重组冻结。
     */
    override fun recreate() {
        if (recreatePending || isFinishing || isDestroyed) return
        recreatePending = true
        instanceCreateTime = System.currentTimeMillis()
        startActivity(
            Intent(this, ThemeManageActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题切换触发的系统级 relaunch 会创建新实例；记录创建时刻用于
        // 识别「刚重建完成」的实例，忽略防抖窗口内迟到的 RECEIVE 广播。
        instanceCreateTime = System.currentTimeMillis()
        super.onCreate(savedInstanceState)
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.RECREATE) {
            // 本实例创建于窗口内（刚被主题切换重建）→ 广播是同一操作的迟到重复，
            // 跳过以免第二次重建打断窗口过渡。窗口外的广播（如长按后主动返回的页面）
            // 正常放行。
            val now = System.currentTimeMillis()
            if (now - instanceCreateTime < RECREATE_IGNORE_MS) {
                return@observeEvent
            }
            if (recreatePending || isFinishing || isDestroyed) return@observeEvent
            instanceCreateTime = now
            window.decorView.postOnAnimation {
                if (!isFinishing && !isDestroyed) recreate()
            }
        }
    }

    @androidx.compose.runtime.Composable
    override fun ComposeContent() {
        ThemeManageScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onImportFromClipboard = { toastOnUi(R.string.import_success) },
            onImportEmpty = { toastOnUi(R.string.clipboard_empty) },
            onImportFailed = { toastOnUi(R.string.import_failed) },
            onSelectImage = { selectImage.launch { mode = HandleFileContract.IMAGE } },
            onShareJson = { json -> share(json) },
            onDeleteConfirm = {
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete)
                    .setMessage(R.string.sure_del)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        viewModel.executeDeleteSelected()
                    }
                    .show()
            },
            onToast = { toastOnUi(it) },
            onToastMsg = { toastOnUi(it) },
            onColorClick = { colorKey, currentColor ->
                pendingColorKey = colorKey
                val color = runCatching { currentColor.toColorInt() }
                    .getOrDefault(ContextCompat.getColor(this, R.color.default_primary))
                val dialog = ColorPickerDialog.newBuilder()
                    .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                    .setColor(color)
                    .setShowAlphaSlider(false)
                    .setAllowPresets(true)
                    .setAllowCustom(true)
                    .setDialogId(DIALOG_ID_THEME_COLOR)
                    .create()
                dialog.setColorPickerDialogListener(this@ThemeManageActivity)
                supportFragmentManager
                    .beginTransaction()
                    .add(dialog, "theme_color_$colorKey")
                    .commitAllowingStateLoss()
            },
            onBlurClick = { currentBlur ->
                NumberPickerDialog(this)
                    .setTitle(getString(R.string.background_image_blurring))
                    .setMinValue(0)
                    .setMaxValue(25)
                    .setValue(currentBlur)
                    .show { blur -> viewModel.updateDraftBlur(blur) }
            },
        )
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        if (dialogId == DIALOG_ID_THEME_COLOR) {
            val key = pendingColorKey ?: return
            viewModel.updateDraftColor(key, color)
        }
    }

    override fun onDialogDismissed(dialogId: Int) {}

    companion object {
        private const val DIALOG_ID_THEME_COLOR = 401

        /**
         * 跨实例重建合并窗口。
         *
         * 一次日夜主题切换会触发两条重建路径：AppCompat 的 `setDefaultNightMode`
         * 强制系统级 relaunch（新实例创建），以及 [io.legado.app.help.config.ThemeConfig.notifyRecreate]
         * 防抖窗口（1500ms）结束后才广播的 RECEIVE。两条重建相隔约 1.5s，
         * 若都放行，重建出的 Compose 实例存在重组冻结（输入通但重组/重绘不再调度，
         * 第 8 轮模拟器实测）→ 页面定格仅局部可点。窗口略大于防抖延迟即可兜住
         * 迟到广播，配合 [recreate] 走「重启替代原地重建」，保证只经历一次、
         * 且是健康的全新启动。
         */
        private const val RECREATE_IGNORE_MS = 2000L

        /** 最近一次因主题切换（日夜模式/系统翻转）而新创建的实例时刻 */
        @Volatile
        private var instanceCreateTime = 0L
    }
}
