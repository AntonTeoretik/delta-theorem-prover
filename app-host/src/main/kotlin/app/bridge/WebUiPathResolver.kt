package app.bridge

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object WebUiPathResolver {
    fun resolveDistPath(): Path {
        val configured = System.getProperty("app.webui.dist")?.let(Paths::get)
        val fromEnv = System.getenv("APP_WEBUI_DIST")?.let(Paths::get)

        val candidates = listOfNotNull(
            configured,
            fromEnv,
            Paths.get("app-webui", "dist"),
            Paths.get("..", "app-webui", "dist"),
        )

        return candidates
            .map(Path::toAbsolutePath)
            .firstOrNull { Files.exists(it.resolve("index.html")) }
            ?: error(
                "Cannot find app-webui/dist/index.html. Build web UI first: npm install && npm run build in app-webui",
            )
    }
}
