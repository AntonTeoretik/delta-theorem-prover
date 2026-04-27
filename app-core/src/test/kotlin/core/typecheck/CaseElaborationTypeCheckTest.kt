package core.typecheck

import core.parser.SimpleTextParser
import kotlin.test.Test
import kotlin.test.assertTrue

class CaseElaborationTypeCheckTest {
    @Test
    fun checksCaseExpressionAgainstExpectedType() {
        val source = """
            inductive ℕ : Type {
              zero : ℕ;
              succ : ℕ → ℕ;
            }

            def pred : ℕ → ℕ := λ(n : ℕ) => case n of {
              zero => zero;
              succ(k) => k;
            };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun rejectsInferringCaseExpressionType() {
        val source = """
            inductive ℕ : Type {
              zero : ℕ;
              succ : ℕ → ℕ;
            }

            bad := case zero of {
              zero => zero;
              succ(k) => k;
            };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(
            diagnostics.any { it.message.contains("cannot infer type of case expression; type annotation required") },
            "Expected inference diagnostic, got: $diagnostics",
        )
    }

    @Test
    fun rejectsCaseWithMissingBranches() {
        val source = """
            inductive ℕ : Type {
              zero : ℕ;
              succ : ℕ → ℕ;
            }

            def bad : ℕ → ℕ := λ(n : ℕ) => case n of {
              zero => zero;
            };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(
            diagnostics.any { it.message.contains("missing case for constructor") },
            "Expected branch coverage diagnostic, got: $diagnostics",
        )
    }

    @Test
    fun doesNotLoopWhenCaseBranchContainsRecursiveCall() {
        val source = """
            inductive Tree : Type {
              leaf : Tree;
              node : Tree → Tree → Tree;
            }

            def size (t : Tree) : Tree :=
              case t of {
                leaf => leaf;
                node(left, right) => size(left);
              };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun typechecksRecursiveCaseDefinitionWithoutReducingAtRuntime() {
        val source = """
            inductive ℕ : Type {
              zero : ℕ;
              succ : ℕ → ℕ;
            }

            def + : ℕ → ℕ → ℕ := λ m, n =>
              ℕ.rec(
                λ(_ : ℕ) => ℕ,
                n,
                λ(_ : ℕ) => λ(rec : ℕ) => succ(rec),
                m
              );

            infixl 6 +;

            def one : ℕ := succ(zero);
            def two : ℕ := succ(one);
            def three : ℕ := succ(two);
            def four : ℕ := succ(three);
            def five : ℕ := succ(four);

            infixl 3 =;

            newtype = : {A : Type} → A → A → Type {
              constructor refl : {A : Type} → (x : A) → x = x;
            }

            inductive Tree : Type {
              leaf : Tree;
              node : Tree → Tree → Tree;
            }

            def size (t : Tree) : ℕ :=
              case t of {
                leaf => one;
                node(left, right) => size(left) + size(right) + one;
              };

            def sampleTree : Tree := node(leaf, node(leaf, leaf));

            def sampleTreeSize : ℕ := size(sampleTree);
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun supportsDependentCaseMotiveForScrutineeVariable() {
        val source = """
            inductive ℕ : Type {
              zero : ℕ;
              succ : ℕ → ℕ;
            }

            infixl 3 =;

            newtype = : {A : Type} → A → A → Type {
              constructor refl : {A : Type} → (x : A) → x = x;
            }

            theorem self_eq_case : ∀(a : ℕ) => a = a :=
              λ a =>
                case a of {
                  zero => refl(zero);
                  succ(k) => refl(succ(k));
                };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun supportsCaseForParameterizedExists() {
        val source = """
            inductive ∃ : {A : Type} → (B : A → Type) → Type {
              make : {A : Type} → {B : A → Type} → (a : A) → (b : B(a)) → ∃{A}(B);
            }

            def fst {A : Type}, {B : A → Type}, p : ∃{A}(B) : A :=
              case p of {
                make(a, b) => a;
              };

            def fst2 {A : Type}, {B : A → Type}, p : ∃{A}(B) : A :=
              case p of {
                make(x, _) => x;
              };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun supportsCaseDefinedAdditionComputingToReflGoal() {
        val source = """
            inductive ℕ : Type {
              zero : ℕ;
              succ : ℕ → ℕ;
            }

            infixl 3 =;
            newtype = : {A : Type} → A → A → Type {
              constructor refl : {A : Type} → (x : A) → x = x;
            }

            infixl 6 +;
            def + : ℕ → ℕ → ℕ := λ m, n => case m of {
              zero => n;
              succ(k) => succ(k + n);
            };

            def one : ℕ := succ(zero);
            def two : ℕ := succ(one);
            def three : ℕ := succ(two);
            def four : ℕ := succ(three);

            lemma two_plus_two_is_four : two + two = four := refl(four);
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun supportsAddAssocProofUsingCase() {
        val source = """
            inductive ℕ : Type {
              zero : ℕ;
              succ : ℕ → ℕ;
            }

            infixl 3 =;
            newtype = : {A : Type} → A → A → Type {
              constructor refl : {A : Type} → (x : A) → x = x;
              recursor J : {A : Type} → (x : A) → (P : (y : A) → x = y → Type) → P(x, refl(x)) → (y : A) → (p : x = y) → P(y, p);
              rule J.refl: J{A}(x, P, pr, x, refl{A}(x)) ↦ pr;
            }

            theorem cong {A : Type}, {B : Type}, f : A → B, x : A, y : A, p : x = y : f(x) = f(y) :=
              J(x, λy, _ => f(x) = f(y), refl(f(x)), y, p);

            infixl 6 +;
            def + : ℕ → ℕ → ℕ := λ m, n => case m of {
              zero => n;
              succ(k) => succ(k + n);
            };

            theorem add_assoc (a : ℕ), (b : ℕ), (c : ℕ) : (a + b) + c = a + (b + c) :=
              case a of {
                zero => refl(b + c);
                succ(a) => cong(succ, (a + b) + c, a + (b + c), add_assoc(a, b, c));
              };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun supportsDependentReturnTypeThroughCaseForExists() {
        val source = """
            inductive ∃ : {A : Type} → (B : A → Type) → Type {
              make : {A : Type} → {B : A → Type} → (a : A) → (b : B(a)) → ∃{A}(B);
            }

            infixl 3 =;
            newtype = : {A : Type} → A → A → Type {
              constructor refl : {A : Type} → (x : A) → x = x;
            }

            def fst {A : Type}, {B : A → Type}, p : ∃(B) : A :=
              case p of {
                make(a, _) => a;
              };

            def snd {A : Type}, {B : A → Type}, p : ∃{A}(B) : B(fst(p)) :=
              case p of {
                make(a, b) => b;
              };

            lemma fst_snd_eq {A : Type}, {B : A → Type}, p : ∃{A}(B) : p = make(fst(p), snd(p)) :=
              case p of {
                make(a, b) => refl(make(a, b));
              };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }
}
