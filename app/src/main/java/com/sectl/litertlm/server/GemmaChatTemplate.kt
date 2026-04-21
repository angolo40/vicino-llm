package com.sectl.litertlm.server

/**
 * Converts an OpenAI-style list of [ChatMessage]s into the Gemma 4 chat
 * template string that LiteRT-LM expects to see at generation time.
 *
 * Gemma 4 (like Gemma 3) uses turn-delimited prompts:
 *
 *   <start_of_turn>user
 *   Hello!<end_of_turn>
 *   <start_of_turn>model
 *   Hi there!<end_of_turn>
 *   <start_of_turn>user
 *   How are you?<end_of_turn>
 *   <start_of_turn>model
 *
 * Notes:
 *   - Gemma has no first-class "system" role. The canonical trick is to
 *     prepend system content to the first user turn, separated by a blank
 *     line. Matches what `Contents.of("system instruction")` does inside
 *     the SDK's own systemInstruction path.
 *   - The response from the model is returned naked (already does NOT include
 *     the turn tags), so we don't strip anything on output.
 *   - We append a trailing `<start_of_turn>model\n` so the model knows to
 *     start its assistant turn.
 *
 * Why apply the template on our side rather than let `Conversation`
 * auto-format: we want deterministic multi-turn semantics that match OpenAI's
 * messages[] array (stateless HTTP). Conversation is stateful and would
 * accumulate across requests, which clients like OpenAI SDK do not expect.
 */
object GemmaChatTemplate {

    private const val START = "<start_of_turn>"
    private const val END = "<end_of_turn>"

    /** Text-only formatter (back-compat for existing callers). */
    fun format(messages: List<ChatMessage>): String = buildString {
        forEachTextSpan(messages) { append(it) }
    }

    /**
     * Multimodal formatter — emits a list of [MultimodalPart]s ready for
     * [InferenceEngine.inferMultimodal]. Text spans contain the Gemma turn
     * delimiters; images/audio are emitted as their own parts in-place,
     * so the model sees them at the exact position the user put them.
     */
    fun formatMultimodal(messages: List<ChatMessage>): List<MultimodalPart> {
        val out = mutableListOf<MultimodalPart>()
        val text = StringBuilder()
        val flushText = {
            if (text.isNotEmpty()) {
                out.add(MultimodalPart.Text(text.toString()))
                text.clear()
            }
        }
        forEachMultimodalToken(messages) { tok ->
            when (tok) {
                is Token.TextChunk -> text.append(tok.text)
                is Token.Image -> { flushText(); out.add(MultimodalPart.Image(tok.bytes)) }
                is Token.Audio -> { flushText(); out.add(MultimodalPart.Audio(tok.bytes)) }
            }
        }
        flushText()
        return out
    }

    private sealed interface Token {
        data class TextChunk(val text: String) : Token
        data class Image(val bytes: ByteArray) : Token
        data class Audio(val bytes: ByteArray) : Token
    }

    private inline fun forEachTextSpan(
        messages: List<ChatMessage>,
        emit: (String) -> Unit,
    ) {
        forEachMultimodalToken(messages) { tok ->
            when (tok) {
                is Token.TextChunk -> emit(tok.text)
                // In text-only mode, media gets a placeholder so the prompt
                // is still well-formed even if the caller fell back to
                // infer(prompt).
                is Token.Image -> emit("[image]")
                is Token.Audio -> emit("[audio]")
            }
        }
    }

    private inline fun forEachMultimodalToken(
        messages: List<ChatMessage>,
        emit: (Token) -> Unit,
    ) {
        val systemText = messages
            .filter { it.role.equals("system", ignoreCase = true) }
            .joinToString("\n") { it.content.plainText().trim() }
            .takeIf { it.isNotBlank() }
        val nonSystem = messages.filterNot { it.role.equals("system", ignoreCase = true) }

        if (nonSystem.isEmpty() && systemText == null) {
            emit(Token.TextChunk("$START" + "model\n"))
            return
        }

        var systemInjected = false
        nonSystem.forEach { msg ->
            val role = mapRole(msg.role)
            emit(Token.TextChunk("$START$role\n"))
            if (!systemInjected && role == "user" && systemText != null) {
                emit(Token.TextChunk("$systemText\n\n"))
                systemInjected = true
            }
            emitContent(msg.content, emit)
            emit(Token.TextChunk("$END\n"))
        }
        if (!systemInjected && systemText != null) {
            emit(Token.TextChunk("${START}user\n$systemText$END\n"))
        }
        emit(Token.TextChunk("${START}model\n"))
    }

    private inline fun emitContent(content: MessageContent, emit: (Token) -> Unit) {
        when (content) {
            is MessageContent.Text -> emit(Token.TextChunk(content.text.trim()))
            is MessageContent.Parts -> content.parts.forEach { part ->
                when (part) {
                    is MessageContent.Part.Text -> emit(Token.TextChunk(part.text.trim()))
                    is MessageContent.Part.Image -> {
                        val bytes = MediaLoader.resolveImageUrl(part.url)
                        if (bytes != null) emit(Token.Image(bytes))
                        else emit(Token.TextChunk("[image-unavailable]"))
                    }
                    is MessageContent.Part.Audio -> {
                        val bytes = MediaLoader.loadBase64(part.dataBase64)
                        if (bytes != null) emit(Token.Audio(bytes))
                        else emit(Token.TextChunk("[audio-unavailable]"))
                    }
                }
            }
        }
    }

    private fun mapRole(openAiRole: String): String = when (openAiRole.lowercase()) {
        "assistant", "model" -> "model"
        "user", "system" -> "user"
        else -> "user"
    }
}
