package com.dlsreal

import android.os.Handler
import android.os.HandlerThread
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Roda um modelo .tflite (float32, entrada [1,H,W,3] e saída [1,H',W',3], valores 0..1)
 * em uma thread própria, tentando GPU e caindo para CPU se a GPU não aceitar o modelo.
 */
class AiRunner(private val modelPath: String) {

    private val thread = HandlerThread("dlsreal-ai").apply { start() }
    private val h = Handler(thread.looper)
    private var interp: Interpreter? = null
    private var gpu: GpuDelegate? = null

    @Volatile var inW = 0
    @Volatile var inH = 0
    @Volatile var outW = 0
    @Volatile var outH = 0
    @Volatile var ready = false
    @Volatile var failed = false
    @Volatile var busy = false
    @Volatile var error = ""
    @Volatile var usingGpu = false
    @Volatile private var released = false

    private var inBuf: ByteBuffer? = null
    private var outBuf: ByteBuffer? = null

    fun load() {
        h.post {
            try {
                val f = File(modelPath)
                val map = FileInputStream(f).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
                var it: Interpreter? = null
                try {
                    val g = GpuDelegate()
                    gpu = g
                    it = Interpreter(map, Interpreter.Options().addDelegate(g))
                    usingGpu = true
                } catch (t: Throwable) {
                    runCatching { gpu?.close() }
                    gpu = null
                    usingGpu = false
                    it = Interpreter(map, Interpreter.Options().setNumThreads(4))
                }
                val ip = it!!
                val ti = ip.getInputTensor(0)
                val to = ip.getOutputTensor(0)
                val si = ti.shape()
                val so = to.shape()
                if (ti.dataType() != DataType.FLOAT32 || to.dataType() != DataType.FLOAT32) {
                    throw IllegalStateException("O modelo precisa ser float32 (entrada e saída).")
                }
                if (si.size != 4 || si[3] != 3 || si[1] <= 0 || si[2] <= 0) {
                    throw IllegalStateException("Entrada esperada [1,altura,largura,3]; veio " + si.joinToString("x"))
                }
                if (so.size != 4 || so[3] != 3 || so[1] <= 0 || so[2] <= 0) {
                    throw IllegalStateException("Saída esperada [1,altura,largura,3]; veio " + so.joinToString("x"))
                }
                inH = si[1]; inW = si[2]; outH = so[1]; outW = so[2]
                inBuf = ByteBuffer.allocateDirect(inW * inH * 3 * 4).order(ByteOrder.nativeOrder())
                outBuf = ByteBuffer.allocateDirect(outW * outH * 3 * 4).order(ByteOrder.nativeOrder())
                interp = ip
                ready = true
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                failed = true
            }
        }
    }

    /**
     * src = quadro reduzido (RGBA, linha de baixo primeiro, inW x inH).
     * onResult recebe o mesmo quadro + a saída da IA (RGBA, mesma orientação, outW x outH).
     */
    fun submit(src: ByteArray, onResult: (ByteArray, ByteArray, Int, Int) -> Unit) {
        h.post {
            if (released) return@post
            try {
                val ip = interp ?: return@post
                val ib = inBuf!!
                val ob = outBuf!!
                ib.rewind()
                for (y in 0 until inH) {
                    val sy = inH - 1 - y // modelo espera a linha de cima primeiro
                    for (x in 0 until inW) {
                        val i = (sy * inW + x) * 4
                        ib.putFloat((src[i].toInt() and 255) / 255f)
                        ib.putFloat((src[i + 1].toInt() and 255) / 255f)
                        ib.putFloat((src[i + 2].toInt() and 255) / 255f)
                    }
                }
                ib.rewind()
                ob.rewind()
                ip.run(ib, ob)
                ob.rewind()
                val out = ByteArray(outW * outH * 4)
                for (y in 0 until outH) {
                    val dy = outH - 1 - y // volta para "linha de baixo primeiro" (OpenGL)
                    for (x in 0 until outW) {
                        val r = ob.getFloat(); val g = ob.getFloat(); val b = ob.getFloat()
                        val i = (dy * outW + x) * 4
                        out[i] = (r.coerceIn(0f, 1f) * 255f).toInt().toByte()
                        out[i + 1] = (g.coerceIn(0f, 1f) * 255f).toInt().toByte()
                        out[i + 2] = (b.coerceIn(0f, 1f) * 255f).toInt().toByte()
                        out[i + 3] = 255.toByte()
                    }
                }
                onResult(src, out, outW, outH)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                failed = true
                busy = false
            }
        }
    }

    fun release() {
        released = true
        h.post {
            runCatching { interp?.close() }
            runCatching { gpu?.close() }
            interp = null; gpu = null
            thread.quitSafely()
        }
    }
}
