package core.model

sealed interface Term {
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
        val body: Term,
        val parameterSpan: TextSpan,
    ) : Term

    data class Application(
        val function: Term,
        val argument: Term,
    ) : Term
}
