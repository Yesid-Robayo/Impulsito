import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :domain es Kotlin puro: nada de Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // api: los modelos públicos de :domain exponen tipos de fecha, y :data y :app los reciben.
    api(libs.kotlinx.datetime)
    testImplementation(libs.junit)
    // runTest y dispatchers de prueba para los casos de uso suspend, sin esperas reales.
    testImplementation(libs.kotlinx.coroutines.test)
}
