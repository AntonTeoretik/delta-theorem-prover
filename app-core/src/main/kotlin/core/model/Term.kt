package core.model

sealed interface Term {
    enum class Visibility {
        EXPLICIT,
        IMPLICIT,
    }

    data class Variable(
        val name: String,
        val span: TextSpan,
    ) : Term

    data class Constant(
        val name: String,
        val span: TextSpan,
    ) : Term

    data class Lambda(
        val parameter: String,
        val parameterType: Term,
        val body: Term,
        val parameterSpan: TextSpan,
        val visibility: Visibility,
    ) : Term

    data class Pi(
        val parameter: String,
        val parameterType: Term,
        val body: Term,
        val parameterSpan: TextSpan,
        val visibility: Visibility,
    ) : Term

    data class Meta(
        val id: Int,
        val span: TextSpan,
    ) : Term

    data class Typed(
        val term: Term,
        val type: Term,
    ) : Term

    data class Application(
        val function: Term,
        val argument: Term,
        val visibility: Visibility,
    ) : Term

    data class Case(
        val scrutinee: Term,
        val branches: List<CaseBranch>,
        val span: TextSpan,
    ) : Term

    data class CaseBranch(
        val constructorName: String,
        val constructorSpan: TextSpan,
        val parameters: List<CasePatternParameter>,
        val body: Term,
    )

    data class CasePatternParameter(
        val name: String,
        val span: TextSpan,
    )
}
