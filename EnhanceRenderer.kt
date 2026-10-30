package com.dlsreal

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Pega a imagem capturada da tela e desenha por cima do jogo SÓ as diferenças
 * (brilho, sombras, reflexo...) como camada translúcida. Assim, mesmo que a camada
 * seja capturada de novo, o efeito não "explode" (cada passada tem ganho limitado).
 */
class EnhanceRenderer : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var enabled = false
    @Volatile var ao = 1f      // sombras de contato + vinheta
    @Volatile var spec = 1f    // brilho/metal/detalhe
    @Volatile var refl = 1f    // reflexo de chão molhado e água
    @Volatile var lightK = 1f  // luz dinâmica + cor
    @Volatile var metal = 1f   // metal: brilho anisotrópico, reflexo de ambiente, fresnel
    @Volatile var lamp = 1f    // lâmpadas/luzes: halo, rastro horizontal, luz nas superfícies
    @Volatile var capW = 540
    @Volatile var capH = 960

    @Volatile var engine = 0   // 0 = shaders, 1 = IA (modelo .tflite)
    @Volatile var aiStr = 1f   // força da IA
    @Volatile var ai: AiRunner? = null
    var postGl: ((Runnable) -> Unit)? = null

    var requestRender: (() -> Unit)? = null
    var onSurface: ((Surface) -> Unit)? = null

    private var prog = 0
    private var tex = 0
    private var st: SurfaceTexture? = null
    private val mtx = FloatArray(16)

    // ---- motor de IA ----
    private var viewW = 1
    private var viewH = 1
    private var progDown = 0
    private var progAi = 0
    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0
    private var srcTex = 0
    private var aiTex = 0
    @Volatile private var aiReady = false
    private var readBuf: ByteBuffer? = null
    private var bbSrc: ByteBuffer? = null
    private var bbOut: ByteBuffer? = null
    private var lastAiMs = 0L
    private val t0 = SystemClock.uptimeMillis()

    private var aPos = 0
    private var uTex = 0; private var uMtx = 0; private var uTexel = 0; private var uTime = 0
    private var uAo = 0; private var uSpec = 0; private var uRefl = 0; private var uLight = 0
    private var uMetal = 0; private var uLamp = 0

    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    fun resize(w: Int, h: Int) {
        capW = w; capH = h
        st?.setDefaultBufferSize(w, h)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = link(VS, FS)
        progDown = 0; progAi = 0; fbo = 0; fboTex = 0; fboW = 0; fboH = 0
        srcTex = 0; aiTex = 0; aiReady = false
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")
        uMtx = GLES20.glGetUniformLocation(prog, "uMtx")
        uTexel = GLES20.glGetUniformLocation(prog, "uTexel")
        uTime = GLES20.glGetUniformLocation(prog, "uTime")
        uAo = GLES20.glGetUniformLocation(prog, "uAo")
        uSpec = GLES20.glGetUniformLocation(prog, "uSpec")
        uRefl = GLES20.glGetUniformLocation(prog, "uRefl")
        uLight = GLES20.glGetUniformLocation(prog, "uLight")
        uMetal = GLES20.glGetUniformLocation(prog, "uMetal")
        uLamp = GLES20.glGetUniformLocation(prog, "uLamp")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        tex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        st?.release()
        val s = SurfaceTexture(tex)
        s.setDefaultBufferSize(capW, capH)
        s.setOnFrameAvailableListener(this)
        st = s
        onSurface?.invoke(Surface(s))
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        viewW = w; viewH = h
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onFrameAvailable(s: SurfaceTexture?) {
        requestRender?.invoke()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!enabled) return
        val s = st ?: return
        try { s.updateTexImage() } catch (_: Exception) { }
        s.getTransformMatrix(mtx)

        if (engine == 1) {
            val r = ai
            if (r == null || !r.failed) {
                if (r != null && r.ready) drawAi(r)
                return
            }
            // IA falhou: cai para o motor de shaders abaixo
        }

        GLES20.glDisable(GLES20.GL_BLEND) // saída já é "premultiplied"
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uMtx, 1, false, mtx, 0)
        GLES20.glUniform2f(uTexel, 1f / capW, 1f / capH)
        GLES20.glUniform1f(uTime, ((SystemClock.uptimeMillis() - t0) / 1000f) % 1000f)
        GLES20.glUniform1f(uAo, ao)
        GLES20.glUniform1f(uSpec, spec)
        GLES20.glUniform1f(uRefl, refl)
        GLES20.glUniform1f(uLight, lightK)
        GLES20.glUniform1f(uMetal, metal)
        GLES20.glUniform1f(uLamp, lamp)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun tex2d(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun ensureAiGl(w: Int, h: Int) {
        if (progDown == 0) progDown = link(VS, FS_DOWN)
        if (progAi == 0) progAi = link(VS, FS_AI)
        if (srcTex == 0) srcTex = tex2d()
        if (aiTex == 0) aiTex = tex2d()
        if (fbo == 0 || fboW != w || fboH != h) {
            if (fbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            }
            fboTex = tex2d()
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            val f = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0)
            fbo = f[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            fboW = w; fboH = h
            readBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            aiReady = false
        }
    }

    private fun drawQuad(loc: Int) {
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(loc)
    }

    private fun upload(tex: Int, bytes: ByteArray, w: Int, h: Int, cache: ByteBuffer?): ByteBuffer {
        val bb = if (cache != null && cache.capacity() == bytes.size) cache
                 else ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        bb.clear(); bb.put(bytes); bb.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
        return bb
    }

    private fun uploadAi(src: ByteArray, out: ByteArray, ow: Int, oh: Int) {
        if (fbo == 0 || aiTex == 0) return
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        bbSrc = upload(srcTex, src, fboW, fboH, bbSrc)
        bbOut = upload(aiTex, out, ow, oh, bbOut)
        aiReady = true
    }

    private fun drawAi(r: AiRunner) {
        ensureAiGl(r.inW, r.inH)
        val now = SystemClock.uptimeMillis()
        if (!r.busy && now - lastAiMs >= 33) { // até ~30 quadros de IA por segundo
            lastAiMs = now
            // 1) reduz o quadro do jogo para o tamanho de entrada do modelo
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, fboW, fboH)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(progDown)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progDown, "uTex"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progDown, "uMtx"), 1, false, mtx, 0)
            drawQuad(GLES20.glGetAttribLocation(progDown, "aPos"))
            val buf = readBuf
            if (buf != null) {
                buf.clear()
                GLES20.glReadPixels(0, 0, fboW, fboH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                val arr = ByteArray(fboW * fboH * 4)
                buf.position(0)
                buf.get(arr)
                r.busy = true
                r.submit(arr) { src, out, ow, oh ->
                    val post = postGl
                    if (post == null) { r.busy = false } else {
                        post(Runnable {
                            uploadAi(src, out, ow, oh)
                            r.busy = false
                            requestRender?.invoke()
                        })
                    }
                }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewW, viewH)
        }
        if (!aiReady) return

        // 2) camada final: soma só a DIFERENÇA que a IA fez (IA - original reduzido),
        //    mantendo o detalhe fino do jogo em resolução total
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(progAi)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progAi, "uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progAi, "uSrc"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, aiTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progAi, "uAi"), 2)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progAi, "uMtx"), 1, false, mtx, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progAi, "uStr"), aiStr)
        drawQuad(GLES20.glGetAttribLocation(progAi, "aPos"))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("DLSReal", "Shader erro: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    companion object {
        private const val FS_DOWN = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUV;
uniform samplerExternalOES uTex;
void main(){ gl_FragColor = vec4(texture2D(uTex, vUV).rgb, 1.0); }"""

        private const val FS_AI = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vUV;
varying vec2 vP;
uniform samplerExternalOES uTex;
uniform sampler2D uSrc;
uniform sampler2D uAi;
uniform float uStr;
void main(){
  vec3 c = texture2D(uTex, vUV).rgb;
  vec3 lo = texture2D(uSrc, vP).rgb;
  vec3 ai = texture2D(uAi, vP).rgb;
  vec3 d = (ai - lo) * uStr;
  float m = max(max(abs(d.r), abs(d.g)), abs(d.b));
  float a = clamp(m * 3.0, 0.0, 0.5);
  if(a < 0.004){ gl_FragColor = vec4(0.0); return; }
  vec3 X = clamp(c + d / a, 0.0, 1.0);
  gl_FragColor = vec4(X * a, a);
}"""

        private const val VS = """
attribute vec2 aPos;
uniform mat4 uMtx;
varying vec2 vUV;
varying vec2 vP;
void main(){
  gl_Position = vec4(aPos, 0.0, 1.0);
  vP = aPos * 0.5 + 0.5;
  vUV = (uMtx * vec4(vP, 0.0, 1.0)).xy;
}"""

        private const val FS = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vUV;
varying vec2 vP;
uniform samplerExternalOES uTex;
uniform mat4 uMtx;
uniform vec2 uTexel;
uniform float uTime;
uniform float uAo;
uniform float uSpec;
uniform float uRefl;
uniform float uLight;
uniform float uMetal;
uniform float uLamp;

vec3 S(vec2 uv){ return texture2D(uTex, uv).rgb; }
vec2 T(vec2 p){ return (uMtx * vec4(p, 0.0, 1.0)).xy; }
float L(vec3 c){ return dot(c, vec3(0.299, 0.587, 0.114)); }
float hash(vec2 p){ return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

// peso de "fonte de luz": so pixels bem claros contam (lampada, farol, neon)
float lampW(vec3 s, float lo){
  float m = max(s.r, max(s.g, s.b));
  return smoothstep(lo, lo + 0.27, mix(L(s), m, 0.5));
}

void main(){
  vec3 c = S(vUV);
  float lc = L(c);

  // ADAPTACAO: mede a claridade media do quadro (6 pontos) e ajusta os limiares.
  // Jogo escuro -> limiares baixos (luzes ainda brilham); jogo claro -> limiares altos.
  float avgL = (L(S(T(vec2(0.2, 0.35)))) + L(S(T(vec2(0.5, 0.35)))) + L(S(T(vec2(0.8, 0.35))))
            + L(S(T(vec2(0.2, 0.75)))) + L(S(T(vec2(0.5, 0.75)))) + L(S(T(vec2(0.8, 0.75))))) / 6.0;
  float lamLo = clamp(0.45 + avgL * 0.8, 0.55, 0.85);
  float bt = clamp(0.40 + avgL * 0.6, 0.50, 0.80);
  float metLo = clamp(avgL * 0.7, 0.15, 0.45);

  float rot = hash(gl_FragCoord.xy) * 6.2832;
  float jr = 0.85 + 0.3 * hash(gl_FragCoord.yx + 7.7);

  vec3 b1 = vec3(0.0);
  vec3 bloom = vec3(0.0);
  vec3 lampMid = vec3(0.0);
  vec3 lampWide = vec3(0.0);
  float lw = 0.0;
  for(int i = 0; i < 8; i++){
    float ang = float(i) * 0.7854;
    vec2 dir = vec2(cos(ang), sin(ang)) * uTexel;
    float aj = ang + rot;
    vec2 dj = vec2(cos(aj), sin(aj)) * uTexel * jr;
    b1 += S(vUV + dir * 2.5);
    lw += L(S(vUV + dir * 9.0));
    bloom += max(S(vUV + dir * 7.0) - bt, 0.0)
           + 0.7 * max(S(vUV + dir * 16.0) - bt, 0.0);
    vec3 sm = S(vUV + dj * 26.0);
    vec3 sw = S(vUV + dj * 60.0);
    lampMid += sm * lampW(sm, lamLo);
    lampWide += sw * lampW(sw, lamLo);
  }
  b1 /= 8.0; lw /= 8.0; bloom /= 8.0; lampMid /= 8.0; lampWide /= 8.0;

  vec3 d = vec3(0.0);

  // 1) micro-detalhe (textura, arma, bordas)
  d += (c - b1) * 0.9 * uSpec;

  // 2) sombras de contato / oclusao ambiente
  float ao = clamp(lw - lc, 0.0, 0.5);
  d -= c * ao * 1.6 * uAo;

  // 3) brilho especular quente nas partes claras
  d += bloom * vec3(1.0, 0.93, 0.80) * 0.9 * uSpec;

  // 3b) METAL: normal falsa a partir do relevo de luminancia
  vec2 t = uTexel * 1.5;
  float gx = L(S(vUV + vec2(t.x, 0.0))) - L(S(vUV - vec2(t.x, 0.0)));
  float gy = L(S(vUV + vec2(0.0, t.y))) - L(S(vUV - vec2(0.0, t.y)));
  vec3 n = normalize(vec3(-gx * 4.0, -gy * 4.0, 1.0));
  float edge = clamp(length(vec2(gx, gy)) * 3.0, 0.0, 1.0);
  float mx = max(c.r, max(c.g, c.b));
  float sat = mx - min(c.r, min(c.g, c.b));
  // metal = cinza/claro e com borda (evita ceu e nuvem lisos)
  float metalMask = (1.0 - smoothstep(0.10, 0.38, sat)) * smoothstep(metLo, metLo + 0.35, lc) * (0.15 + 0.85 * edge);

  vec3 Ld = normalize(vec3(0.6 * sin(uTime * 0.35), 0.55 + 0.2 * cos(uTime * 0.27), 0.75));
  vec3 H = normalize(Ld + vec3(0.0, 0.0, 1.0));
  float nh = max(dot(n, H), 0.0);
  float sharpS = pow(nh, 64.0);
  float softS = pow(nh, 12.0);
  float fres = pow(max(1.0 - n.z, 0.0), 2.0);
  vec3 mtint = min(c / max(lc, 0.08), vec3(1.6));
  mtint = mix(vec3(1.0), mtint, 0.5); // metal tinge o reflexo com a propria cor
  vec3 envA = S(vUV + n.xy * uTexel * 30.0);
  vec3 envB = S(vUV - n.xy * uTexel * 14.0);
  vec3 env = (envA + envB) * 0.5;      // reflexo de ambiente (espaco de tela)
  vec3 metalCol = mtint * (sharpS * 1.3 + softS * 0.28 + fres * 0.6) + (env - c) * 0.45;
  d += metalCol * metalMask * uMetal;

  // 3c) LAMPADAS / LUZES
  float flick = 0.95 + 0.05 * sin(uTime * 19.0) * sin(uTime * 7.3 + 1.0);
  // halo colorido (pega a cor da propria lampada)
  d += (lampMid * 1.1 + lampWide * 1.6) * flick * 0.9 * uLamp;
  // a luz bate nas superficies ao redor (tingida pela cor da fonte)
  d += c * (lampMid * 0.9 + lampWide * 1.8) * flick * uLamp;
  // rastro horizontal (anamorfico) + cruz vertical curta
  vec3 streak = vec3(0.0);
  for(int k = 1; k <= 4; k++){
    float o = float(k) * 10.0;
    float wg = 1.0 / (1.0 + float(k) * 0.9);
    vec3 a = S(vUV + vec2(uTexel.x * o, 0.0));
    vec3 b = S(vUV - vec2(uTexel.x * o, 0.0));
    streak += (a * lampW(a, lamLo) + b * lampW(b, lamLo)) * wg;
  }
  vec3 va = S(vUV + vec2(0.0, uTexel.y * 8.0));
  vec3 vb = S(vUV - vec2(0.0, uTexel.y * 8.0));
  streak += (va * lampW(va, lamLo) + vb * lampW(vb, lamLo)) * 0.5;
  streak *= 0.25;
  d += streak * mix(vec3(1.0), vec3(0.6, 0.8, 1.0), 0.6) * 1.2 * flick * uLamp;

  // 4) chao molhado e agua: reflexo da cena espelhada na linha do horizonte
  float line = 0.45;
  float below = 1.0 - smoothstep(line - 0.25, line, vP.y);
  float wob = sin(vP.y * 70.0 + uTime * 2.2) * 0.004 + sin(vP.y * 130.0 - uTime * 3.1) * 0.0025;
  vec2 mp = vec2(clamp(vP.x + wob, 0.0, 1.0), clamp(2.0 * line - vP.y, 0.0, 1.0));
  vec3 r = S(T(mp));
  float water = smoothstep(0.03, 0.15, c.b - max(c.r, c.g * 0.85));
  float rm = below * 0.35 + water * 0.55;
  d += max(r - 0.18, 0.0) * rm * uRefl;
  float sp = step(0.985, hash(floor(vP * vec2(220.0, 400.0)) + floor(uTime * 6.0)));
  d += vec3(sp) * water * 0.5 * uRefl;

  // 5) curva de contraste + saturacao
  vec3 sc = c * c * (3.0 - 2.0 * c);
  d += (sc - c) * 0.45 * uLight;
  d += (c - vec3(lc)) * 0.22 * uLight;

  // 6) luz dinamica em movimento (atinge mais as superficies claras)
  vec2 lp = vec2(0.5 + 0.35 * sin(uTime * 0.25), 0.7 + 0.15 * cos(uTime * 0.19));
  vec2 dl = vP - lp;
  float glow = exp(-dot(dl, dl) * 6.0);
  d += c * glow * vec3(0.30, 0.24, 0.14) * uLight;

  // 7) vinheta
  vec2 q = vP - 0.5;
  d -= c * dot(q, q) * 0.55 * uAo;

  // converte o delta em camada (cor + alpha), ganho limitado p/ nao realimentar
  float m = max(max(abs(d.r), abs(d.g)), abs(d.b));
  float a = clamp(m * 3.0, 0.0, 0.5);
  if(a < 0.004){ gl_FragColor = vec4(0.0); return; }
  vec3 X = clamp(c + d / a, 0.0, 1.0);
  gl_FragColor = vec4(X * a, a);
}"""
    }
}
