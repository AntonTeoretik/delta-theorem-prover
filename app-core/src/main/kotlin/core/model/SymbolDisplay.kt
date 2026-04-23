package core.model

object SymbolDisplay {
    val symbolReplacements: Map<String, String> = linkedMapOf(
        "\\a" to "α",
        "\\b" to "β",
        "\\g" to "γ",
        "\\d" to "δ",
        "\\e" to "ε",
        "\\l" to "λ",
        "\\p" to "π",
        "\\s" to "σ",
        "\\t" to "τ",
        "\\w" to "ω",
        "\\to" to "→",
        "\\mapsto" to "↦",
        "\\dot" to "·",
        "\\times" to "×",
        "\\mN" to "ℕ",
        "\\mZ" to "ℤ",
        "\\mQ" to "ℚ",
        "\\mR" to "ℝ",
        "\\mC" to "ℂ",
        "\\forall" to "∀",
        "\\exists" to "∃",
    )

    fun displayName(rawName: String): String {
        val normalized = rawName.removePrefix("$")
        return symbolReplacements[normalized] ?: normalized
    }
}
