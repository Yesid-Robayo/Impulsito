plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.yesidrobayo.shopledger.data"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // api: :app ve los modelos de :domain a través de :data, y respeta :app -> :data -> :domain.
    api(project(":domain"))
}
