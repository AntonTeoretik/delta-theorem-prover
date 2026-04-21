plugins {
    application
    kotlin("jvm")
}

dependencies {
    implementation(project(":app-core"))
    implementation("me.friwi:jcefmaven:135.0.20")
}

application {
    mainClass = "app.MainKt"
    applicationDefaultJvmArgs = listOf(
        "-Dapp.webui.dist=${rootProject.projectDir.resolve("app-webui/dist").invariantSeparatorsPath}",
        "-Dsun.java2d.dpiaware=true",
        "-Dsun.java2d.uiScale.enabled=true",
        "-Dsun.java2d.uiScale=1.0",
        "-Dsun.java2d.d3d=false",
    )
}

kotlin {
    jvmToolchain(17)
}
