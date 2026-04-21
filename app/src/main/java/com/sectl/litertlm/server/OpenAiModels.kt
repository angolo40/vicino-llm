package com.sectl.litertlm.server

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * OpenAI chat completion wire format subset.
 *
 * Goal: a client using the OpenAI Python SDK, LangChain, llama.cpp clients
 * or OpenClaw must work unmodified when pointed at this server. That means
 * matching JSON field names and shapes exactly, not just semantically.
 *
 * We expose only the fields we actually implement — `ignoreUnknownKeys` in
 * the Ktor JSON config lets clients send any extras without breaking the
 * deserialization.
 */

// ---------- /v1/chat/completions -------------------------------------------

@Serializable
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    /**
     * VicinoLLM extension (not in the OpenAI spec): when true, the server
     * augments the last user message with live SearXNG results before
     * running inference. Requires a `searxngUrl` in Prefs, otherwise the
     * flag is silently ignored.
     */
    @SerialName("web_search") val webSearch: Boolean = false,
    // Extras tolerated but ignored (OpenAI full shape): presence_penalty,
    // frequency_penalty, logit_bias, user, n, stop, etc.
)

@Serializable
data class ChatMessage(
    val role: String,          // "system" | "user" | "assistant"
    @Serializable(with = MessageContentSerializer::class)
    val content: MessageContent = MessageContent.Text(""),
)

/**
 * OpenAI's `content` field accepts EITHER a bare string OR an array of
 * parts (text / image_url / input_audio). We model both and serialize
 * whichever form the caller sent; for our own outputs we always emit a
 * plain string (simpler, matches the official chat/completions reply shape).
 */
sealed interface MessageContent {
    /** Plain text — the classic chat content. */
    data class Text(val text: String) : MessageContent

    /** Array of heterogeneous parts. Keeps order to preserve image position. */
    data class Parts(val parts: List<Part>) : MessageContent

    sealed interface Part {
        data class Text(val text: String) : Part
        /** URL can be `data:image/png;base64,...` or `http(s)://...`. */
        data class Image(val url: String, val detail: String? = null) : Part
        /** Matches OpenAI input_audio: base64 + format hint. */
        data class Audio(val dataBase64: String, val format: String? = null) : Part
    }

    /** Flatten to a text-only summary, used when the model backend ignores media. */
    fun plainText(): String = when (this) {
        is Text -> text
        is Parts -> parts.joinToString("\n") {
            when (it) {
                is Part.Text -> it.text
                is Part.Image -> "[image]"
                is Part.Audio -> "[audio]"
            }
        }
    }
}

/**
 * Accepts either a raw JSON string for `content`, or an array of parts,
 * mirroring OpenAI's polymorphic wire format. On write we always emit a
 * plain string — the server only produces text output.
 */
object MessageContentSerializer : KSerializer<MessageContent> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor

    override fun deserialize(decoder: Decoder): MessageContent {
        val jd = decoder as? JsonDecoder ?: error("JSON-only serializer")
        return when (val el = jd.decodeJsonElement()) {
            is JsonPrimitive -> MessageContent.Text(el.contentOrNull.orEmpty())
            is JsonArray -> MessageContent.Parts(el.mapNotNull { elementToPart(it) })
            else -> MessageContent.Text("")
        }
    }

    override fun serialize(encoder: Encoder, value: MessageContent) {
        // Always emit plain text — assistant replies don't have image/audio
        // output in this server.
        encoder.encodeString(value.plainText())
    }

    private fun elementToPart(el: JsonElement): MessageContent.Part? {
        val obj = el as? JsonObject ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        return when (type) {
            "text" -> (obj["text"] as? JsonPrimitive)?.contentOrNull
                ?.let(MessageContent.Part::Text)
            "image_url" -> {
                val imageObj = obj["image_url"] as? JsonObject ?: return null
                val url = (imageObj["url"] as? JsonPrimitive)?.contentOrNull ?: return null
                val detail = (imageObj["detail"] as? JsonPrimitive)?.contentOrNull
                MessageContent.Part.Image(url = url, detail = detail)
            }
            "input_audio" -> {
                val audioObj = obj["input_audio"] as? JsonObject ?: return null
                val data = (audioObj["data"] as? JsonPrimitive)?.contentOrNull ?: return null
                val format = (audioObj["format"] as? JsonPrimitive)?.contentOrNull
                MessageContent.Part.Audio(dataBase64 = data, format = format)
            }
            else -> null
        }
    }
}

/** Helper: build a plain-text assistant reply. */
fun textMessage(role: String, text: String) = ChatMessage(role, MessageContent.Text(text))

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: Usage,
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String = "stop",
)

// ---------- /v1/completions (legacy) ---------------------------------------

@Serializable
data class CompletionRequest(
    val model: String? = null,
    val prompt: String = "",
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
)

@Serializable
data class CompletionResponse(
    val id: String,
    val `object`: String = "text_completion",
    val created: Long,
    val model: String,
    val choices: List<CompletionChoice>,
    val usage: Usage,
)

@Serializable
data class CompletionChoice(
    val text: String,
    val index: Int = 0,
    @SerialName("finish_reason") val finishReason: String = "stop",
    val logprobs: String? = null,  // serialized as null, we don't compute them
)

// ---------- shared ---------------------------------------------------------

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

// ---------- /v1/models -----------------------------------------------------

@Serializable
data class ModelsListResponse(
    val `object`: String = "list",
    val data: List<ModelEntry>,
)

@Serializable
data class ModelEntry(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0,
    @SerialName("owned_by") val ownedBy: String = "local",
)
