import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Las claves de la navegación se guardan serializadas: así la pila sobrevive a que Android cierre el proceso.
    alias(libs.plugins.kotlin.serialization)
}

// Firma de release. keystore.properties, ignorado por Git, solo dice dónde está la clave y su alias. La contraseña nunca
// se guarda en disco: llega en una variable de entorno de la sesión que compila. Sin ella, release sale sin firmar, y debug
// compila en cualquier equipo sin secretos.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
    }
val keystorePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull

// Gradle entrega el estado de la caché de configuración como un servicio inyectable.
abstract class BuildFeaturesAccess
@Inject
constructor(
    val features: org.gradle.api.configuration.BuildFeatures,
)
val configurationCacheRequested =
    objects.newInstance(BuildFeaturesAccess::class.java).features.configurationCache.requested.get()
if (keystorePassword != null && configurationCacheRequested) {
    // La caché de configuración guardaría la contraseña en disco, dentro de su entrada.
    throw GradleException("Para firmar release, compila con --no-configuration-cache.")
}

android {
    namespace = "io.github.yesidrobayo.shopledger"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.yesidrobayo.shopledger"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // Declara al sistema los idiomas de las carpetas values-*, con el inglés de values/ como respaldo que fija
        // resources.properties: agregar un idioma es agregar una carpeta, sin tocar código. El selector lee esta lista.
        generateLocaleConfig = true
    }

    lint {
        // Una clave sin traducir saldría en inglés sin aviso a quien usa la app en otro idioma: check falla.
        error += "MissingTranslation"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists() && keystorePassword != null) {
            create("release") {
                storeFile =
                    file(
                        requireNotNull(keystoreProperties.getProperty("storeFile")) {
                            "Falta storeFile en keystore.properties"
                        },
                    )
                keyAlias =
                    requireNotNull(keystoreProperties.getProperty("keyAlias")) {
                        "Falta keyAlias en keystore.properties"
                    }
                // Almacén y clave usan la misma contraseña.
                storePassword = keystorePassword
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            // El sufijo deja instalar debug junto a la app real, sin tocar su applicationId, que nunca cambia.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Inglés estirado y con acentos (en-XA) y árabe de derecha a izquierda (ar-XB): un texto escrito en el código
            // queda sin transformar, y uno que se corta o se desborda se ve. Solo en debug: la app real no los ofrece.
            isPseudoLocalesEnabled = true
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // Sin packageScope: con él, R8 solo optimiza esos paquetes, y los registros de la app sobreviven en release.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":data"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    // El idioma por app en Android 8 a 12 solo funciona con una AppCompatActivity.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Las pantallas leen su estado con collectAsStateWithLifecycle y obtienen su ViewModel desde Compose. Ya llegaban
    // con la navegación: aquí solo se declaran, porque el código las usa directamente.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // El ciclo de vida del proceso: el respaldo sale al pasar la app a segundo plano y se revisa al abrirla.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // Un ViewModel por entrada de la pila, que se limpia al salir de ella.
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    // La app configura la biblioteca de trabajos con la fábrica de la capa de datos.
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    // Los ViewModel se prueban en JVM: dispatchers de prueba en lugar del principal, y Turbine para leer cada estado.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // La matriz de configuraciones regionales, la misma que corre :domain, para los ViewModel y las pruebas en el dispositivo.
    testImplementation(testFixtures(project(":domain")))
    androidTestImplementation(testFixtures(project(":domain")))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Las pruebas de Compose esperan con Espresso, y la versión que trae Compose falla desde Android 37: busca por reflexión un
    // método que ya no existe. Esta es la de la misma tanda que el runner. Solo pruebas: no entra en ningún APK de la app.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Un trabajo de prueba, como los de la capa de datos, comprueba que recibe sus textos por el puerto y en el idioma
    // elegido.
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Permisos que el manifiesto fusionado nunca lleva, aunque los pida una biblioteca. La biblioteca de trabajos pide el de servicio
// en primer plano, y la app lo quita en su manifiesto: ningún trabajo pasa a primer plano. Si vuelve, por una versión nueva de una
// biblioteca o porque alguien borró la línea que lo quita, check falla en cada variante.
abstract class VerifyMergedPermissions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    /** El comienzo del nombre de cada permiso prohibido: también cubre sus variantes por tipo de servicio. */
    @get:Input
    abstract val forbiddenPrefixes: ListProperty<String>

    @TaskAction
    fun verify() {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val parser =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    isNamespaceAware = true
                    // El manifiesto no declara entidades externas: nada de afuera entra al leerlo.
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }.newDocumentBuilder()
        val document = parser.parse(mergedManifest.get().asFile)
        val requested = mutableListOf<String>()
        for (tag in listOf("uses-permission", "uses-permission-sdk-23")) {
            val nodes = document.getElementsByTagName(tag)
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as org.w3c.dom.Element
                requested += element.getAttributeNS(androidNamespace, "name")
            }
        }
        val found = requested.filter { name -> forbiddenPrefixes.get().any { name.startsWith(it) } }
        if (found.isNotEmpty()) {
            throw GradleException(
                "El manifiesto fusionado pide permisos prohibidos: ${found.joinToString()}",
            )
        }
        logger.lifecycle("$name: ${requested.size} permisos, ninguno prohibido")
    }
}

// El arnés del respaldo vive solo en el código de debug: sus clases no compilan en release, y su actividad solo la declara el
// manifiesto de debug. Si alguien la mueve a main, o un manifiesto la vuelve a declarar, check falla en release.
abstract class VerifyNoDebugHarness : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    /** El paquete del arnés, tal como empieza el nombre de sus componentes. */
    @get:Input
    abstract val harnessPackage: Property<String>

    @TaskAction
    fun verify() {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val parser =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    isNamespaceAware = true
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }.newDocumentBuilder()
        val document = parser.parse(mergedManifest.get().asFile)
        val components = mutableListOf<String>()
        for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
            val nodes = document.getElementsByTagName(tag)
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as org.w3c.dom.Element
                components += element.getAttributeNS(androidNamespace, "name")
            }
        }
        val found = components.filter { it.startsWith(harnessPackage.get()) }
        if (found.isNotEmpty()) {
            throw GradleException(
                "El manifiesto de release declara el arnés de depuración: ${found.joinToString()}",
            )
        }
        logger.lifecycle("$name: ${components.size} componentes, ninguno del arnés")
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val capitalized = variant.name.replaceFirstChar(Char::uppercaseChar)
        val verify =
            tasks.register<VerifyNoDebugHarness>("verify${capitalized}HasNoDebugHarness") {
                group = "verification"
                description =
                    "Comprueba que ${variant.name} no declare el arnés de depuración del respaldo."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                harnessPackage.set("io.github.yesidrobayo.shopledger.harness.")
            }
        tasks.named("check") { dependsOn(verify) }
    }
    onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar(Char::uppercaseChar)
        val verify =
            tasks.register<VerifyMergedPermissions>("verify${capitalized}MergedPermissions") {
                group = "verification"
                description =
                    "Comprueba que el manifiesto fusionado de ${variant.name} no pida permisos prohibidos."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                forbiddenPrefixes.set(listOf("android.permission.FOREGROUND_SERVICE"))
            }
        tasks.named("check") { dependsOn(verify) }
    }
}
