package com.advnotes.app.ai

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

enum class Subject(val label: String, val guidance: String) {
    AUTO(
        "Auto",
        "First decide whether this is math, physics or chemistry (or another STEM subject) and follow the matching conventions below.",
    ),
    MATH("Math", ""),
    PHYSICS("Physics", ""),
    CHEMISTRY("Chemistry", ""),
}

enum class SolveMode(val label: String, val instruction: String) {
    SOLVE(
        "Solve",
        "First write a line starting with \"Problem:\" containing your transcription of the handwriting or printed question. " +
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

/** Builds solver requests for handwritten or printed STEM problems. */
object MathSolver {
    private const val BASE = """You are a careful STEM tutor (math, physics, chemistry) built into a handwriting note-taking app.
The user selected part of a notebook page (handwriting and/or a printed worksheet); it is attached as an image.
Output plain text only: no Markdown, no LaTeX, no code blocks. Write formulas with Unicode
(×, ÷, −, ±, √, ², ³, ⁿ, ≤, ≥, ≠, ≈, π, ∞, ∫, Σ, Δ, θ, λ, ω, μ, →, ⇌) and fractions like (a + b)/c.
Keep lines short: the answer may be written onto the notebook page.
If the handwriting is ambiguous, say how you read it. If the image holds no question, say so briefly."""

    private const val MATH = """Math: show the algebra; give exact answers (fractions, roots, π) and a decimal when useful; state domain restrictions."""

    private const val PHYSICS = """Physics: start with "Given:" listing the known quantities with SI units, and "Find:".
Name the principle or law you use, solve symbolically first, then substitute numbers with units.
Carry units through every step, round the final answer to sensible significant figures, and do a quick sanity check
(units, sign, order of magnitude). Use g = 9.81 m/s² and standard constants unless the problem gives others."""

    private const val CHEMISTRY = """Chemistry: write formulas with Unicode subscripts and charges (H₂SO₄, Fe³⁺, SO₄²⁻) and state symbols (s), (l), (g), (aq).
Balance every equation and check atom and charge balance. For calculations list the molar masses you used,
go through moles explicitly, keep units, and round to the data's significant figures.
For organic questions name compounds with IUPAC names."""

    fun systemPrompt(subject: Subject): String = buildString {
        append(BASE).append("\n\n")
        when (subject) {
            Subject.AUTO -> append(Subject.AUTO.guidance).append("\n").append(MATH).append("\n").append(PHYSICS).append("\n").append(CHEMISTRY)
            Subject.MATH -> append(MATH)
            Subject.PHYSICS -> append(PHYSICS)
            Subject.CHEMISTRY -> append(CHEMISTRY)
        }
    }

    /** Kept for callers that don't pick a subject. */
    val SYSTEM: String get() = systemPrompt(Subject.AUTO)

    fun png(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }

    fun request(bitmap: Bitmap, mode: SolveMode, extra: String, subject: Subject = Subject.AUTO): VisionRequest {
        val prompt = buildString {
            append(mode.instruction)
            if (extra.isNotBlank()) append("\n\nAdditional request from the user: ").append(extra.trim())
        }
        return VisionRequest(systemPrompt(subject), prompt, png(bitmap))
    }
}
