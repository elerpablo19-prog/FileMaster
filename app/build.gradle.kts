import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// =========================================================================
// Integração Rust <-> Android
//
// Fluxo de build:
//   1. `buildRustDebug/Release`  -> cargo-ndk compila o core para os 3 ABIs
//      e copia os .so para src/main/jniLibs/<abi>/
//   2. `generateUniFFIBindings`  -> uniffi-bindgen lê os símbolos do cdylib
//      host e gera o Kotlin em src/main/kotlin (pacote com.filemaster.core)
//   3. `preBuild` depende de ambos, então `./gradlew assembleDebug` "simples"
//      funciona end-to-end.
//
// Requisitos no ambiente de build:
//   - cargo + cargo-ndk instalados (cargo install cargo-ndk)
//   - Targets Android instalados (rustup target add aarch64-linux-android ...)
//   - ANDROID_NDK_HOME apontando para o NDK
// =========================================================================

val cargoTargets = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val rustProjectDir = file("$rootDir/rust/core")

android {
    namespace = "com.filemaster"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.filemaster"
        minSdk = 26          // MAES exige 30+, mas o app roda a partir do 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Apenas os ABIs que de fato compilamos
            abiFilters += cargoTargets
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        getByName("main") {
            // Bindings gerados pelo UniFFI vivem junto do código Kotlin
            kotlin.srcDir("src/main/kotlin")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// -------------------------------------------------------------------------
// Tasks Rust
// -------------------------------------------------------------------------

fun cargoTask(buildType: String) = tasks.register<Exec>("buildRust${buildType.replaceFirstChar { it.uppercase() }}") {
    workingDir = rustProjectDir
    val profile = if (buildType == "release") "--release" else ""
    commandLine(
        "cargo", "ndk",
        "--platform", "31",              // compilado contra API 31 (MANAGE_EXTERNAL_STORAGE era comum)
        "--output", file("src/main/jniLibs").absolutePath,
        *cargoTargets.flatMap { listOf("-t", it) }.toTypedArray(),
        "build", *profile.split(" ").filter { it.isNotBlank() }.toTypedArray()
    )
    environment("CARGO_TARGET_DIR", file("$rootDir/rust/core/target").absolutePath)
}

fun hostBuild(buildType: String) = tasks.register<Exec>("buildRustHost${buildType.replaceFirstChar { it.uppercase() }}") {
    workingDir = rustProjectDir
    commandLine("cargo", "build")
    environment("CARGO_TARGET_DIR", file("$rootDir/rust/core/target").absolutePath)
}

tasks.register<Exec>("generateUniFFIBindings") {
    dependsOn(hostBuild("debug"))
    val libFile = file("$rootDir/rust/core/target/debug/libfilemaster.so")
    // Usa o uniffi-bindgen embutido na própria crate (src/bin/uniffi-bindgen.rs),
    // garantindo versionamento idêntico entre crate e bindgen — funciona
    // igualmente em máquina local e no CI.
    commandLine(
        "cargo", "run", "-q", "--bin", "uniffi-bindgen", "--",
        "generate",
        "--library", libFile.absolutePath,
        "--language", "kotlin",
        "--out-dir", file("src/main/kotlin").absolutePath,
        "--config", file("$rootDir/rust/core/uniffi.toml").absolutePath
    )
    workingDir = rustProjectDir
    outputs.dir(file("src/main/kotlin/com/filemaster/core"))
}

tasks.register("cleanRust") {
    doLast {
        delete(file("src/main/jniLibs"))
        delete(file("src/main/kotlin/com/filemaster/core"))
    }
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn("buildRustDebug", "generateUniFFIBindings")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose via BOM — versões gerenciadas centralmente
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    // UniFFI exige JNA; a variante @aar é a empacotada para Android
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
