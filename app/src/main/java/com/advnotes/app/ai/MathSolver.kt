package com.advnotes.app.ai

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

enum class SolveMode(val label: String, val instruction: String) {
    SOLVE(
        "Solve",
        "First write a line starting with \"Problem:\" containing your transcription of the handwriting. " +
            "Then solve it step by step, one short step per line. Finish with a line starting with \"Answer:\".",
    ),
    ANSWER(
        "Answer only",
        "Reply with exactly two lines: \"Problem: <your transcription>\" and \"Answer: <final answer>\".",
    ),
    CHECK(
        "Check my work",
        "The image contains the student's own working. Transcribe it, check each step, and point out the first " +
            "mistake and how to fix it, if any. End with \"Verdict: correct\" or \"Verdict: incorrect\".",
    ),
    HINT(
        "Hint",
        "Transcribe the problem on a line starting with \"Problem:\", then give one helpful hint for the next step " +
            "without revealing the final answer.",
    ),
}

object MathSolver {
    val SYSTEM = """
        You are a careful math tutor built into a handwriting note-taking app. The user selected
        handwritten content from their notebook; it is attached as an image.
        Output plain text only: no Markdown, no LaTeX, no code blocks. Write math with Unicode symbols
        (×, ÷, −, ±, √, ², ³, ⁿ, ≤, ≥, ≠, ≈, π, ∞, ∫, Σ, θ, Δ, →) and fractions like (a + b)/c.
        Keep lines short: the answer may be written onto the notebook page.
        If the handwriting is ambiguous, say how you read it. If the image has no math, say so briefly.
    """.trimIndent()

    fun request(bitmap: Bitmap, mode: SolveMode, extra: String): VisionRequest {
        val png = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        val prompt = buildString {
            append(mode.instruction)
            if (extra.isNotBlank()) append("\n\nAdditional request from the user: ").append(extra.trim())
        }
        return VisionRequest(SYSTEM, prompt, png)
    }
}
