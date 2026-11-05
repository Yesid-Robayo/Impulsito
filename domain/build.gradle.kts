import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :domain es Kotlin puro: nada de Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Mide la cobertura de las pruebas en JVM y hace cumplir sus metas dentro de check.
    alias(libs.plugins.kover)
    // Accesorios de prueba que comparten :domain y :app, como la matriz de configuraciones regionales. Es un plugin de
    // Gradle, sin biblioteca nueva, y nada de él entra en el APK.
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // Un método con cuerpo en una interfaz se compila solo dentro de ella, sin la clase DefaultImpls que Kotlin
        // agrega para módulos compilados con un Kotlin anterior a 2.2. Aquí no hay ninguno: :data y :app compilan con
        // el mismo Kotlin. Esa clase nunca se ejecuta, y en el informe bajaría la cobertura de líneas sin que falte
        // ninguna prueba.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

dependencies {
    // api: los modelos públicos de :domain exponen tipos de fecha, y :data y :app los reciben.
    api(libs.kotlinx.datetime)
    // api: los puertos de :domain devuelven Flow, y :data los implementa.
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    // runTest y dispatchers de prueba para los casos de uso suspend, sin esperas reales.
    testImplementation(libs.kotlinx.coroutines.test)
    // La regla que fija la zona y el idioma de la JVM es de JUnit.
    testFixturesImplementation(libs.junit)
}

// Metas de cobertura de la estrategia de pruebas, exigidas en check. Una regla de Kover no tiene filtros propios: los
// filtros son del informe. Por eso cada meta con su propio conjunto de paquetes vive en una variante del informe, que reusa
// las mismas pruebas. Un filtro de paquete incluye sus subpaquetes, y un paquete que todavía no existe deja la variante
// vacía: su regla pasa sin nada que medir.
val domainPackage = "io.github.yesidrobayo.shopledger.domain"
// Kover llama jvm a las clases y pruebas de un módulo Kotlin JVM.
val jvmVariant = "jvm"
val rulesAndModels = "rulesAndModels"
val useCases = "useCases"
kover {
    currentProject {
        createVariant(rulesAndModels) { add(jvmVariant) }
        createVariant(useCases) { add(jvmVariant) }
    }
    reports {
        // El informe total cubre todo :domain y no exige nada: es el que se abre para ver qué falta.
        variant(rulesAndModels) {
            filters {
                includes { packages("$domainPackage.rule", "$domainPackage.model") }
            }
            verify {
                // check falla si la cobertura queda por debajo de la meta.
                onCheck = true
                // Una sola cifra para todo el conjunto, no una por clase: una clase sin ramas no tiene porcentaje.
                // Con el 100 %, que el conjunto llegue equivale a que llegue cada rama de cada clase.
                rule("Reglas y modelos de :domain: 100 % de ramas") {
                    bound {
                        coverageUnits = CoverageUnit.BRANCH
                        minValue = 100
                    }
                }
            }
        }
        variant(useCases) {
            filters {
                includes { packages("$domainPackage.usecase") }
            }
            verify {
                onCheck = true
                rule("Casos de uso de :domain: 90 % de líneas") {
                    bound {
                        coverageUnits = CoverageUnit.LINE
                        minValue = 90
                    }
                }
            }
        }
    }
}
