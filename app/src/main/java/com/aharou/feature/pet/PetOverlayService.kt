package com.aharou.feature.pet

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aharou.MainActivity
import com.aharou.R
import com.aharou.accessibility.AharouAccessibilityService
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.runtime.AgentRuntimeStatus
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aharou.feature.agent.presentation.component.PendingUploadAttachment
import com.aharou.feature.agent.presentation.component.appendAttachmentsToRequest
import com.aharou.feature.agent.presentation.component.toAgentAttachments
import com.aharou.feature.agent.presentation.component.toAgentImages
import com.aharou.feature.voice.call.VoiceCallService
import com.aharou.feature.voice.call.VoiceCallSession
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 小染悬浮层服务：屏幕上的**唯一**悬浮实体。
 *
 * 吞掉了原「状态胶囊」的全部职责——胶囊的显隐门控、通话字幕、点头像开关通话，现在都落在
 * [PetOverlay] 身上（字幕进气泡、通话开关进径向菜单的麦克风）。
 *
 * 出现条件（并集）：
 *  - 通话中 → 一定出现（隐藏状态下也不例外，否则通话没有界面可用）；
 *  - 没被隐藏 且（常驻待机 ||（Agent 在跑 且 App 不在前台））。
 *
 * 两个开关的分工：设置页的「显示小染」决定**服务是否运行**（关掉就彻底停）；
 * 径向菜单里的「隐藏」只是让她从桌面消失（服务留着，通知变成「点此叫回」的入口）。
 */
@AndroidEntryPoint
class PetOverlayService : Service() {

    companion object {
        private const val TAG = "PetOverlayService"

        /** 截屏前先把自己收起来的等待时长：够系统把悬浮窗的帧丢掉就行。 */
        private const val CAPTURE_HIDE_MS = 140L

        /** 截图落盘的 JPEG 质量：压得太狠字就看不清，太高又会把会话拖肿。 */
        private const val SHOT_QUALITY = 85
        private const val CHANNEL_ID = "aharou_pet"
        private const val NOTIFICATION_ID = 4102

        /** 旧版「状态胶囊」的开关存储，仅用于升级时继承一次（见 [isEnabled]）。 */
        private const val LEGACY_PREFS = "aharou_overlay_prefs"
        private const val LEGACY_KEY_ENABLED = "enabled"

        private const val ACTION_SET_SIZE = "com.aharou.feature.pet.action.SET_SIZE"
        private const val ACTION_SET_ALWAYS = "com.aharou.feature.pet.action.SET_ALWAYS"
        private const val ACTION_SHOW = "com.aharou.feature.pet.action.SHOW"
        private const val ACTION_HIDE = "com.aharou.feature.pet.action.HIDE"
        private const val ACTION_PASS_OFF = "com.aharou.feature.pet.action.PASS_OFF"
        private const val ACTION_SET_PASS = "com.aharou.feature.pet.action.SET_PASS"
        private const val EXTRA_PASS = "pass"

        /** 任务至少跑了这么久才值得播报「干完了」，免得短问答也叮一下。 */
        private const val DONE_MIN_MS = 20_000L

        /** 挂在那儿等你确认多久后响一声。 */
        private const val WAIT_REMIND_MS = 20_000L

        /** 两次「带记忆生成台词」之间的最小间隔。 */
        private const val LINE_COOLDOWN_MS = 8_000L

        /** 生成一句台词的超时：超了就退回写死的台词，不能让她干张着嘴。 */
        private const val LINE_TIMEOUT_MS = 12_000L
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_ALWAYS = "always"

        /**
         * 服务是否已在本进程内运行。
         *
         * 用于「开关开着但服务没跑」的自愈判断：装包或进程被杀后服务不会自动重建，
         * 用户看到的就是开关亮着却不生效。
         */
        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        fun isEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(PetOverlay.KEY_ENABLED)) {
                return prefs.getBoolean(PetOverlay.KEY_ENABLED, false)
            }
            // 首次读：从旧版「状态胶囊」的开关继承一次。升级前开着（那时她只作为胶囊偶尔
            // 出现）的，升级后接着常驻，不然老用户会以为小染自己关了。继承后写回，
            // 之后就走上面的分支。
            val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getBoolean(LEGACY_KEY_ENABLED, false)
            prefs.edit().putBoolean(PetOverlay.KEY_ENABLED, legacy).apply()
            return legacy
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PetOverlay.KEY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
            runCatching {
                // 用 startForegroundService：调用方可能是广播接收器（见 PetRestartReceiver），
                // 后台走 startService 在 Android 8+ 会抛异常。
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PetOverlayService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, PetOverlayService::class.java))
            }
        }

        /** 改尺寸：先落盘（服务没跑时下次生效），服务在跑就顺带通知它换图。 */
        fun applySize(context: Context, size: PetOverlay.Size) {
            PetOverlay.writeSize(context, size)
            if (!running) return
            send(context, ACTION_SET_SIZE) { it.putExtra(EXTRA_SIZE, size.key) }
        }

        fun applyAlways(context: Context, always: Boolean) {
            context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PetOverlay.KEY_ALWAYS, always).apply()
            if (!running) return
            send(context, ACTION_SET_ALWAYS) { it.putExtra(EXTRA_ALWAYS, always) }
        }

        /** 改摆件模式：先落盘（服务没跑时下次生效），在跑就让她重设窗口属性。 */
        fun applyPassThrough(context: Context, on: Boolean) {
            PetOverlay.writePassThrough(context, on)
            if (!running) return
            send(context, ACTION_SET_PASS) { it.putExtra(EXTRA_PASS, on) }
        }

        private fun send(
            context: Context,
            action: String,
            decorate: (Intent) -> Unit = {},
        ) {
            runCatching {
                val intent = Intent(context, PetOverlayService::class.java).setAction(action)
                decorate(intent)
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectorJob: Job? = null
    private lateinit var overlay: PetOverlay
    private val appForeground = MutableStateFlow(true)

    private val always = MutableStateFlow(false)
    private val hidden = MutableStateFlow(false)

    /** 通话状态要从服务里拿（跨进程级的单例）。 */
    @Inject
    internal lateinit var callSession: VoiceCallSession

    /** 两个审批管理器：用来知道她是不是正卡在等主人点确认。 */
    @Inject
    internal lateinit var toolPermissionManager: ToolPermissionManager

    @Inject
    internal lateinit var planApprovalManager: PlanApprovalManager

    /** 带记忆生成台词的那一半。 */
    @Inject
    internal lateinit var petVoice: PetVoice

    /** 截图落盘要写工作区。 */
    @Inject
    internal lateinit var fileAccess: FileAccessProvider

    /** 截图直接发会话：跟语音通话共用同一条轮次编排。 */
    @Inject
    internal lateinit var agentTurnRunner: AgentTurnRunner

    private var remindJob: Job? = null
    private var tone: ToneGenerator? = null

    /** 上一帧她是不是在屏幕上：用来抓「刚现身」那一刻报每日一句。 */
    private var wasShown = false

    /** 上次向模型要台词的时间：冷却用，免得连点她一直烧模型。 */
    private var lastLineAt = 0L

    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appForeground.value = true
        }

        override fun onStop(owner: LifecycleOwner) {
            appForeground.value = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ensureChannel()
        // targetSdk 34 起 startForeground 必须带类型（与 manifest 一致），否则抛
        // MissingForegroundServiceTypeException。
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.pet_notification_text)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        overlay = PetOverlay(this).also { pet ->
            pet.onMenuAction = { id ->
                when (id) {
                    PetRadialMenu.ID_CALL -> toggleCall()
                    PetRadialMenu.ID_HIDE -> setHidden(true)
                    PetRadialMenu.ID_PASS -> setPassThrough(true)
                    PetRadialMenu.ID_SHOT -> captureScreen()
                }
            }
            pet.onSpeakRequest = { requestLine() }
        }
        always.value = prefs().getBoolean(PetOverlay.KEY_ALWAYS, true)
        hidden.value = prefs().getBoolean(PetOverlay.KEY_HIDDEN, false)
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        // 启动时预拉天气并写入缓存，现身时直接用缓存而不是当场拉网络
        scope.launch { PetDailyBrief.prefetch(this@PetOverlayService) }
        collectorJob = scope.launch {
            combine(
                combine(
                    AgentRuntimeStatus.state,
                    appForeground,
                    VoiceCallService.running,
                    callSession.state,
                    callSession.displayText,
                ) { status, foreground, callRunning, callState, displayText ->
                    Core(status, foreground, callRunning, callState, displayText)
                },
                always,
                hidden,
            ) { core, alwaysNow, hiddenNow ->
                Snapshot(
                    status = core.status,
                    foreground = core.foreground,
                    callRunning = core.callRunning,
                    callState = core.callState,
                    displayText = core.displayText,
                    always = alwaysNow,
                    hidden = hiddenNow,
                )
            }.collect { snap -> render(snap) }
        }
        watchAgent()
        watchApproval()
    }

    /** 任务跑完（且跑了足够久）就报一句。 */
    private fun watchAgent() {
        scope.launch {
            var running = false
            var since = 0L
            AgentRuntimeStatus.state.collect { state ->
                val now = System.currentTimeMillis()
                if (state.active && !running) {
                    running = true
                    since = now
                } else if (!state.active && running) {
                    running = false
                    if (now - since >= DONE_MIN_MS) {
                        overlay.say(getString(R.string.pet_agent_done))
                    }
                }
            }
        }
    }

    /**
     * 卡在等确认（工具审批 / 计划审查）超过阈值就响一声并冒一句。
     *
     * 只提醒一次：确认被点掉后状态回 false，计时器归零，下次再挂再提醒。
     */
    private fun watchApproval() {
        scope.launch {
            combine(
                toolPermissionManager.awaitingSessionIds,
                planApprovalManager.pendingApproval,
            ) { awaiting, plan -> awaiting.isNotEmpty() || plan != null }
                .distinctUntilChanged()
                .collect { waiting ->
                    remindJob?.cancel()
                    remindJob = null
                    if (!waiting) return@collect
                    remindJob = scope.launch {
                        delay(WAIT_REMIND_MS)
                        overlay.say(getString(R.string.pet_agent_waiting))
                        ringBell()
                    }
                }
        }
    }

    /**
     * 每天第一次现身时报一句：节日祝福 / 今日天气与注意事项。
     *
     * 拿不到就什么都不说（没授权定位、没网、不是节日），不报错也不纠缠。
     */
    private fun announceBrief() {
        scope.launch {
            val line = PetDailyBrief.take(this@PetOverlayService) ?: return@launch
            overlay.say(line, holdMs = 9_000L)
        }
    }

    /**
     * 点她说话：带记忆去问一句。
     *
     * 生成失败、超时、或刚生成过（冷却，免得连点她一直烧模型）都退化成写死的台词——
     * 宁可说老词，不能张嘴却没有话。
     */
    private fun requestLine() {
        val now = System.currentTimeMillis()
        if (now - lastLineAt < LINE_COOLDOWN_MS) {
            overlay.speakStaticLine()
            return
        }
        lastLineAt = now
        scope.launch {
            val scene = getString(R.string.pet_scene_tap)
            val line = withTimeoutOrNull(LINE_TIMEOUT_MS) { petVoice.line(scene) }
            if (line.isNullOrBlank()) {
                overlay.speakStaticLine()
            } else {
                PetMoodStore.markSaid(this@PetOverlayService, line)
                overlay.say(line, wave = true)
            }
        }
    }

    /** 摇铃：通知流量的短提示音，不占 TTS（避免和通话/朗读撞）。 */
    private fun ringBell() {
        runCatching {
            val player = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
                .also { tone = it }
            player.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        }
    }

    private fun render(snap: Snapshot) {
        if (!overlay.hasOverlayPermission()) {
            overlay.hide()
            notify(getString(R.string.pet_notification_no_permission))
            return
        }

        // 隐藏状态下只有通话能把她叫出来，否则「隐藏」等于把通话也一起断了。
        val working = snap.status.active && !snap.foreground
        val shouldShow = snap.callRunning || (!snap.hidden && (snap.always || working))

        if (!shouldShow) {
            wasShown = false
            overlay.hide()
            notify(
                if (snap.hidden) getString(R.string.pet_notification_hidden)
                else getString(R.string.pet_notification_text)
            )
            return
        }

        overlay.show()
        if (!wasShown) {
            wasShown = true
            announceBrief()
        }
        val persistent = when {
            snap.callRunning && snap.displayText.isNotBlank() -> snap.displayText
            snap.callRunning -> getString(callStateLabel(snap.callState))
            working && snap.status.statusText.isNotBlank() -> snap.status.statusText
            working -> getString(R.string.floating_thinking)
            else -> ""
        }
        overlay.showStatus(persistent)
        overlay.setCallState(snap.callRunning)

        val notificationText = when {
            snap.callRunning -> getString(R.string.pet_notification_call)
            working -> getString(R.string.pet_notification_working)
            else -> getString(R.string.pet_notification_text)
        }
        notify(notificationText)
    }

    private fun notify(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun prefs() = getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE)

    /** 通话开关：开着就挂断，关着就接通；没录音权限就先跟她说清楚。 */
    private fun toggleCall() {
        if (VoiceCallService.isRunning()) {
            VoiceCallService.stop(this)
            return
        }
        if (!canRecord()) {
            // 没录音权限她张不了嘴：明说一句，并把 App 叫到前台去设置里授权
            overlay.say(getString(R.string.pet_no_mic), holdMs = 6_000L)
            openApp()
            return
        }
        VoiceCallService.start(this)
    }

    private fun canRecord(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    /**
     * 菜单里的「截屏」：截当前屏幕，落成工作区附件，直接发进当前会话。
     *
     * 她是悬浮窗——不先把自己收起来，截出来画面里就有只桌宠，AI 会跑去解读它。
     */
    private fun captureScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            overlay.say(getString(R.string.pet_shot_unsupported), holdMs = 6_000L)
            return
        }
        val service = AharouAccessibilityService.getInstance()
        if (service == null) {
            overlay.say(getString(R.string.pet_shot_no_a11y), holdMs = 6_000L)
            openApp()
            return
        }
        scope.launch {
            overlay.setCaptureHidden(true)
            delay(CAPTURE_HIDE_MS)
            val shot = withContext(Dispatchers.IO) { service.captureScreenshot() }
            overlay.setCaptureHidden(false)

            val bitmap = shot.bitmap
            val pending = bitmap?.let { withContext(Dispatchers.IO) { saveShot(it) } }
            bitmap?.recycle()
            if (pending == null) {
                FileLogger.w(TAG, "截图失败：${shot.errorCode} ${shot.errorMessage}")
                overlay.say(getString(R.string.pet_shot_failed), holdMs = 6_000L)
                return@launch
            }
            overlay.say(getString(R.string.pet_shot_sent), holdMs = 5_000L)
            ringBell()
            sendShotToSession(pending)
        }
    }

    /** 截图存成聊天附件（跟分享文件进输入栏是同一套落盘约定）。 */
    private fun saveShot(bitmap: Bitmap): PendingUploadAttachment? = runCatching {
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, out)
            out.toByteArray()
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "shot_$stamp.jpg"
        val containerPath = "${WorkspacePathMapper.CONTAINER_ROOT}/.aharou/attachments/$fileName"
        fileAccess.writeBytes(containerPath, bytes, overwrite = true)
        val local = fileAccess.copyToLocal(containerPath)
        PendingUploadAttachment(
            fileName = fileName,
            containerPath = containerPath,
            localPath = local.absolutePath,
            mimeType = "image/jpeg",
            sizeBytes = bytes.size.toLong(),
            image = AgentImage(
                mimeType = "image/jpeg",
                base64Data = Base64.getEncoder().encodeToString(bytes),
                path = containerPath,
            ),
        )
    }.onFailure { FileLogger.w(TAG, "截图落盘失败：${it.message}") }.getOrNull()

    /**
     * 直接发进「最近聊过的会话」（说话和通话用的都是它），不依赖聊天界面开着。
     * 事件流照常收下来让这一轮跑完，界面上看不到进度就看不到，消息与回复都会落库。
     */
    private fun sendShotToSession(pending: PendingUploadAttachment) {
        scope.launch {
            runCatching {
                val root = agentTurnRunner.currentWorkspacePath()
                val sessionId = agentTurnRunner.ensureSession(root)
                if (sessionId.isBlank()) return@runCatching
                val text = appendAttachmentsToRequest(
                    this@PetOverlayService,
                    getString(R.string.pet_shot_message),
                    listOf(pending),
                )
                agentTurnRunner.run(
                    AgentTurnRequest(
                        sessionId = sessionId,
                        text = text,
                        projectRoot = root,
                        inputImages = listOf(pending).toAgentImages(),
                        inputAttachments = listOf(pending).toAgentAttachments(),
                    )
                ).collect { }
            }.onFailure { FileLogger.w(TAG, "截图发会话失败：${it.message}") }
        }
    }

    private fun setHidden(value: Boolean) {
        prefs().edit().putBoolean(PetOverlay.KEY_HIDDEN, value).apply()
        hidden.value = value
    }

    /**
     * 摆件模式：整窗不接触摸，只当装饰。
     *
     * 开了之后长按菜单也叫不出来了，所以通知里必须留一个「取消摆件」的退路（见 [buildNotification]）。
     */
    private fun setPassThrough(on: Boolean) {
        PetOverlay.writePassThrough(this, on)
        overlay.setPassThrough(on)
        notify(
            if (on) getString(R.string.pet_notification_pass_on)
            else getString(R.string.pet_notification_text)
        )
    }

    private fun callStateLabel(state: VoiceCallSession.State): Int = when (state) {
        VoiceCallSession.State.Listening -> R.string.voice_call_state_listening
        VoiceCallSession.State.WakeListening -> R.string.voice_wake_on
        VoiceCallSession.State.Thinking -> R.string.voice_call_state_thinking
        VoiceCallSession.State.Speaking -> R.string.voice_call_state_speaking
        VoiceCallSession.State.Preparing, VoiceCallSession.State.Idle ->
            R.string.voice_call_state_preparing
    }

    /** 合并后的显示状态：Agent 进度 + 通话 + 用户的两个开关。 */
    private data class Snapshot(
        val status: AgentRuntimeStatus.State,
        val foreground: Boolean,
        val callRunning: Boolean,
        val callState: VoiceCallSession.State,
        val displayText: String,
        val always: Boolean,
        val hidden: Boolean,
    )

    /** 前五个源头先合成一段，否则七路类型不同没法一次 combine（Flow 是不变的）。 */
    private data class Core(
        val status: AgentRuntimeStatus.State,
        val foreground: Boolean,
        val callRunning: Boolean,
        val callState: VoiceCallSession.State,
        val displayText: String,
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_SIZE -> {
                val key = intent.getStringExtra(EXTRA_SIZE)
                overlay.setSize(PetOverlay.Size.from(key))
            }
            ACTION_SET_ALWAYS -> {
                always.value = intent.getBooleanExtra(EXTRA_ALWAYS, false)
            }
            ACTION_SHOW -> {
                setHidden(false)
                overlay.show()
            }
            ACTION_HIDE -> setHidden(true)
            ACTION_PASS_OFF -> setPassThrough(false)
            ACTION_SET_PASS -> setPassThrough(intent.getBooleanExtra(EXTRA_PASS, false))
            else -> {
                if (!isEnabled(this)) {
                    // 开关被关掉但服务还在（例如被系统重建）——收干净。
                    FileLogger.i(TAG, "开关已关闭，服务自退")
                    stopSelf()
                    return START_STICKY
                }
                if (!overlay.isShown) overlay.show()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { overlay.onConfigurationChanged(newConfig) }
    }

    override fun onDestroy() {
        running = false
        collectorJob?.cancel()
        remindJob?.cancel()
        runCatching { tone?.release() }
        tone = null
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver) }
        runCatching { overlay.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.pet_notification_channel),
                    NotificationManager.IMPORTANCE_MIN,
                )
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val restore = PendingIntent.getService(
            this,
            0,
            Intent(this, PetOverlayService::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val passOff = PendingIntent.getService(
            this,
            2,
            Intent(this, PetOverlayService::class.java).setAction(ACTION_PASS_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.pet_title))
            .setContentText(text)
            .setContentIntent(if (hidden.value) restore else open)
            .setOngoing(true)
            .apply {
                if (PetOverlay.readPassThrough(this@PetOverlayService)) {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@PetOverlayService, R.drawable.ic_pet_pass),
                            getString(R.string.pet_notification_pass_off),
                            passOff,
                        ).build()
                    )
                }
            }
            .build()
    }
}
