package com.example.voiceassistant

/**
 * ModelConfig  (FINAL — Drive link pre-filled)
 * ---------------------------------------------
 * The Google Drive file ID is hardcoded below.
 * The app will automatically download the model on first launch
 * with no ADB or manual steps required.
 *
 * Drive link: https://drive.google.com/file/d/1KuB6RrCJh0vzhVwXLx0a97zDv6OpbZor/view
 */
object ModelConfig {

    data class ModelEntry(
        val name          : String,
        val fileName      : String,
        val driveFileId   : String,
        val expectedBytes : Long,
        val promptFormat  : PromptFormat
    )

    enum class PromptFormat { CHATML, LLAMA3, DEEPSEEK_R1 }

    // =========================================================================
    // Active model — Qwen2.5-1.5B-Instruct Q4_K_M
    // Drive file ID extracted from the user-supplied shareable link
    // =========================================================================
    val ACTIVE = ModelEntry(
        name          = "Qwen2.5-1.5B-Instruct Q4_K_M",
        fileName      = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        driveFileId   = "1KuB6RrCJh0vzhVwXLx0a97zDv6OpbZor",   // <-- your Drive ID
        expectedBytes = 935_000_000L,   // ~935 MB; used for progress bar + integrity check
        promptFormat  = PromptFormat.CHATML
    )

    // =========================================================================
    // Prompt format builders
    // =========================================================================
    fun buildPrompt(format: PromptFormat, systemPrompt: String, userText: String): String =
        when (format) {
            PromptFormat.CHATML -> """
                |<|im_start|>system
                |$systemPrompt<|im_end|>
                |<|im_start|>user
                |$userText<|im_end|>
                |<|im_start|>assistant
                |""".trimMargin()

            PromptFormat.LLAMA3 -> """
                |<|begin_of_text|><|start_header_id|>system<|end_header_id|>
                |$systemPrompt<|eot_id|><|start_header_id|>user<|end_header_id|>
                |$userText<|eot_id|><|start_header_id|>assistant<|end_header_id|>
                |""".trimMargin()

            PromptFormat.DEEPSEEK_R1 -> """
                |<|im_start|>system
                |$systemPrompt<|im_end|>
                |<|im_start|>user
                |$userText<|im_end|>
                |<|im_start|>assistant
                |<think>\n""".trimMargin()
        }
}
