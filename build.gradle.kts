// Archivo de compilación de la raíz: plugins comunes a los tres módulos.
plugins {
    id("base")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint) apply false
}

// La versión de ktlint sale del catálogo, no de la que trae el plugin por defecto.
val ktlintVersion = libs.versions.ktlint.get()
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
    }
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
        // El cierre automático de Room reabre la conexión de escritura con synchronous = NORMAL, sin el FULL que fija la app
        // al abrir la base: una venta confirmada podría perderse en un corte de energía.
        val autoClose = Regex("set[A]utoCloseTimeout")
        // Los formatos salen del formato regional elegido, nunca del idioma del sistema.
        val systemLocale = Regex("""Locale\.getDefault\(""")
        val mainSource = Regex("""^(app|data|domain)/src/main/""")
        // Las reglas de negocio reciben la zona del negocio por parámetro: nunca leen la del teléfono, que cambia al viajar.
        val systemZone = Regex("""current[S]ystemDefault|[s]ystemDefault\(|TimeZone\.get[D]efault\(""")
        // Código de producción del dominio: lo usan la regla de la zona y la de Kotlin puro.
        val domainMain = Regex("""^domain/src/main/""")
        // Una pantalla que importa la capa de datos o un puerto se salta el caso de uso y su transacción.
        val uiSource = Regex("""^app/src/main/(java|kotlin)/io/github/yesidrobayo/shopledger/ui/""")
        val uiForbiddenImport =
            Regex("""^\s*import\s+io\.github\.yesidrobayo\.shopledger\.(data|domain\.port)(\.|\s|$)""")
        // Tipos retirados porque fijaban supuestos de un país: unidades solo métricas, pasos de redondeo de una moneda y
        // una clase de dinero con nombre de moneda. Las unidades son una tabla editable y el paso, un entero que escribe la persona.
        val retiredType = Regex("""\b([U]nitOfMeasure|[P]riceRoundingStep)\b|\bclass\s+[C]op\b""")
        // El dinero va en la unidad menor de la moneda del negocio, sin sufijo de moneda ni de unidad.
        val moneySuffix = Regex("""[a-z0-9]_([c]op|[p]esos|[m]inor)(?![a-z0-9])""")
        // El dominio es Kotlin puro: sin java.*, tampoco java.math para multiplicar en 128 bits. Solo Math.*Exact, que no
        // necesita import.
        val domainJavaUse = Regex("""^\s*import\s+javax?\.|\bjavax?\.[a-z]+\.[A-Z]""")
        val domainMathCall = Regex("""\bMath\.(?!\w+Exact\b)\w+""")

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
                if (autoClose.containsMatchIn(line)) {
                    problems += "$where: el cierre automático de Room reabre la conexión de escritura sin synchronous = FULL"
                }
                if (isSource && mainSource.containsMatchIn(path) && systemLocale.containsMatchIn(line)) {
                    problems += "$where: el formato sale del formato regional elegido, no del idioma del sistema"
                }
                if (isSource && domainMain.containsMatchIn(path) && systemZone.containsMatchIn(line)) {
                    problems += "$where: las reglas de negocio usan la zona del negocio, nunca la del teléfono"
                }
                if (uiSource.containsMatchIn(path) && uiForbiddenImport.containsMatchIn(line)) {
                    problems += "$where: una pantalla no importa la capa de datos ni un puerto: usa un caso de uso"
                }
                if (retiredType.containsMatchIn(line)) {
                    problems += "$where: ese tipo se retiró porque suponía un país: usa la tabla de unidades o un paso entero"
                }
                if (moneySuffix.containsMatchIn(line)) {
                    problems += "$where: el dinero va en unidad menor sin sufijo de moneda ni de unidad"
                }
                if (isSource && domainMain.containsMatchIn(path) &&
                    (domainJavaUse.containsMatchIn(line) || domainMathCall.containsMatchIn(line))
                ) {
                    problems += "$where: el dominio es Kotlin puro: sin java.* y, de Math, solo las operaciones Exact"
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
