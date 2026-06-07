package com.example.omu.ml

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class VoiceProfileInfo(
    val id: String,
    val label: String
)

data class VoiceTensor(
    val data: FloatArray,
    val shape: LongArray
) {
    val elementCount: Int
        get() = data.size
}

data class VoiceProfile(
    val id: String,
    val label: String,
    val styleTtl: VoiceTensor,
    val styleDp: VoiceTensor
) {
    fun flattenEmbedding(): FloatArray {
        val embedding = FloatArray(styleTtl.elementCount + styleDp.elementCount)
        styleTtl.data.copyInto(embedding)
        styleDp.data.copyInto(embedding, destinationOffset = styleTtl.elementCount)
        return embedding
    }
}

class VoiceProfileParser(context: Context) {
    private val appContext = context.applicationContext
    private val assetManager = appContext.assets
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun listVoiceProfiles(): List<VoiceProfileInfo> = withContext(Dispatchers.IO) {
        resolveVoiceSources()
            .distinctBy { source -> source.id }
            .sortedWith(compareBy<VoiceSource> { source ->
                if (source.label == DEFAULT_VOICE_NAME) 0 else 1
            }.thenBy { source -> source.label })
            .map { source -> VoiceProfileInfo(source.id, source.label) }
    }

    suspend fun loadVoiceEmbedding(fileName: String): FloatArray = withContext(Dispatchers.IO) {
        loadVoiceProfileInternal(fileName).flattenEmbedding()
    }

    suspend fun loadVoiceProfile(fileName: String): VoiceProfile = withContext(Dispatchers.IO) {
        loadVoiceProfileInternal(fileName)
    }

    private fun loadVoiceProfileInternal(fileName: String): VoiceProfile {
        val normalizedFileName = fileName.trim()
        val directFile = File(normalizedFileName)
        if (directFile.isFile && directFile.name.hasVoiceProfileExtension()) {
            val content = directFile.readText()
            if (!content.trimStart().startsWith("{")) {
                throw IllegalArgumentException("Unsupported voice profile format: ${directFile.absolutePath}")
            }
            return parseProfile(
                id = directFile.absolutePath,
                label = directFile.name.substringBeforeLast('.'),
                content = content
            )
        }

        val source = resolveVoiceSources().firstOrNull { it.matches(fileName) }
            ?: throw IllegalArgumentException("Voice profile not found: $fileName")
        val content = source.readText(assetManager)
        if (!content.trimStart().startsWith("{")) {
            throw IllegalArgumentException("Unsupported voice profile format: ${source.id}")
        }
        return parseProfile(
            id = source.id,
            label = source.label,
            content = content
        )
    }

    private fun parseProfile(
        id: String,
        label: String,
        content: String
    ): VoiceProfile {
        val root = json.parseToJsonElement(content).jsonObject
        val styleTtl = root["style_ttl"]?.jsonObject
            ?: throw IllegalArgumentException("Voice profile $label is missing style_ttl")
        val styleDp = root["style_dp"]?.jsonObject
            ?: throw IllegalArgumentException("Voice profile $label is missing style_dp")

        return VoiceProfile(
            id = id,
            label = label,
            styleTtl = parseTensor(styleTtl, tensorName = "style_ttl"),
            styleDp = parseTensor(styleDp, tensorName = "style_dp")
        )
    }

    private fun parseTensor(
        tensorObject: JsonObject,
        tensorName: String
    ): VoiceTensor {
        val dataElement = tensorObject["data"]
            ?: throw IllegalArgumentException("Voice tensor $tensorName is missing data")
        val shape = tensorObject["dims"]?.jsonArray?.map { element ->
            element.jsonPrimitive.content.toLong()
        }?.toLongArray() ?: inferShape(dataElement).toLongArray()

        require(shape.isNotEmpty()) { "Voice tensor $tensorName has no shape" }

        val expectedCount = shape.fold(1L) { total, dimension ->
            total * dimension.coerceAtLeast(1)
        }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val values = FloatArray(expectedCount)
        val written = flattenNumbers(dataElement, values, 0)

        require(written == expectedCount) {
            "Voice tensor $tensorName has $written values, expected $expectedCount"
        }

        return VoiceTensor(values, shape)
    }

    private fun flattenNumbers(
        element: JsonElement,
        target: FloatArray,
        startIndex: Int
    ): Int {
        var index = startIndex
        when (element) {
            is JsonArray -> {
                element.forEach { child ->
                    index = flattenNumbers(child, target, index)
                }
            }

            else -> {
                if (index >= target.size) {
                    throw IllegalArgumentException("Voice tensor contains more values than its shape")
                }
                target[index] = element.jsonPrimitive.double.toFloat()
                index++
            }
        }
        return index
    }

    private fun inferShape(element: JsonElement): List<Long> {
        return when (element) {
            is JsonArray -> {
                val firstChild = element.firstOrNull() ?: return listOf(0L)
                listOf(element.size.toLong()) + inferShape(firstChild)
            }

            else -> emptyList()
        }
    }

    private fun resolveVoiceSources(): List<VoiceSource> {
        val sources = ArrayList<VoiceSource>()

        ASSET_VOICE_ROOTS.forEach { root ->
            runCatching {
                assetManager.list(root)?.forEach { fileName ->
                    if (fileName.hasVoiceProfileExtension()) {
                        sources += VoiceSource.Asset(
                            id = "$root/$fileName",
                            label = fileName.substringBeforeLast('.'),
                            path = "$root/$fileName"
                        )
                    }
                }
            }
        }

        listOfNotNull(
            appContext.getExternalFilesDir(null),
            appContext.filesDir
        ).forEach { root ->
            DISK_VOICE_ROOTS.forEach { relativePath ->
                val directory = File(root, relativePath)
                if (directory.isDirectory) {
                    directory.listFiles()
                        ?.filter { file -> file.isFile && file.name.hasVoiceProfileExtension() }
                        ?.forEach { file ->
                            sources += VoiceSource.Disk(
                                id = file.absolutePath,
                                label = file.name.substringBeforeLast('.'),
                                file = file
                            )
                        }
                }
            }
        }

        return sources
    }

    private fun String.hasVoiceProfileExtension(): Boolean {
        return endsWith(".json", ignoreCase = true) || endsWith(".tensor", ignoreCase = true)
    }

    private sealed class VoiceSource(
        open val id: String,
        open val label: String
    ) {
        abstract fun readText(assetManager: android.content.res.AssetManager): String

        fun matches(requestedName: String): Boolean {
            val normalized = requestedName.trim()
            val requestedLabel = normalized.substringAfterLast('/').substringBeforeLast('.')
            return id == normalized ||
                id.endsWith("/$normalized") ||
                label.equals(normalized, ignoreCase = true) ||
                label.equals(requestedLabel, ignoreCase = true)
        }

        data class Asset(
            override val id: String,
            override val label: String,
            val path: String
        ) : VoiceSource(id, label) {
            override fun readText(assetManager: android.content.res.AssetManager): String {
                return assetManager.open(path).bufferedReader().use { it.readText() }
            }
        }

        data class Disk(
            override val id: String,
            override val label: String,
            val file: File
        ) : VoiceSource(id, label) {
            override fun readText(assetManager: android.content.res.AssetManager): String = file.readText()
        }
    }

    companion object {
        const val DEFAULT_VOICE_NAME = "F1"
        const val DEFAULT_VOICE_ID = "android_tts_assets/voice_styles/F1.json"
        fun createVoiceProfileFromEmbedding(
            id: String,
            label: String,
            embedding: FloatArray,
            template: VoiceProfile
        ): VoiceProfile {
            val ttlCount = template.styleTtl.elementCount
            val dpCount = template.styleDp.elementCount
            val expectedCount = ttlCount + dpCount
            require(embedding.size == expectedCount) {
                "Voice embedding has ${embedding.size} values, expected $expectedCount"
            }

            return VoiceProfile(
                id = id,
                label = label,
                styleTtl = VoiceTensor(
                    data = embedding.copyOfRange(0, ttlCount),
                    shape = template.styleTtl.shape.copyOf()
                ),
                styleDp = VoiceTensor(
                    data = embedding.copyOfRange(ttlCount, expectedCount),
                    shape = template.styleDp.shape.copyOf()
                )
            )
        }

        fun expectedEmbeddingSize(template: VoiceProfile): Int {
            return template.styleTtl.elementCount + template.styleDp.elementCount
        }

        private val ASSET_VOICE_ROOTS = listOf(
            "voices",
            "android_tts_assets/voices",
            "android_tts_assets/voice_styles"
        )
        private val DISK_VOICE_ROOTS = listOf(
            "voices",
            "android_tts_assets/voices",
            "android_tts_assets/voice_styles"
        )
    }
}
