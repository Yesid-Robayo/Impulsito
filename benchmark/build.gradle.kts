// Las mediciones de rendimiento con Macrobenchmark: el arranque en frío hasta Inicio con cifras y
// la fluidez del historial de ventas. Es un módulo de pruebas aparte, que instala y mide la app
// desde fuera: ninguna de sus clases ni de sus dependencias entra en el APK de la app.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "io.github.yesidrobayo.shopledger.benchmark"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        targetSdk = 37

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Mide el tipo benchmark de la app, que es release firmada con la clave de debug. Las
        // pruebas se firman con la misma clave, y sin clave de release ni secretos en el equipo.
        create("benchmark") {
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    // Las pruebas corren en su propio proceso y no dentro de la app: así pueden cerrarla y
    // abrirla en frío sin cerrarse con ella.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    // Solo la variante benchmark: medir debug no sirve, porque es depurable y no pasa por R8.
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

dependencies {
    // Macrobenchmark: abre la app, mide el arranque y los cuadros, y genera el perfil de
    // compilación. Trae UiAutomator, con el que las pruebas tocan la app desde fuera.
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
}
