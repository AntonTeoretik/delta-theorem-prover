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
            diagnostics.any { it.message.contains("missing case branches") || it.message.contains("must match constructors exactly") },
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
    fun reducesRecursiveCaseOnConcreteTree() {
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

            lemma sampleTreeOfSize4 : size(sampleTree) = five := refl(five);
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
}
