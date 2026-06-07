package com.example.omu.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import com.example.omu.core.AppConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet
import ai.onnxruntime.providers.NNAPIFlags

/**
 * Voice Activity Detection (VAD) Engine using Silero VAD via ONNX Runtime.
 * Optimized for 16kHz mono PCM audio in 32ms (512 samples) frames.
 */
class VadEngine(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputNames: Set<String>
    private val outputNames: List<String>
    private val inputs = LinkedHashMap<String, OnnxTensorLike>()

    private val audioBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(AppConstants.FRAME_SAMPLES * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    private val audioTensor: OnnxTensor

    private val stateBuffer: FloatBuffer?
    private val stateTensor: OnnxTensor?
    private val hBuffer: FloatBuffer?
    private val hTensor: OnnxTensor?
    private val cBuffer: FloatBuffer?
    private val cTensor: OnnxTensor?
    private val sampleRateTensor: OnnxTensor?

    init {
        val modelBytes = context.assets.open("silero_vad.onnx").use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(1)
            setMemoryPatternOptimization(false)
            runCatching {
                addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
            }.onFailure {
                Log.w(TAG, "NNAPI provider unavailable for VAD; ONNX Runtime will use fallback EPs", it)
            }
        }
        session = env.createSession(modelBytes, options)
        inputNames = session.inputNames
        outputNames = session.outputNames.toList()

        audioTensor = OnnxTensor.createTensor(
            env,
            audioBuffer,
            tensorShape("input", longArrayOf(1, AppConstants.FRAME_SAMPLES.toLong()))
        )
        inputs["input"] = audioTensor

        if ("state" in inputNames) {
            val shape = tensorShape("state", longArrayOf(2, 1, 128))
            stateBuffer = floatBuffer(elementCount(shape))
            stateTensor = OnnxTensor.createTensor(env, stateBuffer, shape)
            inputs["state"] = stateTensor
            hBuffer = null
            hTensor = null
            cBuffer = null
            cTensor = null
        } else {
            stateBuffer = null
            stateTensor = null
            if ("h" in inputNames) {
                val hShape = tensorShape("h", longArrayOf(2, 1, 64))
                hBuffer = floatBuffer(elementCount(hShape))
                hTensor = OnnxTensor.createTensor(env, hBuffer, hShape)
                inputs["h"] = hTensor
            } else {
                hBuffer = null
                hTensor = null
            }

            if ("c" in inputNames) {
                val cShape = tensorShape("c", longArrayOf(2, 1, 64))
                cBuffer = floatBuffer(elementCount(cShape))
                cTensor = OnnxTensor.createTensor(env, cBuffer, cShape)
                inputs["c"] = cTensor
            } else {
                cBuffer = null
                cTensor = null
            }
        }

        sampleRateTensor = if ("sr" in inputNames) {
            val shape = tensorShape("sr", longArrayOf(1))
            val buffer = longBuffer(elementCount(shape).coerceAtLeast(1))
            for (index in 0 until buffer.capacity()) {
                buffer.put(index, AppConstants.SAMPLE_RATE.toLong())
            }
            OnnxTensor.createTensor(env, buffer, shape).also {
                inputs["sr"] = it
            }
        } else {
            null
        }
    }

    @Synchronized
    fun confidenceFromPcm(pcmFrame: ByteArray): Float {
        if (pcmFrame.size != AppConstants.FRAME_BYTES) return 0f

        pcm16ToFloat(pcmFrame)

        return try {
            session.run(inputs).use { results ->
                val probabilityIndex = outputNames.indexOf("output").takeIf { it >= 0 } ?: 0
                val probability = firstFloat(results[probabilityIndex].value)

                stateBuffer?.let { buffer ->
                    val stateIndex = outputNames.indexOfFirst {
                        it.contains("state", ignoreCase = true)
                    }.takeIf { it >= 0 } ?: 1.takeIf { results.size() > 1 }

                    if (stateIndex != null) {
                        writeFlattened(results[stateIndex].value, buffer)
                    }
                }

                hBuffer?.let { buffer ->
                    val hIndex = outputNames.indexOfFirst {
                        it.equals("hn", ignoreCase = true) || it.equals("h", ignoreCase = true)
                    }.takeIf { it >= 0 } ?: 1.takeIf { results.size() > 1 }
                    if (hIndex != null) {
                        writeFlattened(results[hIndex].value, buffer)
                    }
                }

                cBuffer?.let { buffer ->
                    val cIndex = outputNames.indexOfFirst {
                        it.equals("cn", ignoreCase = true) || it.equals("c", ignoreCase = true)
                    }.takeIf { it >= 0 } ?: 2.takeIf { results.size() > 2 }
                    if (cIndex != null) {
                        writeFlattened(results[cIndex].value, buffer)
                    }
                }

                probability.coerceIn(0f, 1f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD inference failed", e)
            0f
        }
    }

    fun isSpeech(audioFrame: FloatArray): Float {
        if (audioFrame.size != AppConstants.FRAME_SAMPLES) return 0f
        for (index in audioFrame.indices) {
            audioBuffer.put(index, audioFrame[index])
        }
        return runInferenceOnly()
    }

    @Synchronized
    fun warmUp() {
        reset()
        repeat(WARM_UP_FRAME_COUNT) {
            confidenceFromPcm(SILENCE_FRAME)
        }
        reset()
    }

    @Synchronized
    fun reset() {
        stateBuffer?.zero()
        hBuffer?.zero()
        cBuffer?.zero()
    }

    fun close() {
        audioTensor.close()
        stateTensor?.close()
        hTensor?.close()
        cTensor?.close()
        sampleRateTensor?.close()
        session.close()
    }

    private fun runInferenceOnly(): Float {
        return try {
            session.run(inputs).use { results ->
                firstFloat(results[0].value).coerceIn(0f, 1f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD inference failed", e)
            0f
        }
    }

    private fun pcm16ToFloat(pcmFrame: ByteArray) {
        var byteIndex = 0
        for (sampleIndex in 0 until AppConstants.FRAME_SAMPLES) {
            val sample = (pcmFrame[byteIndex + 1].toInt() shl 8) or
                (pcmFrame[byteIndex].toInt() and 0xff)
            audioBuffer.put(sampleIndex, sample / 32768.0f)
            byteIndex += AppConstants.BYTES_PER_SAMPLE
        }
    }

    private fun tensorShape(name: String, fallback: LongArray): LongArray {
        val info = session.inputInfo[name]?.info as? TensorInfo ?: return fallback
        val shape = info.shape
        if (shape.isEmpty()) return fallback
        return LongArray(shape.size) { index ->
            when {
                shape[index] > 0 -> shape[index]
                index < fallback.size -> fallback[index]
                else -> 1
            }
        }
    }

    private fun elementCount(shape: LongArray): Int {
        return shape.fold(1L) { total, dimension -> total * dimension.coerceAtLeast(1) }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun floatBuffer(size: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    private fun longBuffer(size: Int): LongBuffer {
        return ByteBuffer.allocateDirect(size * Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
    }

    private fun FloatBuffer.zero() {
        for (index in 0 until capacity()) {
            put(index, 0f)
        }
    }

    private fun firstFloat(value: Any?): Float {
        return when (value) {
            is Float -> value
            is FloatArray -> value.firstOrNull() ?: 0f
            is Array<*> -> value.firstOrNull()?.let(::firstFloat) ?: 0f
            is Number -> value.toFloat()
            else -> 0f
        }
    }

    private fun writeFlattened(value: Any?, target: FloatBuffer) {
        val nextIndex = writeFlattened(value, target, 0)
        for (index in nextIndex until target.capacity()) {
            target.put(index, 0f)
        }
    }

    private fun writeFlattened(value: Any?, target: FloatBuffer, startIndex: Int): Int {
        var index = startIndex
        when (value) {
            is FloatArray -> {
                var sourceIndex = 0
                while (sourceIndex < value.size && index < target.capacity()) {
                    target.put(index, value[sourceIndex])
                    index++
                    sourceIndex++
                }
            }

            is Array<*> -> {
                value.forEach { item ->
                    if (index < target.capacity()) {
                        index = writeFlattened(item, target, index)
                    }
                }
            }

            is Number -> {
                if (index < target.capacity()) {
                    target.put(index, value.toFloat())
                    index++
                }
            }
        }
        return index
    }

    companion object {
        private const val TAG = "VadEngine"
        private const val WARM_UP_FRAME_COUNT = 3
        private val SILENCE_FRAME = ByteArray(AppConstants.FRAME_BYTES)
    }
}
