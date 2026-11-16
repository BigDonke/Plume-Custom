package dev.plume

import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
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
 * The player decodes into a texture ([inputs]), so the frames reach the screen as ordinary pixels. A SurfaceView
 * that the player drew into directly went to the TV's video plane, whose picture processing lifted the shadows
 * (level 16 showed as 32), and a TextureView made the whole home screen redraw on every video frame. Here the home
 * screen redraws only when it changes; [GlassLayer] sends where its tiles are ([setTiles]) and this draws the glass
 * under them, from the same video frame.
 *
 * Each read of the decoder's frame is slow on the TV's GPU (a Mali-G52), so every pass reads it as few times as it
 * can. A blur under the glass, a copy of the frame or more taps per pixel each cost 10 ms or more a frame there.
 *
 * The surface keeps the last frame after the player stops, and it is drawn again when the view comes back.
 *
 * There are two inputs, so a video can loop with a crossfade: the next loop plays into the other one, and [show]
 * blends the two while the old loop runs out.
 *
 * While the video plays, only a new frame from the front input draws. A tile move or a fade step waits for that
 * frame, at most one video frame later. Drawing on each tile move doubled the GPU work during a focus move, and the
 * TV then showed video frames late. Each frame is also given its time on screen ([present]), so a busy GPU no
 * longer pushes it to the next refresh.
 */
internal class LiquidVideo(val view: SurfaceView) : SurfaceHolder.Callback {
    private val thread = HandlerThread("liquid video").apply { start() }
    private val gl = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var idle: EGLSurface = EGL14.EGL_NO_SURFACE // 1x1, current while there is no window
    private val textures = IntArray(2)
    private val frames = arrayOfNulls<SurfaceTexture>(2)
    private var videoProgram = 0
    private var glassProgram = 0
    private var fadeVideoProgram = 0
    private var fadeGlassProgram = 0
    private val texMatrix = arrayOf(FloatArray(16), FloatArray(16))
    private val hasFrame = BooleanArray(2)
    private val newFrame = BooleanArray(2)
    private var front = 0 // the input on screen
    private var fadeFrom = -1 // the input fading out, or -1
    private var fadeStart = 0L
    private var fadeMs = 0L
    private var width = 0
    private var height = 0
    private val queued = AtomicBoolean(false)
    @Volatile private var tiles = FloatArray(0)
    @Volatile private var tint = floatArrayOf(0f, 0f, 0f)
    @Volatile private var dp = 2f
    @Volatile private var frameAt = 0L // uptime of the newest frame from either input
    private var frontAt = 0L // uptime of the newest frame from the front input
    private var arrivedNs = 0L // System.nanoTime when the front input's newest frame was taken
    private var lastStamp = 0L // that frame's decoder timestamp
    private var interval = 0.0 // the decoder's frame interval, smoothed, in ns
    private var target = 0L // when the last frame was asked to show
    private var vsync = 0L // a recent vsync, in ns
    private var vsyncAsked = false
    @Volatile private var period = 16_666_667L // one refresh, in ns

    /** Where the players draw, two of them. Valid until [release]. */
    lateinit var inputs: List<Surface>
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
        if (!playing()) requestDraw()
    }

    /** The glass's tint, an opaque colour. Each tile says how much of it covers the video. */
    fun setTint(colour: Int) {
        val t = floatArrayOf((colour shr 16 and 0xFF) / 255f, (colour shr 8 and 0xFF) / 255f, (colour and 0xFF) / 255f)
        if (t.contentEquals(tint)) return
        tint = t
        if (!playing()) requestDraw()
    }

    /** Shows what [slot] of [inputs] draws. With [fadeMs] above 0 it crossfades from the input shown now. */
    fun show(slot: Int, fadeMs: Long) {
        gl.post {
            if (fadeMs > 0 && slot != front && hasFrame[front]) {
                fadeFrom = front
                fadeStart = SystemClock.uptimeMillis()
                this.fadeMs = fadeMs
            } else fadeFrom = -1
            front = slot
            if (!playing()) draw()
        }
    }

    fun release() {
        view.holder.removeCallback(this)
        gl.post {
            frames.forEach { it?.release() }
            inputs.forEach { it.release() }
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

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        view.display?.refreshRate?.takeIf { it > 1f }?.let { period = (1e9 / it).toLong() }
        surfaceSized(w, h)
    }

    private fun surfaceSized(w: Int, h: Int) = onGl {
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

    /** Frames keep coming, so the next one draws whatever changed. */
    private fun playing() = SystemClock.uptimeMillis() - frameAt < 100

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

        GLES20.glGenTextures(2, textures, 0)
        inputs = List(2) { slot ->
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[slot])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(textures[slot])
            st.setOnFrameAvailableListener({
                val now = SystemClock.uptimeMillis()
                frameAt = now
                if (slot == front) frontAt = now
                newFrame[slot] = true
                // In a crossfade both players send frames. Only the front one's draw: drawing on both doubled the draws,
                // at about 16 ms each. The other's frame is still taken at once, or its player stalls waiting for its
                // buffers back, and when the front one pauses too the video freezes for good.
                if (slot == front || now - frontAt > 100) requestDraw() else take(slot)
            }, gl)
            frames[slot] = st
            Surface(st)
        }

        videoProgram = program(VERTEX, fragment(VIDEO, fade = false))
        glassProgram = program(VERTEX, fragment(GLASS, fade = false))
        fadeVideoProgram = program(VERTEX, fragment(VIDEO, fade = true))
        fadeGlassProgram = program(VERTEX, fragment(GLASS, fade = true))
    }

    private fun draw() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        val fresh = newFrame[front]
        for (slot in 0..1) take(slot)
        // The new loop's frames keep coming during a fade, and each one draws the next step.
        if (fadeFrom >= 0 && (!hasFrame[fadeFrom] || SystemClock.uptimeMillis() - fadeStart >= fadeMs)) fadeFrom = -1
        val fading = fadeFrom >= 0
        if (window == EGL14.EGL_NO_SURFACE || width == 0 || height == 0) return
        EGL14.eglMakeCurrent(display, window, window, context)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (hasFrame[front]) {
            GLES20.glDisable(GLES20.GL_BLEND)
            val video = if (fading) fadeVideoProgram else videoProgram
            use(video)
            rect(video, 0f, 0f, width.toFloat(), height.toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            val list = tiles
            if (list.isNotEmpty()) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                val glassProgram = if (fading) fadeGlassProgram else glassProgram
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
        if (fresh) present()
        EGL14.eglSwapBuffers(display, window)
    }

    /**
     * Takes [slot]'s new frame into its texture. Taken even without a window, or the player stalls waiting for its
     * buffers back.
     */
    private fun take(slot: Int) {
        val st = frames[slot] ?: return
        if (display == EGL14.EGL_NO_DISPLAY || !newFrame[slot]) return
        EGL14.eglMakeCurrent(display, if (window != EGL14.EGL_NO_SURFACE) window else idle,
            if (window != EGL14.EGL_NO_SURFACE) window else idle, context)
        newFrame[slot] = false
        try { st.updateTexImage() } catch (_: RuntimeException) { return }
        st.getTransformMatrix(texMatrix[slot])
        hasFrame[slot] = true
        if (slot == front) took(st.timestamp)
    }

    /** Notes when the front input's frame arrived and how far apart the decoder spaces its frames. */
    private fun took(stamp: Long) {
        arrivedNs = System.nanoTime()
        val gap = stamp - lastStamp
        if (lastStamp > 0 && gap in 5_000_000L..200_000_000L) interval = if (interval == 0.0) gap.toDouble() else interval * 0.9 + gap * 0.1
        lastStamp = stamp
        if (!vsyncAsked && arrivedNs - vsync > 1_000_000_000L) {
            vsyncAsked = true
            Choreographer.getInstance().postFrameCallback { vsync = it; vsyncAsked = false }
        }
    }

    /**
     * Asks for the frame about to be swapped to show two refreshes after it arrived. Shown as soon as possible, a
     * frame missed its refresh whenever the home screen kept the GPU busy.
     *
     * A video whose frames last a whole number of refreshes (30 fps on 60 Hz) keeps an exact cadence: each frame shows
     * that many refreshes after the last one. The decoder's timing wanders by about 10 ms over a minute. Following it
     * frame by frame made frames near a refresh boundary flip between two refreshes. The cadence moves only when a
     * frame would show less than 1.3 or more than 3.4 refreshes after it arrived. Other frame rates (24 fps) show two
     * refreshes after they arrive.
     */
    private fun present() {
        val p = period
        val steps = Math.round(interval / p)
        val whole = steps >= 1 && kotlin.math.abs(interval / p - steps) < 0.1 && vsync > 0
        var t = target + steps * p
        val ahead = t - arrivedNs
        if (!whole) t = arrivedNs + 2 * p
        else if (target == 0L || ahead < p * 13 / 10 || ahead > p * 34 / 10) {
            // Back to two refreshes after arrival, in the middle of a refresh so small timing errors don't move it.
            val n = Math.round((arrivedNs + 2 * p - vsync - p / 2).toDouble() / p)
            t = vsync + n * p + p / 2
        }
        target = t
        EGLExt.eglPresentationTimeANDROID(display, window, t)
    }

    private fun use(p: Int) {
        GLES20.glUseProgram(p)
        val pos = GLES20.glGetAttribLocation(p, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[front])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uVideo"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uTex"), 1, false, texMatrix[front], 0)
        if (fadeFrom >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[fadeFrom])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uVideo2"), 1)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uTex2"), 1, false, texMatrix[fadeFrom], 0)
            val mix = ((SystemClock.uptimeMillis() - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uMix"), mix * mix * (3 - 2 * mix)) // eased at both ends
        }
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

        /**
         * The start of both fragment shaders: video() reads the frame at a pixel. With FADE it blends in the input
         * that fades out by uMix, so the glass and the video under it fade together.
         */
        private const val SAMPLE = """
uniform samplerExternalOES uVideo;
uniform mat4 uTex;
uniform vec2 uScreen;
varying vec2 vPx;
#ifdef FADE
uniform samplerExternalOES uVideo2;
uniform mat4 uTex2;
uniform float uMix;
#endif

vec3 video(vec2 px) {
    px = clamp(px, vec2(0.5), uScreen - 0.5);
    vec4 at = vec4(px.x / uScreen.x, 1.0 - px.y / uScreen.y, 0.0, 1.0);
    vec3 c = texture2D(uVideo, (uTex * at).xy).rgb;
#ifdef FADE
    c = mix(texture2D(uVideo2, (uTex2 * at).xy).rgb, c, uMix);
#endif
    return c;
}
"""

        private fun fragment(body: String, fade: Boolean) =
            "#extension GL_OES_EGL_image_external : require\nprecision highp float;\n" +
                (if (fade) "#define FADE\n" else "") + SAMPLE + body

        private const val VIDEO = """
void main() {
    gl_FragColor = vec4(video(vPx), 1.0);
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
uniform vec4 uBox;
uniform float uCorner;
uniform float uDp;
uniform float uAlpha;
uniform vec4 uTint;
uniform float uZoom;

float roundBox(vec2 p, vec2 h, float r) {
    vec2 q = abs(p) - h + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
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
