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
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
