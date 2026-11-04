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
