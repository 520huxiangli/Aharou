package com.aharou.feature.pet

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.aharou.core.util.FileLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 桌宠「视频角色」的渲染层。
 *
 * 素材是**左右拼合**的 mp4（格式来自 dsh-pet：宽是显示宽的两倍，左半边彩色画面、
 * 右半边灰度图的 R 通道当 alpha）——H.264 不支持透明通道，所以把透明度单独塞在右半边。
 * 这里照它的做法把两半合回一张带透明通道的画面再画出来，桌宠看起来仍是「透明立绘」，
 * 而不是一个视频方块。
 *
 * 渲染自建 GL 线程与 EGL（输出目标是 TextureView 自己的 SurfaceTexture），播放交给
 * [MediaPlayer]（渲染到内部的 OES 纹理），不引第三方播放器依赖。
 */
class PetVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    /** 该播的素材（assets 内路径）；同段重复调用只当继续播，换段才重开播放器。 */
    private var requested: String? = null
    private var playingPath: String? = null

    /** 角色朝左时整帧水平镜像。 */
    @Volatile
    private var mirrored = false

    private var player: MediaPlayer? = null
    private var render: GlRender? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        // 不透明的话透明区域会被涂黑，桌宠就成了方块
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setMirrored(value: Boolean) {
        if (mirrored == value) return
        mirrored = value
        render?.requestRender()
    }

    /** 播一段视频；surface 还没就绪时先记下来，等 GL 输入纹理建好再补播。 */
    fun play(assetPath: String) {
        requested = assetPath
        val input = render?.inputTexture ?: return
        startPlayer(assetPath, input)
    }

    fun release() {
        requested = null
        stopPlayer()
        render?.release()
        render = null
    }

    // ── TextureView 生命周期 ────────────────────────────────
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val r = GlRender(surface) { mirrored }
        render = r
        r.onInputReady = { input -> requested?.let { startPlayer(it, input) } }
        r.setViewport(width, height)
        r.start()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        render?.setViewport(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopPlayer()
        render?.release()
        render = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    // ── 播放器 ──────────────────────────────────────────────
    private fun startPlayer(assetPath: String, input: SurfaceTexture) {
        if (playingPath == assetPath && player != null) return
        stopPlayer()
        val mp = MediaPlayer()
        try {
            context.assets.openFd(assetPath).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setSurface(Surface(input))
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            player = mp
            playingPath = assetPath
        } catch (e: Exception) {
            FileLogger.w(TAG, "桌宠视频播放失败：$assetPath", e)
            runCatching { mp.release() }
        }
    }

    private fun stopPlayer() {
        val mp = player ?: return
        player = null
        playingPath = null
        runCatching { mp.setOnPreparedListener(null) }
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.release() }
    }

    /**
     * GL 渲染：自建 EGL 环境，输出面是 [outputTexture]（TextureView 的 SurfaceTexture），
     * 视频帧先解码进 [inputTexture]（OES 纹理），再由合成 shader 画到输出面。
     */
    private class GlRender(
        private val outputTexture: SurfaceTexture,
        private val mirrored: () -> Boolean
    ) {
        var onInputReady: ((SurfaceTexture) -> Unit)? = null

        @Volatile
        var inputTexture: SurfaceTexture? = null
            private set

        private val thread = HandlerThread("PetVideoGl")
        private var handler: Handler? = null

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var program = 0
        private var textureId = 0
        private var aPosition = 0
        private var aTexCoord = 0
        private var uTexMatrix = 0
        private var uMirror = 0
        private var uTexture = 0

        @Volatile
        private var viewportW = 1

        @Volatile
        private var viewportH = 1

        /** 有没有还没画上去的新帧；视频暂停/没帧时就不必重绘。 */
        @Volatile
        private var framePending = false

        private val texMatrix = FloatArray(16)
        private val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        private val quad: FloatBuffer = ByteBuffer
            .allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(vertices); position(0) }

        fun setViewport(w: Int, h: Int) {
            viewportW = w.coerceAtLeast(1)
            viewportH = h.coerceAtLeast(1)
            requestRender()
        }

        fun start() {
            thread.start()
            val h = Handler(thread.looper)
            handler = h
            h.post {
                // GL 初始化可能失败（SurfaceTexture 失效、窗口重建等），而它跑在 GL 线程上，
                // 未捕获异常会直接杀掉进程——失败就记日志并停用视频渲染。
                val ready = runCatching {
                    setupEgl()
                    setupGl()
                }.onFailure { e -> FileLogger.w(TAG, "桌宠视频 GL 初始化失败，已停用视频渲染", e) }.isSuccess
                if (!ready) return@post
                // 输入纹理建好后再交给播放器（MediaPlayer 要有 surface 才能起播）
                inputTexture = SurfaceTexture(textureId).apply {
                    setOnFrameAvailableListener {
                        framePending = true
                        requestRender()
                    }
                }
                onInputReady?.invoke(inputTexture!!)
            }
        }

        fun requestRender() {
            handler?.post { draw() }
        }

        fun release() {
            val h = handler ?: return
            h.post {
                inputTexture?.release()
                inputTexture = null
                if (program != 0) GLES20.glDeleteProgram(program)
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                    )
                    EGL14.eglDestroySurface(display, surface)
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
                display = EGL14.EGL_NO_DISPLAY
                context = EGL14.EGL_NO_CONTEXT
                surface = EGL14.EGL_NO_SURFACE
                thread.quitSafely()
            }
        }

        private fun setupEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay 失败" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize 失败" }

            // 输出到 TextureView：必须带 alpha 位，否则合成出来的透明区域会丢
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(
                EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0,
            ) { "eglChooseConfig 失败" }
            val config = configs[0]!!

            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext 失败" }
            surface = EGL14.eglCreateWindowSurface(
                display, config, outputTexture, intArrayOf(EGL14.EGL_NONE), 0,
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface 失败" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent 失败" }
        }

        private fun setupGl() {
            val vertex = """
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                uniform mat4 uTexMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * aTexCoord).xy;
                }
            """.trimIndent()

            // 左半边取颜色，右半边取 R 通道当透明度——与 dsh-pet 的合成方式一致
            val fragment = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES uTexture;
                uniform float uMirror;
                varying vec2 vTexCoord;
                void main() {
                    float x = uMirror > 0.5 ? 1.0 - vTexCoord.x : vTexCoord.x;
                    vec4 rgb = texture2D(uTexture, vec2(x * 0.5, vTexCoord.y));
                    float a = texture2D(uTexture, vec2(0.5 + x * 0.5, vTexCoord.y)).r;
                    gl_FragColor = vec4(rgb.rgb, a);
                }
            """.trimIndent()

            val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
            GLES20.glShaderSource(vs, vertex)
            GLES20.glCompileShader(vs)
            val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
            GLES20.glShaderSource(fs, fragment)
            GLES20.glCompileShader(fs)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)

            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uMirror = GLES20.glGetUniformLocation(program, "uMirror")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )
        }

        private fun draw() {
            if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE) return
            GLES20.glViewport(0, 0, viewportW, viewportH)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val input = inputTexture
            if (input != null && framePending) {
                framePending = false
                runCatching {
                    input.updateTexImage()
                    input.getTransformMatrix(texMatrix)
                    GLES20.glUseProgram(program)
                    GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
                    GLES20.glUniform1f(uMirror, if (mirrored()) 1f else 0f)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                    GLES20.glUniform1i(uTexture, 0)

                    quad.position(0)
                    GLES20.glEnableVertexAttribArray(aPosition)
                    GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
                    quad.position(2)
                    GLES20.glEnableVertexAttribArray(aTexCoord)
                    GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisableVertexAttribArray(aPosition)
                    GLES20.glDisableVertexAttribArray(aTexCoord)
                }.onFailure { e ->
                    FileLogger.w(TAG, "桌宠视频合成失败", e)
                }
            }
            // 没有新帧也要把上一帧交出去，否则 TextureView 上是空的
            runCatching { EGL14.eglSwapBuffers(display, surface) }
                .onFailure { e -> FileLogger.w(TAG, "桌宠视频交换缓冲失败", e) }
        }
    }

    private companion object {
        const val TAG = "PetVideoView"
    }
}
