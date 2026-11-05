import java.util.Properties
import javax.inject.Inject

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
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // Un ViewModel por entrada de la pila, que se limpia al salir de ella.
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    testImplementation(libs.junit)
    // Los ViewModel se prueban en JVM: dispatchers de prueba en lugar del principal, y Turbine para leer cada estado.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
