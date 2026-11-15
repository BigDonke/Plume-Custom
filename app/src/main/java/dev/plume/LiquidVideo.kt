package dev.plume

import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The video wallpaper and the liquid glass over it, drawn with OpenGL on a thread of their own into a SurfaceView
 * behind the home screen.
 *
 * The player decodes into a texture ([input]), so the frames reach the screen as ordinary pixels. A SurfaceView
 * that the player drew into directly went to the TV's video plane, whose picture processing lifted the shadows
 * (level 16 showed as 32), and a TextureView made the whole home screen redraw on every video frame. Here the home
 * screen redraws only when it changes; [GlassLayer] sends where its tiles are ([setTiles]) and this draws the glass
 * under them, from the same video frame.
 *
 * Each read of the decoder's frame is slow on the TV's GPU (a Mali-G52), so every pass reads it as few times as it
 * can. A blur under the glass, a copy of the frame or more taps per pixel each cost 10 ms or more a frame there.
 *
 * The surface keeps the last frame after the player stops, and it is drawn again when the view comes back.
 */
internal class LiquidVideo(val view: SurfaceView) : SurfaceHolder.Callback {
    private val thread = HandlerThread("liquid video").apply { start() }
    private val gl = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var idle: EGLSurface = EGL14.EGL_NO_SURFACE // 1x1, current while there is no window
    private var texture = 0
    private var frames: SurfaceTexture? = null
    private var videoProgram = 0
    private var glassProgram = 0
    private val texMatrix = FloatArray(16)
    private var hasFrame = false
    private var newFrame = false
    private var width = 0
    private var height = 0
    private val queued = AtomicBoolean(false)
    @Volatile private var tiles = FloatArray(0)
    @Volatile private var tint = floatArrayOf(0f, 0f, 0f)
    @Volatile private var dp = 2f

    /** Where the player draws. Valid until [release]. */
    lateinit var input: Surface
        private set

    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    init {
        dp = view.resources.displayMetrics.density
        onGl { setUp() }
        view.holder.setFormat(PixelFormat.RGBA_8888)
        view.holder.addCallback(this)
    }

    /**
     * The glass tiles, [TILE] floats each: centre x and y, half width and half height, corner radius, alpha, the
     * clip (left, top, right, bottom), all in pixels of this view, how much of the tint covers the video, and how
     * much the glass magnifies (1 for none). They
     * are drawn in order, so a later one (the settings panel) covers the ones under it. The latest list wins.
     */
    fun setTiles(list: FloatArray) {
        if (list.contentEquals(tiles)) return
        tiles = list
        requestDraw()
    }

    /** The glass's tint, an opaque colour. Each tile says how much of it covers the video. */
    fun setTint(colour: Int) {
        val t = floatArrayOf((colour shr 16 and 0xFF) / 255f, (colour shr 8 and 0xFF) / 255f, (colour and 0xFF) / 255f)
        if (t.contentEquals(tint)) return
        tint = t
        requestDraw()
    }

    fun release() {
        view.holder.removeCallback(this)
        gl.post {
            frames?.release()
            input.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                if (idle != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, idle)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            thread.quitSafely()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = onGl {
        window = EGL14.eglCreateWindowSurface(display, config, holder.surface, intArrayOf(EGL14.EGL_NONE), 0)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = onGl {
        width = w
        height = h
        draw()
    }

    // Blocks until the thread lets go of the surface, as SurfaceHolder asks.
    override fun surfaceDestroyed(holder: SurfaceHolder) = onGl {
        EGL14.eglMakeCurrent(display, idle, idle, context)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        window = EGL14.EGL_NO_SURFACE
    }

    /** Runs [block] on the GL thread and waits for it (at most 2 s). */
    private fun onGl(block: () -> Unit) {
        val done = CountDownLatch(1)
        gl.post { try { block() } finally { done.countDown() } }
        done.await(2, TimeUnit.SECONDS)
    }

    private fun requestDraw() {
        if (queued.compareAndSet(false, true)) gl.post { queued.set(false); draw() }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0)
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        idle = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, idle, idle, context)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(texture)
        st.setOnFrameAvailableListener({ newFrame = true; requestDraw() }, gl)
        frames = st
        input = Surface(st)

        videoProgram = program(VERTEX, VIDEO)
        glassProgram = program(VERTEX, GLASS)
    }

    private fun draw() {
        val st = frames ?: return
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (newFrame) {
            // Taken even without a window, or the player stalls waiting for its buffers back.
            EGL14.eglMakeCurrent(display, if (window != EGL14.EGL_NO_SURFACE) window else idle,
                if (window != EGL14.EGL_NO_SURFACE) window else idle, context)
            newFrame = false
            try { st.updateTexImage() } catch (_: RuntimeException) { return }
            st.getTransformMatrix(texMatrix)
            hasFrame = true
        }
        if (window == EGL14.EGL_NO_SURFACE || width == 0 || height == 0) return
        EGL14.eglMakeCurrent(display, window, window, context)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (hasFrame) {
            GLES20.glDisable(GLES20.GL_BLEND)
            use(videoProgram)
            rect(videoProgram, 0f, 0f, width.toFloat(), height.toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            val list = tiles
            if (list.isNotEmpty()) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                use(glassProgram)
                val t = tint
                GLES20.glUniform1f(GLES20.glGetUniformLocation(glassProgram, "uDp"), dp)
                var i = 0
                while (i + TILE <= list.size) {
                    val cx = list[i]; val cy = list[i + 1]; val hw = list[i + 2]; val hh = list[i + 3]
                    val l = list[i + 6].toInt(); val tp = list[i + 7].toInt(); val r = list[i + 8].toInt(); val b = list[i + 9].toInt()
                    i += TILE
                    if (r <= l || b <= tp) continue
                    GLES20.glScissor(l, height - b, r - l, b - tp)
                    rect(glassProgram, cx - hw - 1f, cy - hh - 1f, cx + hw + 1f, cy + hh + 1f)
                    GLES20.glUniform4f(GLES20.glGetUniformLocation(glassProgram, "uBox"), cx, cy, hw, hh)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(glassProgram, "uCorner"), list[i - TILE + 4])
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(glassProgram, "uAlpha"), list[i - TILE + 5])
                    GLES20.glUniform4f(GLES20.glGetUniformLocation(glassProgram, "uTint"), t[0], t[1], t[2], list[i - TILE + 10])
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(glassProgram, "uZoom"), list[i - TILE + 11])
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }
            }
        }
        EGL14.eglSwapBuffers(display, window)
    }

    private fun use(p: Int) {
        GLES20.glUseProgram(p)
        val pos = GLES20.glGetAttribLocation(p, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uVideo"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uTex"), 1, false, texMatrix, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uScreen"), width.toFloat(), height.toFloat())
    }

    private fun rect(p: Int, l: Float, t: Float, r: Float, b: Float) =
        GLES20.glUniform4f(GLES20.glGetUniformLocation(p, "uRect"), l, t, r, b)

    private fun program(vertex: String, fragment: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("Plume", "shader: " + GLES20.glGetShaderInfoLog(it))
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(it)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("Plume", "program: " + GLES20.glGetProgramInfoLog(it))
        }
    }

    companion object {
        const val TILE = 12

        private const val VERTEX = """
attribute vec2 aPos;
uniform vec4 uRect;
uniform vec2 uScreen;
varying vec2 vPx;
void main() {
    vec2 px = mix(uRect.xy, uRect.zw, aPos);
    vPx = px;
    vec2 ndc = px / uScreen * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
}
"""

        private const val VIDEO = """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uVideo;
uniform mat4 uTex;
uniform vec2 uScreen;
varying vec2 vPx;
void main() {
    vec2 uv = (uTex * vec4(vPx.x / uScreen.x, 1.0 - vPx.y / uScreen.y, 0.0, 1.0)).xy;
    gl_FragColor = vec4(texture2D(uVideo, uv).rgb, 1.0);
}
"""

        /**
         * Clear liquid glass, after Apple's: no frost. The middle magnifies the video a little; a bevel along the rim
         * bends in the scene from just outside the pane, each colour a little more than the last, so the rim shows a
         * faint colour split. A hairline of light runs round the rim, brightest where it faces the light (top left)
         * and on the far corner, with a soft glow in the bevel. The rim's direction comes from the tile as a pill, so
         * it turns smoothly round the corners. Premultiplied output.
         */
        private const val GLASS = """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uVideo;
uniform mat4 uTex;
uniform vec2 uScreen;
uniform vec4 uBox;
uniform float uCorner;
uniform float uDp;
uniform float uAlpha;
uniform vec4 uTint;
uniform float uZoom;
varying vec2 vPx;

float roundBox(vec2 p, vec2 h, float r) {
    vec2 q = abs(p) - h + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

vec3 video(vec2 px) {
    px = clamp(px, vec2(0.5), uScreen - 0.5);
    vec2 uv = (uTex * vec4(px.x / uScreen.x, 1.0 - px.y / uScreen.y, 0.0, 1.0)).xy;
    return texture2D(uVideo, uv).rgb;
}

void main() {
    vec2 h = uBox.zw;
    vec2 p = vPx - uBox.xy;
    float d = roundBox(p, h, uCorner);
    float cover = 1.0 - smoothstep(-0.75, 0.75, d);
    if (cover <= 0.0) discard;
    float a = cover * uAlpha;
    float pill = min(h.x, h.y);
    float depth = max(-d, 0.0);
    float band = min(18.0 * uDp, pill);
    float e = 1.0 - clamp(depth / band, 0.0, 1.0);
    // Inside the bevel, glass that does not magnify shows the video as drawn under it, so only the tint goes on top.
    // This keeps the large settings panel cheap, as each read of the video is slow on this GPU.
    if (e <= 0.0 && uZoom == 1.0) {
        gl_FragColor = vec4(uTint.rgb, 1.0) * uTint.a * a;
        return;
    }
    vec2 g = vec2(roundBox(p + vec2(1.0, 0.0), h, pill) - roundBox(p - vec2(1.0, 0.0), h, pill),
                  roundBox(p + vec2(0.0, 1.0), h, pill) - roundBox(p - vec2(0.0, 1.0), h, pill));
    vec2 n = length(g) > 0.0001 ? normalize(g) : vec2(0.0, -1.0);
    vec2 base = uBox.xy + p / uZoom;
    vec2 pull = n * band * 1.0 * e * e;
    vec3 c = vec3(video(base + pull).r, video(base + pull * 1.08).g, video(base + pull * 1.16).b);
    vec2 toLight = normalize(vec2(-1.0, -1.4));
    float key = pow(max(dot(n, toLight), 0.0), 2.5);
    float fill = pow(max(dot(n, -toLight), 0.0), 2.5);
    float light = 0.12 + 1.0 * key + 0.55 * fill;
    float x = (depth - 1.0 * uDp) / (0.8 * uDp);
    float line = exp(-x * x);
    c = mix(c, uTint.rgb, uTint.a);
    c += vec3(0.7 * line * light + 0.12 * e * e * e * light);
    gl_FragColor = vec4(clamp(c, 0.0, 1.0) * a, a);
}
"""
    }
}
