import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.library)
    // Room 3 solo genera código con KSP.
    alias(libs.plugins.ksp)
    // Exporta el esquema de cada versión a un JSON versionado: sin él no se prueba una migración.
    alias(libs.plugins.room3)
}

android {
    namespace = "io.github.yesidrobayo.shopledger.data"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Las pruebas de la base corren en el dispositivo, y Kover solo mide pruebas en JVM:
            // aquí mide AGP, con JaCoCo. Solo se instrumenta el APK de pruebas de este módulo, en
            // debug. Ni la app ni release llevan nada de JaCoCo.
            enableAndroidTestCoverage = true
        }
    }

    testCoverage {
        // La versión sale del catálogo, no de la que AGP trae por defecto.
        jacocoVersion = libs.versions.jacoco.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // El destino falso de las copias y el servidor WebDAV falso sirven a las pruebas en la JVM y
        // a las del dispositivo: viven una sola vez, en una carpeta que ven las dos.
        getByName("test").kotlin.directories += "src/sharedTest/java"
        getByName("androidTest").kotlin.directories += "src/sharedTest/java"
    }
}

room3 {
    // Un JSON por versión del esquema. Se versionan y nunca se editan una vez publicados.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // api: :app ve los modelos de :domain a través de :data, y respeta :app -> :data -> :domain.
    api(project(":domain"))
    // Room 3 sobre el SQLite empaquetado: la misma versión de SQLite en todas las versiones de
    // Android que se soportan.
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room3.compiler)
    // El cliente HTTP del destino WebDAV: la plataforma no admite PROPFIND, MKCOL ni MOVE. Solo vive
    // en este módulo, y no llega a :app.
    implementation(libs.okhttp)
    // Los trabajos en segundo plano, como el respaldo, que siguen aunque la app esté cerrada.
    implementation(libs.androidx.work.runtime)
    // El cifrado en flujo de las copias, solo con AesGcmHkdfStreaming: la clave sale del código de
    // recuperación, sin KeysetManager ni Keystore. Solo el paquete crypto de esta capa la usa, y
    // tras R8 quedan unas pocas clases, sin gson ni protobuf.
    implementation(libs.google.tink.android)

    // Las pruebas en la JVM comparan el catálogo del esquema con sus fuentes y lo generan, y prueban
    // lo del destino de copias que no necesita el analizador de XML del sistema.
    testImplementation(libs.junit)

    // La base se prueba en el dispositivo, con el mismo driver que la app.
    androidTestImplementation(libs.androidx.room3.testing)
    // La plantilla de migraciones usa una base ficticia propia de las pruebas, con varias versiones:
    // el mismo compilador de Room la genera, y su esquema de cada versión queda exportado.
    kspAndroidTest(libs.androidx.room3.compiler)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Las lecturas de los repositorios devuelven Flow: Turbine espera cada emisión con un tope de
    // tiempo, sin colgar la prueba si no llega.
    androidTestImplementation(libs.turbine)
    // El trabajo de respaldo corre en las pruebas con su fábrica, sin programarlo.
    androidTestImplementation(libs.androidx.work.testing)
    // El generador de datos de prueba corre con cada fila de la matriz de configuraciones
    // regionales, que vive en los accesorios de prueba de :domain. Solo entra en el APK de pruebas.
    androidTestImplementation(testFixtures(project(":domain")))
}

// El catálogo del esquema, la lista permitida con que se revisa una copia antes de confiar en ella,
// sale de dos fuentes: el JSON que Room exporta de cada versión, solo el de AppDatabase y nunca el de
// la base ficticia de las pruebas de migración, y SchemaGuards. Las pruebas en la JVM lo comparan con
// ellas, y fallan si alguien cambió el esquema sin regenerarlo.
val appDatabaseSchemas =
    layout.projectDirectory.dir("schemas/io.github.yesidrobayo.shopledger.data.local.AppDatabase")
val schemaCatalogSources =
    layout.projectDirectory.dir("src/main/java/io/github/yesidrobayo/shopledger/data/local/guard")
tasks.withType<Test>().configureEach {
    systemProperty("schemaCatalog.schemaDir", appDatabaseSchemas.asFile.absolutePath)
    systemProperty("schemaCatalog.sourceDir", schemaCatalogSources.asFile.absolutePath)
    // Las dos muertes seguidas del reemplazo en todos sus escenarios: -PswapFull=true. Tarda
    // minutos, así que check corre solo los que cambian el camino de la recuperación.
    systemProperty("swap.full", providers.gradleProperty("swapFull").getOrElse("false"))
    // Los vectores públicos del formato de las copias cifradas, los mismos que usa la herramienta
    // de rescate en el PC: el lector y el escritor de la app se prueban contra ellos.
    val backupFormatVectors =
        rootProject.layout.projectDirectory.file(
            "tools/backup-format/vectors/format-v2-vectors.json",
        )
    systemProperty("backupFormat.vectors", backupFormatVectors.asFile.absolutePath)
    inputs
        .files(backupFormatVectors)
        .withPropertyName("backupFormatVectors")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // El JSON también se lee como archivo: si cambia, las pruebas vuelven a correr.
    inputs
        .dir(appDatabaseSchemas)
        .withPropertyName("appDatabaseSchemas")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Un solo comando regenera el catálogo: .\gradlew.bat :data:generateSchemaCatalog. Corre el generador
// de las pruebas en la JVM, con las clases de :data tal como las compila Gradle, porque las guardas
// viven en código. Escribe el archivo de la versión actual y la lista de versiones. El de una versión
// anterior queda congelado, como su JSON.
tasks.register<Test>("generateSchemaCatalog") {
    group = "build"
    description = "Regenera SchemaCatalog desde el JSON que exporta Room y desde SchemaGuards."
    // AGP registra las pruebas en la JVM después de este archivo: se toman al configurar esta
    // tarea. Se usan sus clases y su classpath, que dependen de compilar, y no la tarea misma, que
    // fallaría justo porque el catálogo no coincide.
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    useJUnit()
    filter { includeTestsMatching("*.SchemaCatalogRegeneration") }
    systemProperty("schemaCatalog.write", "true")
    testLogging { showStandardStreams = true }
    // Siempre corre: lo que escribe vive en el código fuente, no en una salida de Gradle.
    outputs.upToDateWhen { false }
}

// Meta de líneas de :data, medida con lo que recorrieron las pruebas en el dispositivo. No va en
// check, que corre sin dispositivo: se exige al terminar connectedDebugAndroidTest, sobre el informe
// que AGP arma con las pruebas de todos los dispositivos conectados.
val verifyDeviceCoverage =
    tasks.register("verifyDeviceCoverage") {
        // Se resuelven al configurar: la acción solo recibe números, textos y proveedores, y así
        // sirve con la caché de configuración.
        val minimumPercent = 70L
        group = "verification"
        description =
            "Exige el $minimumPercent % de líneas de :data en las pruebas del dispositivo."

        // AGP registra la tarea del informe después de este archivo: se nombra, sin buscarla.
        dependsOn("createDebugAndroidTestCoverageReport")
        val report =
            layout.buildDirectory.file("reports/coverage/androidTest/debug/connected/report.xml")
        inputs.files(report)
        // Lo que generan Room y KSP no se cuenta: nadie lo escribe, y lo prueba Room.
        val generatedSources = layout.buildDirectory.dir("generated/ksp/debug")
        inputs.files(generatedSources)
        // Respaldo, trabajos y migraciones tienen su propia meta, el 100 % de los casos de sus tablas
        // de pruebas: sus líneas se informan aparte y no cuentan para esta meta.
        val listedPackages =
            listOf("backup", "work", "local/migration")
                .map { "io/github/yesidrobayo/shopledger/data/$it" }
        // Con un filtro de pruebas, por ejemplo al correr una sola clase desde Android Studio, el
        // informe solo ve una parte: la meta no se exige.
        val runnerArgument = "android.testInstrumentationRunnerArguments."
        val filterArguments =
            setOf(
                "class",
                "notClass",
                "package",
                "notPackage",
                "annotation",
                "notAnnotation",
                "size",
                "tests_regex",
                "shardIndex",
            )
        val filtered =
            providers.gradlePropertiesPrefixedBy(runnerArgument).map { arguments ->
                arguments.keys.any { it.removePrefix(runnerArgument) in filterArguments }
            }
        onlyIf("Sin filtro de pruebas: con uno, el informe solo ve una parte") { !filtered.get() }

        doLast {
            val xml = report.get().asFile
            if (!xml.isFile) throw GradleException("No está el informe de cobertura: $xml")
            val parser =
                DocumentBuilderFactory
                    .newInstance()
                    .apply {
                        // El informe declara un DTD que no viene con él: no se carga, y nada
                        // externo entra al leerlo.
                        setFeature(
                            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                            false,
                        )
                        setFeature("http://xml.org/sax/features/external-general-entities", false)
                        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    }.newDocumentBuilder()

            // Ruta de cada archivo generado, relativa a su raíz: la misma que da el informe con el
            // paquete y el nombre del archivo.
            val generatedRoot = generatedSources.get().asFile
            val generated =
                listOf("kotlin", "java")
                    .map { generatedRoot.resolve(it) }
                    .filter { it.isDirectory }
                    .flatMap { root ->
                        root
                            .walkTopDown()
                            .filter { it.isFile }
                            .map { it.relativeTo(root).invariantSeparatorsPath }
                            .toList()
                    }.toSet()

            fun Element.children(tag: String): List<Element> = (0 until childNodes.length)
                .map { childNodes.item(it) }
                .filterIsInstance<Element>()
                .filter { it.tagName == tag }

            fun percent(part: Long, whole: Long): String {
                // Se trunca a una décima: nunca muestra la meta cumplida si no lo está.
                val tenths = part * 1000 / whole
                return "${tenths / 10},${tenths % 10} %"
            }

            var covered = 0L
            var missed = 0L
            var listedCovered = 0L
            var listedMissed = 0L
            var generatedFiles = 0
            for (pkg in parser.parse(xml).documentElement.children("package")) {
                val name = pkg.getAttribute("name")
                val listed = listedPackages.any { name == it || name.startsWith("$it/") }
                // Se cuenta por archivo, como el total de JaCoCo: una línea de Kotlin puede
                // compilar en varias clases.
                for (source in pkg.children("sourcefile")) {
                    if ("$name/${source.getAttribute("name")}" in generated) {
                        generatedFiles++
                        continue
                    }
                    // JaCoCo no escribe el contador de un archivo sin líneas.
                    val lines =
                        source.children("counter").find { it.getAttribute("type") == "LINE" }
                    if (lines == null) continue
                    val sourceCovered = lines.getAttribute("covered").toLong()
                    val sourceMissed = lines.getAttribute("missed").toLong()
                    if (listed) {
                        listedCovered += sourceCovered
                        listedMissed += sourceMissed
                    } else {
                        covered += sourceCovered
                        missed += sourceMissed
                    }
                }
            }

            // :data siempre tiene la base de Room. Si su código generado no aparece, KSP cambió de
            // carpeta y se estaría contando como propio.
            if (generatedFiles == 0) {
                throw GradleException(
                    "El informe no trae el código que genera Room: revisa $generatedRoot",
                )
            }
            val total = covered + missed
            if (total == 0L) throw GradleException("El informe no trae líneas de :data")
            val listedTotal = listedCovered + listedMissed
            if (listedTotal > 0L) {
                val listedPercent = percent(listedCovered, listedTotal)
                logger.lifecycle(
                    "Respaldo, trabajos y migraciones, que se miden por su lista de casos: " +
                        "$listedPercent ($listedCovered de $listedTotal líneas)",
                )
            }
            val result =
                "Cobertura de líneas de :data en el dispositivo: ${percent(covered, total)} " +
                    "($covered de $total). La meta es $minimumPercent %"
            if (covered * 100 < minimumPercent * total) throw GradleException(result)
            logger.lifecycle(result)
        }
    }

// Así la meta se exige con el mismo comando que ya corre las pruebas del dispositivo. Si las pruebas
// fallan, no hay informe ni verificación: manda el fallo de las pruebas.
tasks
    .named { it == "connectedDebugAndroidTest" }
    .configureEach { finalizedBy(verifyDeviceCoverage) }
