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
        // Tipos retirados porque fijaban supuestos de un país: unidades solo métricas, pasos de redondeo de una moneda, una
        // clase de dinero con nombre de moneda y una lista cerrada de métodos de pago con marcas. Las unidades y los métodos de
        // pago son tablas editables, y el paso, un entero que escribe la persona.
        val retiredType = Regex("""\b([U]nitOfMeasure|[P]riceRoundingStep|[P]aymentMethod)\b|\bclass\s+[C]op\b""")
        // El dinero va en la unidad menor de la moneda del negocio, sin sufijo de moneda ni de unidad.
        val moneySuffix = Regex("""[a-z0-9]_([c]op|[p]esos|[m]inor)(?![a-z0-9])""")
        // El dominio es Kotlin puro: sin java.*, tampoco java.math para multiplicar en 128 bits. Solo Math.*Exact, que no
        // necesita import.
        val domainJavaUse = Regex("""^\s*import\s+javax?\.|\bjavax?\.[a-z]+\.[A-Z]""")
        val domainMathCall = Regex("""\bMath\.(?!\w+Exact\b)\w+""")
        // Un solo archivo escribe en el registro del sistema, y lo que recibe ya pasó por tipos que no admiten montos, nombres ni
        // el mensaje de una excepción. La salida estándar y la traza de una excepción también llegan al registro, con el mensaje.
        val directLog = Regex("""\bandroid\.util\.[L]og\b|\bprint(ln)?\(|\bSystem\.(out|err)\b|\.print[S]tackTrace\(""")
        val logSink = "data/src/main/java/io/github/yesidrobayo/shopledger/data/log/AndroidEventLog.kt"
        // Los libros, de stock, de clientes, de dinero y de proveedores, las líneas de venta, de compra, de devolución, de
        // transferencias y de producción, los componentes de un combo vendido, los conteos de caja, los pagos de una venta y las
        // cotizaciones solo crecen: ningún DAO los edita ni los borra, porque el saldo, el costo, lo vendido, lo cobrado y cada
        // repreciado salen de ellos. Una venta, una compra, una devolución, una transferencia o un lote de producción tampoco se
        // borran: solo se anulan. Un INSERT OR REPLACE borra la fila que choca sin pasar por la guarda de borrado.
        val dataMain = Regex("""^data/src/main/""")
        val appendOnlyTables =
            listOf(
                "stock_movements",
                "customer_ledger_entries",
                "loyalty_ledger_entries",
                "money_movements",
                "supplier_ledger_entries",
                "cash_session_counts",
                "sale_lines",
                "purchase_lines",
                "sale_line_components",
                "stock_transfer_lines",
                "production_batch_lines",
                "sale_return_lines",
                "purchase_return_lines",
                "sale_payments",
                "fx_rates",
            ).joinToString("|")
        // El registro de auditoría tampoco se edita, pero su poda sí borra lo que pasa del tope. Nunca con REPLACE.
        val ledgerEdit = Regex("""\b[U]PDATE(\s+OR\s+[A-Z]+)?\s+($appendOnlyTables|audit_events)\b""", RegexOption.IGNORE_CASE)
        val ledgerRemoval =
            Regex(
                """\b([D]ELETE\s+FROM|[R]EPLACE\s+INTO|[I]NSERT\s+OR\s+REPLACE\s+INTO)\s+($appendOnlyTables|sales|purchases|stock_transfers|production_batches|sale_returns|purchase_returns)\b""",
                RegexOption.IGNORE_CASE,
            )
        // Un gasto tampoco se borra: solo se anula. Los ajustes y el estado del respaldo son una sola fila que nace con la base: sin
        // ella la app no arranca, y sin la del respaldo un cambio quedaría sin marca. Un saldo por ubicación y lote es la suma de
        // sus filas del libro: borrarlo o reemplazarlo lo dejaría sin ellas. Las categorías de gasto, las alertas de stock, los
        // clientes, las plantillas de mensaje, las cuentas de dinero, las sesiones de caja y sus denominaciones nunca se reemplazan con
        // REPLACE, que borraría la fila que choca sin pasar por su regla de borrado.
        val documentAndSingleRowRemoval =
            Regex(
                """\b([D]ELETE\s+FROM|[R]EPLACE\s+INTO|[I]NSERT\s+OR\s+REPLACE\s+INTO)\s+(expenses|app_settings|backup_state|stock_balances)\b""",
                RegexOption.IGNORE_CASE,
            )
        val replaceableTables =
            listOf(
                "expense_categories",
                "low_stock_alerts",
                "customers",
                "message_templates",
                "money_accounts",
                "cash_sessions",
                "cash_denominations",
                "audit_events",
            ).joinToString("|")
        val catalogReplace =
            Regex("""\b([R]EPLACE\s+INTO|[I]NSERT\s+OR\s+REPLACE\s+INTO)\s+($replaceableTables)\b""", RegexOption.IGNORE_CASE)

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
                if (isSource && mainSource.containsMatchIn(path) && path != logSink && directLog.containsMatchIn(line)) {
                    problems += "$where: solo AndroidEventLog escribe en el registro del sistema: usa EventLog"
                }
                if (uiSource.containsMatchIn(path) && uiForbiddenImport.containsMatchIn(line)) {
                    problems += "$where: una pantalla no importa la capa de datos ni un puerto: usa un caso de uso"
                }
                if (retiredType.containsMatchIn(line)) {
                    problems += "$where: ese tipo se retiró porque suponía un país: usa su tabla editable o un paso entero"
                }
                if (moneySuffix.containsMatchIn(line)) {
                    problems += "$where: el dinero va en unidad menor sin sufijo de moneda ni de unidad"
                }
                if (isSource && domainMain.containsMatchIn(path) &&
                    (domainJavaUse.containsMatchIn(line) || domainMathCall.containsMatchIn(line))
                ) {
                    problems += "$where: el dominio es Kotlin puro: sin java.* y, de Math, solo las operaciones Exact"
                }
                if (isSource && dataMain.containsMatchIn(path) &&
                    (ledgerEdit.containsMatchIn(line) || ledgerRemoval.containsMatchIn(line))
                ) {
                    problems += "$where: los libros y las líneas solo se agregan, y una venta, una compra o una devolución solo se anulan"
                }
                if (isSource && dataMain.containsMatchIn(path) && documentAndSingleRowRemoval.containsMatchIn(line)) {
                    problems += "$where: un gasto solo se anula, y los ajustes y el estado del respaldo nunca se borran ni se reemplazan"
                }
                if (isSource && dataMain.containsMatchIn(path) && catalogReplace.containsMatchIn(line)) {
                    problems += "$where: sin REPLACE, que borraría la fila que choca sin pasar por su regla de borrado"
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
