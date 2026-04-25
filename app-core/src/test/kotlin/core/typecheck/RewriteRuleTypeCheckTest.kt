package core.typecheck

import core.parser.SimpleTextParser
import kotlin.test.Test
import kotlin.test.assertTrue

class RewriteRuleTypeCheckTest {
    @Test
    fun twoPlusTwoIsFourTypeChecksWithRewriteRules() {
        val source = """
            ℕ : Type;
            zero : ℕ;
            succ : ℕ → ℕ;

            natRec :
              (P : ℕ → Type) →
              P(zero) →
              ((n : ℕ) → P(n) → P(succ(n))) →
              (n : ℕ) →
              P(n);

            rule natRec.zero:
              natRec(P, z, s, zero) ↦ z;

            rule natRec.succ:
              natRec(P, z, s, succ(n)) ↦ s(n, natRec(P, z, s, n));

            Id : (A : Type) → A → A → Type;

            refl :
              (A : Type) → (x : A) → Id(A, x, x);

            J :
              (A : Type) →
              (x : A) →
              (P : (y : A) → Id(A, x, y) → Type) →
              P(x, refl(A, x)) →
              (y : A) →
              (p : Id(A, x, y)) →
              P(y, p);

            rule J.refl:
              J(A, x, P, pr, x, refl(A, x)) ↦ pr;

            add : ℕ → ℕ → ℕ
            := λ(m : ℕ) => λ(n : ℕ) =>
                 natRec(
                   λ(_ : ℕ) => ℕ,
                   n,
                   λ(_ : ℕ) => λ(rec : ℕ) => succ(rec),
                   m
                 );

            one : ℕ := succ(zero);
            two : ℕ := succ(one);
            three : ℕ := succ(two);
            four : ℕ := succ(three);

            two_plus_two_is_four : Id(ℕ, add(two, two), four) := refl(ℕ, four);
        """.trimIndent()

        val parsed = SimpleTextParser().parse(source)
        val result = TypeChecker(parsed).checkProgram()

        val mismatch = result.diagnostics.any { it.message.contains("two_plus_two_is_four") && it.message.contains("type mismatch") }
        assertTrue(
            !mismatch,
            "Expected no type mismatch for two_plus_two_is_four, diagnostics: ${result.diagnostics}",
        )
    }
}
