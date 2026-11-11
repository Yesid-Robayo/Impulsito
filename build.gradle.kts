// Archivo de compilación de la raíz: plugins comunes a los tres módulos.
plugins {
    id("base")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
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
        // Una fábrica de ICU o de Java sin región, o un format sin Locale, toma la del proceso igual que Locale.getDefault().
        val implicitLocale =
            Regex(
                """\b(NumberFormat|DecimalFormat|DateFormat|SimpleDateFormat|DecimalFormatSymbols|DateTimePatternGenerator|PluralRules|MessageFormat)\.get\w*Instance\(\)|\bString\.format\(\s*"|\bDecimalFormatSymbols\(\)""",
            )
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
        // Los colores salen de la paleta que eligió la persona: un color escrito en una pantalla se saltaría la paleta y sus
        // contrastes medidos. Solo ui/theme/ guarda valores, y el color dinámico de Android nunca se usa, porque borra la marca.
        val themeSource = Regex("""^app/src/main/(java|kotlin)/io/github/yesidrobayo/shopledger/ui/theme/""")
        val colorLiteral =
            Regex(
                """\b[C]olor\(\s*(0x|red\s*=)|#[0-9A-Fa-f]{6}\b|\b[C]olor\.(Black|White|Gray|LightGray|DarkGray|Red|Green|Blue|Yellow|Cyan|Magenta)\b""",
            )
        val dynamicColor = Regex("""\b[d]ynamic(Light|Dark)ColorScheme\b""")

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
                if (isSource && mainSource.containsMatchIn(path) &&
                    (systemLocale.containsMatchIn(line) || implicitLocale.containsMatchIn(line))
                ) {
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
                if (isSource && path.startsWith("app/src/main/") && !themeSource.containsMatchIn(path) &&
                    colorLiteral.containsMatchIn(line)
                ) {
                    problems += "$where: un color sale de la paleta, con MaterialTheme.colorScheme o AppTheme.colors"
                }
                if (isSource && dynamicColor.containsMatchIn(line)) {
                    problems += "$where: sin color dinámico de Android: la paleta la elige la persona"
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

// Redacción de los textos visibles, también dentro de check. El español es neutro: un solo texto sirve en todo país donde se
// habla, así que una clave de values-es no se dirige a nadie con tú, vos ni usted, la app no habla de sí misma y no hay
// regionalismos. En portugués tampoco se trata de você. Y en ningún idioma un texto llama factura a lo que genera la app, que
// no emite documentos fiscales.
val verifyWording = tasks.register("verifyWording") {
    group = "verification"
    description = "Busca en los textos visibles el tuteo, la primera persona, los regionalismos y los documentos fiscales."

    // Se resuelven al configurar, como en verifyCodeRules: la acción solo recibe archivos.
    val root = rootDir
    val sourceDirs = subprojects.map { it.projectDir.resolve("src") }

    doLast {
        // Palabra entera, con letras de cualquier idioma: el \b de Java solo conoce las ASCII y cortaría «tú» por la tilde.
        fun words(vararg forms: String) = Regex("""(?<![\p{L}\p{N}_])(${forms.joinToString("|")})(?![\p{L}\p{N}_])""")

        // El criterio es una lista cerrada de palabras que solo pueden nombrar a quien lee o a la app, para que el trato
        // neutro nunca la dispare. Son las mismas listas con que la documentación valida su guía de redacción, más algunas
        // formas, y se probaron contra todos sus textos.
        // Pronombres de tú, vos y usted. «tu» sin tilde es siempre el posesivo, y el té lleva tilde.
        val secondPerson =
            words(
                "tú", "tu", "tus", "te", "ti", "contigo", "tuyo", "tuya", "tuyos", "tuyas", "vos", "usted", "ustedes",
                // Verbos en segunda persona, en cualquier lugar de la frase: ninguno se escribe igual que una palabra que use la
                // app. Por eso no está «estas», que también es un demostrativo, ni «compras», que nombra una pestaña.
                "eres", "estás", "tienes", "puedes", "quieres", "debes", "necesitas", "deseas", "sabes", "has", "vas", "ves",
                "prefieres", "recuerdas", "buscas", "intentas", "pierdes", "vendes", "cobras", "pagas", "usas", "tocas",
                "podrás", "vendiste", "perdiste", "escribiste", "elegiste", "conectaste", "cambiaste", "devolviste",
                "compraste", "registraste",
                // Subjuntivo, el de «no olvides».
                "seas", "estés", "tengas", "puedas", "quieras", "debas", "necesites", "desees", "sepas", "hayas", "vayas",
                "veas", "hagas", "olvides", "pierdas", "borres", "guardes", "cierres", "toques", "abras", "apagues",
                "desinstales",
                // Voseo.
                "sos", "tenés", "podés", "querés", "sabés", "debés", "necesitás", "mirá", "hacé", "agregá", "escribí",
                "revisá", "tocá", "abrí", "cerrá", "buscá",
                // Imperativo con el pronombre pegado.
                "asegúrate", "regístrate", "conéctate", "acuérdate", "fíjate", "inténtalo", "pruébalo", "revísalo", "hazlo",
                "cuéntanos",
            )
        // El imperativo del tuteo se escribe igual que la tercera persona, que el trato neutro sí usa: «la copia se guarda».
        // Por eso solo cuenta con mayúscula al abrir una frase, donde el texto neutro pone el infinitivo: «Crear», no «Crea».
        // En minúscula tras dos puntos sigue una lista, como «Por ejemplo: marca, garantía o talla». Queda fuera «activa»,
        // que también es el adjetivo de «Activa».
        val imperativeForms =
            listOf(
                "crea", "revisa", "agrega", "añade", "toca", "pulsa", "presiona", "intenta", "elige", "escribe", "guarda",
                "abre", "cierra", "busca", "mira", "usa", "selecciona", "ingresa", "desactiva", "confirma", "comprueba",
                "verifica", "recuerda", "olvida", "borra", "elimina", "conecta", "reconecta", "configura", "introduce",
                "comparte", "descarga", "actualiza", "registra", "anota", "cambia", "libera", "instala", "pide", "exporta",
                "restaura", "marca", "lee", "anula", "reintenta", "espera", "consulta", "permite", "desbloquea", "empieza",
                "comienza", "mantén", "evita", "prueba", "vuelve", "sigue", "haz", "pon", "sal", "di", "ten", "ve", "ven",
            )
        val capitalized = imperativeForms.joinToString("|") { it.replaceFirstChar(Char::uppercaseChar) }
        val imperative =
            Regex(
                """(?:^|[.!?¿¡:;]\s*)($capitalized)(?![\p{L}\p{N}_])""",
                RegexOption.MULTILINE,
            )
        // La app no habla de sí misma: «Sin resultados», no «No encontramos».
        val firstPerson =
            words(
                "nosotros", "nuestro", "nuestra", "nuestros", "nuestras", "nos", "me", "pudimos", "podemos", "encontramos",
                "guardamos", "avisamos", "vemos", "paramos", "quitaremos", "tenemos", "conservamos", "recalculamos",
                "corregimos", "subimos", "hemos", "vamos", "volveremos", "llevamos", "seguimos", "logramos", "mostramos",
                "conté", "equivoqué", "devolví",
            )
        // Un adjetivo de saludo supone el género de quien lee. «Bienvenida a la app» sí vale, porque es el sustantivo.
        val gendered = words("bienvenidos?")
        // Regionalismos de la tabla de redacción del mapa, con sus formas: en otro país se dice otra cosa.
        val regionalism =
            words("platas?", "arriendos?", "fiad[oa]s?", "fiar", "celular", "celulares", "ordenador(es)?", "computador(es)?")
        // La libra solo nombra la unidad real, de 453,59237 g, en una clave que lo dice con pound. La de 500 g es regional.
        val pound = words("libras?")
        val poundKey = Regex("""(^|_)pound(_|$)""")
        // En portugués, sin você ni primera persona: «Dados do negócio», nunca «Seus dados».
        val portuguesePerson = words("vocês?", "voces?", "teus?", "tuas?", "nossos?", "nossas?")
        // La app no emite documentos fiscales: estas palabras solo aparecen para negarlo o en el nombre «Pendientes de
        // facturar», y cada clave que las usa está en esta lista cerrada, en cualquier idioma.
        val fiscalDocument = words("factur\\p{L}*", "invoic\\p{L}*", "notas? fisca(?:l|is)")
        val fiscalDocumentKeys =
            setOf(
                "about_not_accounting",
                "catalog_doc_legend",
                "csv_file_sale_fiscal_marks",
                "fiscal_share_note",
                "fiscal_title",
                "hub_tool_fiscal",
                "legal_fiscal_bridge_notice",
                "legal_order_not_invoice",
                "legal_quote_not_invoice",
                "legal_receipt_not_invoice",
                "legal_terms_no_substitute",
                "legal_universal_notice",
                "order_doc_legend",
                "quote_doc_legend",
                "report_doc_legend",
            )
        // Ningún texto cita una autoridad, un documento fiscal de un país ni una ley con número: la app no afirma cumplir ninguna
        // norma, y nombrarlas la ataría a un país. Son nombres que ningún texto neutro necesita.
        val authority =
            words(
                "dian", "sat", "sunat", "afip", "arca", "sii", "sri", "seniat", "aeat", "irs", "hmrc", "receita\\s+federal", "cfdi",
                "nfc-e", "nf-e", "dte", "decreto", "(?:ley|lei|law)\\s+(?:n[º°.]?\\s*)?\\d+",
            )
        // Los encabezados y nombres de archivo del CSV no siguen la regla impersonal: nombran una columna, como «Vuelve al
        // stock». Los regionalismos y los documentos fiscales sí valen para ellos.
        val csvKey = Regex("""^csv_""")

        val valuesDir = Regex("""^values(-[A-Za-z0-9+-]+)?$""")
        val spanishDir = Regex("""^values-(b\+)?es([-+].*)?$""")
        val portugueseDir = Regex("""^values-(b\+)?pt([-+].*)?$""")
        // Una secuencia de escape como \n o \' se lee como espacio: así «\nTe» sigue siendo una palabra suelta.
        val escape = Regex("""\\.""")
        val factory =
            javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                // Un recurso nunca declara DOCTYPE: rechazarlo cierra la puerta a las entidades externas.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }

        val files =
            sourceDirs
                .filter { it.isDirectory }
                .flatMap { src -> src.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList() }
                .filter { valuesDir.matches(it.parentFile.name) && it.parentFile.parentFile?.name == "res" }
        val problems = mutableListOf<String>()
        var texts = 0
        for (file in files) {
            val path = file.relativeTo(root).invariantSeparatorsPath
            val spanish = spanishDir.matches(file.parentFile.name)
            val portuguese = portugueseDir.matches(file.parentFile.name)
            val document = factory.newDocumentBuilder().parse(file)
            val elements =
                listOf("string", "item").flatMap { tag ->
                    val list = document.getElementsByTagName(tag)
                    (0 until list.length).map { list.item(it) as org.w3c.dom.Element }
                }
            for (element in elements) {
                // Un item solo es texto dentro de plurals o string-array, y se nombra por su padre.
                val parent = element.parentNode as? org.w3c.dom.Element
                val name =
                    when {
                        element.tagName == "string" -> element.getAttribute("name")
                        parent != null && parent.tagName in setOf("plurals", "string-array") -> parent.getAttribute("name")
                        else -> continue
                    }
                texts++
                val original = escape.replace(element.textContent, " ")
                val text = original.lowercase()
                val impersonal = !csvKey.containsMatchIn(name)
                val found = mutableListOf<String>()
                fun reject(regex: Regex, reason: String, source: String = text) =
                    regex.findAll(source).forEach { found += "«${it.groupValues[1]}»: $reason" }
                if (name !in fiscalDocumentKeys) {
                    reject(fiscalDocument, "la app no emite documentos fiscales, y la palabra solo va en las claves que lo niegan")
                }
                reject(authority, "cita una autoridad, un documento de un país o una ley, y el texto vale en cualquier país")
                if (spanish && impersonal) {
                    reject(secondPerson, "trata de tú, vos o usted, y el español va en infinitivo o impersonal")
                    reject(imperative, "trata de tú, y una acción va en infinitivo", original)
                    reject(firstPerson, "la app habla en primera persona, y el texto va impersonal")
                    reject(gendered, "supone el género de quien lee, y va un sustantivo")
                }
                if (spanish) {
                    reject(regionalism, "es un regionalismo, y va la palabra de la guía de redacción")
                    if (!poundKey.containsMatchIn(name)) {
                        reject(pound, "la libra solo nombra la unidad real, en una clave con pound")
                    }
                }
                if (portuguese && impersonal) {
                    reject(portuguesePerson, "trata de você o habla en primera persona, y el portugués va impersonal")
                }
                found.forEach { problems += "$path: $name: $it" }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Redacción rota (${problems.size}):\n" + problems.joinToString("\n"))
        }
        logger.lifecycle("verifyWording: $texts textos en ${files.size} archivos revisados, sin problemas")
    }
}

tasks.named("check") { dependsOn(verifyWording) }
