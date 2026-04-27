package core.typecheck

import core.parser.SimpleTextParser
import kotlin.test.Test
import kotlin.test.assertTrue

class ImplicitRewriteElaborationTest {
    @Test
    fun originalCongAndTestJProgramTypechecks() {
        val source = """
            newtype ℕ : Type {
              constructor zero : ℕ;
              constructor succ: ℕ → ℕ;

              recursor natRec : (P : ℕ → Type) → P(zero) → ((n : ℕ) → P(n) → P(succ(n))) → (n : ℕ) → P(n);

              rule natRec.zero: natRec(P, z, s, zero) ↦ z;
              rule natRec.succ: natRec(P, z, s, succ(n)) ↦ s(n, natRec(P, z, s, n));
            }

            infixl 3 =;
            newtype = : {A : Type} → A → A → Type {
              constructor refl : {A : Type} → (x : A) → x = x;
              recursor J : {A : Type} → (x : A) →
                (P : (y : A) → x = y → Type) →
                P(x, refl(x)) →
                (y : A) →
                (p : x = y) →
                P(y, p);

              rule J.refl: J{A}(x, P, pr, x, refl{A}(x)) ↦ pr;
            }

            theorem cong {A : Type}, {B : Type}, f : A → B, x : A, y : A, p : x = y : f(x) = f(y) :=
              J(x, λ(y : A), p => f(x) = f(y), refl(f(x)), y, p);

            lemma testJ : cong(succ, zero, zero, refl(zero)) = refl(succ(zero)) :=
              refl(refl(succ(zero)));
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }
}
