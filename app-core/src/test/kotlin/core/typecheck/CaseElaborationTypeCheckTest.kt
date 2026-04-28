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

    @Test
    fun supportsCaseOnOrReturnedFromAndRight() {
        val source = """
            inductive ∃ : {A : Type} → (B : A → Type) → Type {
              make : {A : Type} → {B : A → Type} → (a : A) → (b : B(a)) → ∃{A}(B);
            }

            inductive True : Type {
               obvious : True;
            }

            inductive or : (A: Type) → (B: Type) → Type {
               lhs : {A : Type } → {B: Type} → A → or (A, B);
               rhs : {A : Type } → {B: Type} → B → or (A, B);
            }
            infixl 6 or;

            def and (A : Type), (B : Type) : Type := ∃{A}(λ(_ : A) => B);
            infixl 7 and;

            def × {A : Type}, {B : Type}, (a : A), (b : B) : (A and B) := make{A}{λ(_ : A) => B}(a, b);
            infixl 7 ×;

            def fst {A : Type}, {B : A → Type}, p : ∃(B) : A :=
              case p of {
                make(a, _) => a;
              };

            def snd {A : Type}, {B : A → Type}, p : ∃{A}(B) : B(fst(p)) :=
              case p of {
                make(a, b) => b;
              };

            def and_left {A : Type}, {B : Type}, p : (A and B) : A := fst(p);
            def and_right {A : Type}, {B : Type}, p : (A and B) : B := snd(p);

            theorem and_or_distrib_left {A : Type}, {B : Type}, {C : Type},
              p : A and (B or C) : (A and B) or (A and C) :=
              case and_right(p) of {
                lhs(b) => lhs(and_left(p) × b);
                rhs(c) => rhs(and_left(p) × c);
              };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun supportsCaseOnOrWithOverlappingParameterNames() {
        val source = """
            inductive True : Type {
               obvious : True;
            }

            inductive or : (A: Type) → (B: Type) → Type {
               lhs : {A : Type } → {B: Type} → A → or (A, B);
               rhs : {A : Type } → {B: Type} → B → or (A, B);
            }
            infixl 6 or;

            def absorb_or {B : Type}, {C : Type}, p : B or C : True :=
              case p of {
                lhs(_) => obvious;
                rhs(_) => obvious;
              };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

  @Test
  fun supportsStandardProofs() {
    val source = """
      def const {A : Type}, {B : Type}: B → A → B := λb, _  => b;

      inductive ℕ : Type {
        zero : ℕ;
        succ: ℕ → ℕ;
      }

      infixl 3 =;
      newtype = : {A : Type} → A → A → Type {
        constructor refl : {A : Type} → (x : A) → x = x;
        recursor J : {A : Type} → (x : A) → (P : (y : A) → x = y → Type) → P(x, refl(x)) → (y : A) → (p : x = y) → P(y, p);
        rule J.refl: J{A}(x, P, pr, x, refl{A}(x)) ↦ pr;
      } 

      theorem cong {A : Type}, {B : Type}, f : A → B, x : A, y : A, p : x = y : f(x) = f(y) := 
        J(x, λy, _ => f(x) = f(y), refl(f(x)), y, p);

      lemma testJ : cong(succ, zero, zero, refl(zero)) = refl(succ(zero)) := refl(refl(succ(zero))) ;

      infixl 6 +;
      def + : ℕ → ℕ → ℕ := λ m, n => case m of {
        zero => n;
        succ(k) => succ(k + n);
      };

      def one : ℕ := succ(zero);
      def two : ℕ := succ(one);
      def three : ℕ := succ(two);
      def four : ℕ := succ(three);
      def five : ℕ := succ(four);

      lemma two_plus_two_is_four : two + two = four := refl(four);

      theorem add_assoc (a : ℕ), (b : ℕ), (c : ℕ) : (a + b) + c = a + (b + c) := 
        case a of {
          zero => refl(b + c);
          succ(a) => cong(succ, (a + b) + c, a + (b + c), add_assoc(a, b, c)) ;
        }; 

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

      inductive ∃ : {A : Type} → (B : A → Type) → Type {
        make : {A : Type} → {B : A → Type} → (a : A) → (b : B(a)) → ∃{A}(B);
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

      inductive True : Type {
         obvious : True;
      }

      inductive False : Type {}

      inductive or : (A: Type) → (B: Type) → Type {
         lhs : {A : Type } → {B: Type} → A → or (A, B);
         rhs : {A : Type } → {B: Type} → B → or (A, B);
      }
      infixl 6 or;

      def and (A : Type), (B : Type) : Type := ∃{A}(λ(_ : A) => B);
      infixl 7 and;

      def × {A : Type}, {B : Type}, (a : A), (b : B) : (A and B) := make{A}{λ(_ : A) => B}(a, b);
      infixl 7 ×;

      def and_left {A : Type}, {B : Type}, p : (A and B) : A := fst(p);

      def and_right {A : Type}, {B : Type}, p : (A and B) : B := snd(p);

      theorem and_comm {A : Type}, {B : Type}, p : A and B : B and A :=
        and_right(p) × and_left(p);

      theorem and_assoc {A : Type}, {B : Type}, {C : Type}, p : (A and B) and C : A and (B and C) :=
        and_left(and_left(p)) × (and_right(and_left(p)) × and_right(p));

      theorem and_assoc_inv {A : Type}, {B : Type}, {C : Type}, p : A and (B and C) : (A and B) and C :=
        (and_left(p) × and_left(and_right(p))) × and_right(and_right(p));

      theorem true_and {A : Type}, a : A : True and A :=
        obvious × a;

      theorem and_true {A : Type}, a : A : A and True :=
        a × obvious;

      theorem and_or_distrib_left {A : Type}, {B : Type}, {C : Type},
        p : A and (B or C) : (A and B) or (A and C) :=
        case and_right(p) of {
          lhs(b) => lhs(and_left(p) × b);
          rhs(c) => rhs(and_left(p) × c);
        };

      theorem and_or_distrib_right {A : Type}, {B : Type}, {C : Type},
        p : (A or B) and C : (A and C) or (B and C) :=
        case and_left(p) of {
          lhs(a) => lhs(a × and_right(p));
          rhs(b) => rhs(b × and_right(p));
        };

      theorem or_and_distrib_left {A : Type}, {B : Type}, {C : Type},
        p : A or (B and C) : (A or B) and (A or C) :=
        case p of {
          lhs(a) => lhs(a) × lhs(a);
          rhs(bc) => rhs(and_left(bc)) × rhs(and_right(bc));
        };

      theorem or_and_distrib_right {A : Type}, {B : Type}, {C : Type},
        p : (A and B) or C : (A or C) and (B or C) :=
        case p of {
          lhs(ab) => lhs(and_left(ab)) × lhs(and_right(ab));
          rhs(c) => rhs(c) × rhs(c);
        };

      theorem exists_intro {A : Type}, {B : A → Type}, a : A, b : B(a) : ∃{A}(B) :=
        make(a, b);

      theorem exists_elim {A : Type}, {B : A → Type}, {C : Type}, p : ∃{A}(B), (f : ∀(a : A), (b : B(a)) => C) : C :=
        case p of {
          make(a, b) => f(a, b);
        };

      theorem forall_and_left {A : Type}, {P : A → Type}, {Q : A → Type},
        h : ∀(x : A) => P(x) and Q(x),
        x : A
        : P(x) :=
        and_left(h(x));

      theorem forall_and_right {A : Type}, {P : A → Type}, {Q : A → Type},
        h : ∀(x : A) => P(x) and Q(x),
        x : A
        : Q(x) :=
        and_right(h(x));

      theorem forall_and_intro {A : Type}, {P : A → Type}, {Q : A → Type},
        (hp : ∀(x : A) => P(x)),
        (hq : ∀(x : A) => Q(x))
        : (∀(x : A) => P(x) and Q(x)) :=
        λ x => hp(x) × hq(x);

      theorem exists_or_left {A : Type}, {P : A → Type}, {Q : A → Type},
        p : ∃{A}(P) : ∃{A}(λ x => P(x) or Q(x)) :=
        case p of {
          make(a, pa) => make(a, lhs(pa));
        };

      theorem exists_or_right {A : Type}, {P : A → Type}, {Q : A → Type},
        p : ∃{A}(Q)
        : ∃{A}(λ x => P(x) or Q(x)) :=
        case p of {
          make(a, qa) => make(a, rhs(qa));
        };

      theorem exists_or_elim {A : Type}, {P : A → Type}, {Q : A → Type},
        p : ∃{A}(λ x => P(x) or Q(x))
        : (∃{A}(P)) or (∃{A}(Q)) :=
        case p of {
          make(a, pq) =>
            case pq of {
              lhs(pa) => lhs(make(a, pa));
              rhs(qa) => rhs(make(a, qa));
            };
        };

      theorem exists_and_to_exists_left {A : Type}, {P : A → Type}, {Q : A → Type},
        p : ∃{A}(λ x => P(x) and Q(x))
        : ∃{A}(P) :=
        case p of {
          make(a, pq) => make(a, and_left(pq));
        };

      theorem exists_and_to_exists_right {A : Type}, {P : A → Type}, {Q : A → Type},
        p : ∃{A}(λ x => P(x) and Q(x))
        : ∃{A}(Q) :=
        case p of {
          make(a, pq) => make(a, and_right(pq));
        };

      theorem forall_to_exists_function {A : Type}, {B : Type}, (P : A → Type),
        (f : A → B),
        (p : ∃{A}(P))
        : (∃{B}(λ b => ∃{A}(λ a => P(a) and (f(a) = b))) ):=
        case p of {
          make(a, pa) =>
            make(f(a), make(a, pa × refl(f(a))));
        };
      
    """.trimIndent()

    val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
    assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
  }

    @Test
    fun supportsStandardCaseTheoremsSubset() {
        val source = """
            inductive ∃ : {A : Type} → (B : A → Type) → Type {
              make : {A : Type} → {B : A → Type} → (a : A) → (b : B(a)) → ∃{A}(B);
            }

            inductive or : (A: Type) → (B: Type) → Type {
               lhs : {A : Type } → {B: Type} → A → or (A, B);
               rhs : {A : Type } → {B: Type} → B → or (A, B);
            }
            infixl 6 or;

            def and (A : Type), (B : Type) : Type := ∃{A}(λ(_ : A) => B);
            infixl 7 and;

            def × {A : Type}, {B : Type}, (a : A), (b : B) : (A and B) := make{A}{λ(_ : A) => B}(a, b);
            infixl 7 ×;

            def fst {A : Type}, {B : A → Type}, p : ∃(B) : A :=
              case p of {
                make(a, _) => a;
              };

            def snd {A : Type}, {B : A → Type}, p : ∃{A}(B) : B(fst(p)) :=
              case p of {
                make(a, b) => b;
              };

            def and_left {A : Type}, {B : Type}, p : (A and B) : A := fst(p);
            def and_right {A : Type}, {B : Type}, p : (A and B) : B := snd(p);

            theorem and_or_distrib_left {A : Type}, {B : Type}, {C : Type},
              p : A and (B or C) : (A and B) or (A and C) :=
              case and_right(p) of {
                lhs(b) => lhs(and_left(p) × b);
                rhs(c) => rhs(and_left(p) × c);
              };

            theorem exists_elim {A : Type}, {B : A → Type}, {C : Type}, p : ∃{A}(B), (f : ∀(a : A), (b : B(a)) => C) : C :=
              case p of {
                make(a, b) => f(a, b);
              };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun rejectsIncorrectStandardCaseTheoremBranchType() {
        val source = """
            inductive ∃ : {A : Type} → (B : A → Type) → Type {
              make : {A : Type} → {B : A → Type} → (a : A) → (b : B(a)) → ∃{A}(B);
            }

            inductive True : Type {
              obvious : True;
            }

            theorem bad_exists_elim {A : Type}, {B : A → Type}, p : ∃{A}(B) : True :=
              case p of {
                make(a, b) => a;
              };
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(
            diagnostics.any { it.message.contains("Type mismatch") },
            "Expected type mismatch diagnostic, got: $diagnostics",
        )
    }
}
