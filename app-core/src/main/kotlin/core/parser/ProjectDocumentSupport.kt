package core.parser

import core.model.Diagnostic

internal data class DeltaProjectFile(
    val path: String,
    val content: String,
)

internal data class DeltaProjectDocument(
    val files: List<DeltaProjectFile>,
    val activeFile: String,
    val diagnostics: List<Diagnostic>,
)

internal object DeltaProjectCodec {
    private const val HEADER = "--!delta-project v1"
    private const val FILE_PREFIX = "--!file "
    private val importRegex = Regex("""^\s*import\s+([A-Za-z_][A-Za-z0-9_\-.]*|\"[^\"]+\")\s*;\s*$""")

    fun decode(source: String): DeltaProjectDocument {
        val normalized = source.replace("\r\n", "\n")
        if (!normalized.startsWith(HEADER)) {
            return DeltaProjectDocument(
                files = listOf(DeltaProjectFile(DEFAULT_FILE, normalized)),
                activeFile = DEFAULT_FILE,
                diagnostics = emptyList(),
            )
        }

        val lines = normalized.split('\n')
        val files = mutableListOf<DeltaProjectFile>()
        var currentPath: String? = null
        val currentBody = StringBuilder()
        val diagnostics = mutableListOf<Diagnostic>()
        var offset = 0

        fun flushCurrent() {
            val path = currentPath ?: return
            files += DeltaProjectFile(path, currentBody.toString().trimEnd('\n'))
            currentBody.clear()
        }

        for ((index, line) in lines.withIndex()) {
            if (index == 0) {
                offset += line.length + 1
                continue
            }
            if (line.startsWith(FILE_PREFIX)) {
                flushCurrent()
                currentPath = line.removePrefix(FILE_PREFIX).trim().ifBlank { null }
                if (currentPath == null) {
                    diagnostics += Diagnostic(
                        message = "Empty project file path",
                        line = index + 1,
                        column = 1,
                        startOffset = offset,
                        endOffset = offset + line.length,
                    )
                }
            } else if (currentPath != null) {
                currentBody.append(line).append('\n')
            }
            offset += line.length + 1
        }
        flushCurrent()

        val normalizedFiles = if (files.isEmpty()) {
            listOf(DeltaProjectFile(DEFAULT_FILE, ""))
        } else files

        val active = normalizedFiles.first().path
        return DeltaProjectDocument(normalizedFiles, active, diagnostics)
    }

    fun encode(files: List<DeltaProjectFile>, activeFile: String): String {
        val ordered = files.sortedBy { if (it.path == activeFile) "" else it.path }
        val body = ordered.joinToString("\n") { file ->
            buildString {
                append(FILE_PREFIX)
                append(file.path)
                append('\n')
                append(file.content.trimEnd())
                append('\n')
            }
        }
        return "$HEADER\n$body".trimEnd() + "\n"
    }

    fun composeForTypecheck(project: DeltaProjectDocument): String {
        val byPath = project.files.associateBy { it.path }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val chunks = mutableListOf<String>()

        fun resolve(path: String) {
            if (path in visited) return
            if (!visiting.add(path)) return
            val file = byPath[path] ?: return
            val lines = file.content.lines()
            val body = mutableListOf<String>()
            for (line in lines) {
                val match = importRegex.find(line)
                if (match != null) {
                    val raw = match.groupValues[1]
                    val target = normalizeImport(raw)
                    resolve(target)
                } else {
                    body += line
                }
            }
            visiting.remove(path)
            visited.add(path)
            chunks += body.joinToString("\n").trim()
        }

        resolve(project.activeFile)
        return chunks.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun normalizeImport(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
            trimmed.substring(1, trimmed.length - 1)
        } else if (trimmed.contains('.')) {
            trimmed
        } else {
            "$trimmed.$DEFAULT_EXT"
        }
    }

    const val DEFAULT_EXT = "dlt"
    const val DEFAULT_FILE = "main.$DEFAULT_EXT"
}
