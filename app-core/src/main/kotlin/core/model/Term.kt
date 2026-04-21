package core.model

sealed interface Term {
    data class Variable(val name: String) : Term

    data class Constant(val name: String) : Term

    data class Lambda(
        val parameter: String,
        val body: Term,
    ) : Term

    data class Application(
        val function: Term,
        val argument: Term,
    ) : Term
}
