// Archivo de compilación de la raíz: plugins comunes a los tres módulos.
plugins {
    id("base")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

// Reglas del código que corren dentro de check, porque no hay integración continua que las haga cumplir.
// Los patrones se escriben con corchetes para que este mismo archivo no los contenga.
val verifyCodeRules = tasks.register("verifyCodeRules") {
    group = "verification"
    description = "Busca en el código lo que nunca debe aparecer en él."

    // Se resuelven al configurar: la acción solo recibe archivos y textos, y así sirve con la caché de configuración.
    val root = rootDir
    val scanRoots =
        subprojects.map { it.projectDir } +
            listOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml").map { file(it) }

    doLast {
        // El nombre comercial solo vive en app_name y en rootProject.name, que se llama como el repositorio.
        val commercialName = Regex("[i]mpulsito", RegexOption.IGNORE_CASE)
        val stringsFile = Regex("""^app/src/main/res/values(-[A-Za-z0-9+-]+)?/strings\.xml$""")
        val appNameLine = Regex("""name="app_name"""")
        val rootProjectNameLine = Regex("""^\s*rootProject\.name = "[I]mpulsito"\s*$""")
        // Una migración destructiva borraría el negocio de la persona al actualizar.
        val destructiveMigration = Regex("fallbackTo[D]estructiveMigration")
        // Los formatos salen del formato regional elegido, nunca del idioma del sistema.
        val systemLocale = Regex("""Locale\.getDefault\(""")
        val mainSource = Regex("""^(app|data|domain)/src/main/""")
        // Una pantalla que importa la capa de datos o un puerto se salta el caso de uso y su transacción.
        val uiSource = Regex("""^app/src/main/(java|kotlin)/io/github/yesidrobayo/shopledger/ui/""")
        val uiForbiddenImport =
            Regex("""^\s*import\s+io\.github\.yesidrobayo\.shopledger\.(data|domain\.port)(\.|\s|$)""")

        val binary = setOf("png", "webp", "jpg", "jpeg", "gif", "ttf", "otf", "jar", "so", "jks", "keystore", "db", "gz", "zip", "apk")
        val skippedDirs = setOf("build", ".gradle", ".kotlin", ".idea", ".cxx")
        val files =
            scanRoots
                .flatMap { start ->
                    when {
                        start.isFile -> listOf(start)
                        start.isDirectory -> start.walkTopDown().onEnter { it.name !in skippedDirs }.filter { it.isFile }.toList()
                        else -> emptyList()
                    }
                }.filter { it.extension.lowercase() !in binary }

        val problems = mutableListOf<String>()
        // Tampoco en el nombre de un archivo, que también cubre los binarios. Ruta relativa al repositorio: la absoluta
        // incluye la carpeta del repositorio, que lleva el nombre comercial.
        val allFiles =
            scanRoots.flatMap { start ->
                if (start.isDirectory) start.walkTopDown().onEnter { it.name !in skippedDirs }.filter { it.isFile }.toList() else listOf(start)
            }
        for (file in allFiles) {
            val path = file.relativeTo(root).invariantSeparatorsPath
            if (commercialName.containsMatchIn(path)) problems += "$path: el nombre comercial no va en el nombre de un archivo"
        }
        for (file in files) {
            val path = file.relativeTo(root).invariantSeparatorsPath
            val isSource = file.extension in setOf("kt", "java")
            file.readLines().forEachIndexed { index, line ->
                val where = "$path:${index + 1}"
                if (commercialName.containsMatchIn(line)) {
                    val allowed =
                        (stringsFile.matches(path) && appNameLine.containsMatchIn(line)) ||
                            (path == "settings.gradle.kts" && rootProjectNameLine.matches(line))
                    if (!allowed) problems += "$where: el nombre comercial solo puede estar en app_name y en rootProject.name"
                }
                if (destructiveMigration.containsMatchIn(line)) {
                    problems += "$where: una migración destructiva puede borrar los datos al actualizar"
                }
                if (isSource && mainSource.containsMatchIn(path) && systemLocale.containsMatchIn(line)) {
                    problems += "$where: el formato sale del formato regional elegido, no del idioma del sistema"
                }
                if (uiSource.containsMatchIn(path) && uiForbiddenImport.containsMatchIn(line)) {
                    problems += "$where: una pantalla no importa la capa de datos ni un puerto: usa un caso de uso"
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Reglas del código rotas (${problems.size}):\n" + problems.joinToString("\n"))
        }
        logger.lifecycle("verifyCodeRules: ${files.size} archivos revisados, sin problemas")
    }
}

tasks.named("check") { dependsOn(verifyCodeRules) }
