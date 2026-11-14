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

// Keystore release (o CI a gera/povê antes do build; ver .github/workflows/build.yml)
val keystoreFile = file("keystore/filemaster.keystore")

android {
    namespace = "com.filemaster"
    compileSdk = 35

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KS_STORE_PASSWORD") ?: "filemaster"
                keyAlias = System.getenv("KS_KEY_ALIAS") ?: "filemaster"
                keyPassword = System.getenv("KS_KEY_PASSWORD") ?: "filemaster"
            }
        }
    }

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

            // Assinatura release: ativada somente quando a keystore existe
            // (gerada pelo CI em app/keystore/). Em builds locais sem a
            // keystore, sai app-release-unsigned.apk — nada quebra.
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
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
// Tasks Rust (cargo direto + linkers do NDK — sem depender do cargo-ndk)
//
// O cargo-ndk foi removido do pipeline: sua CLI mudou entre versões e é um
// ponto único de falha. Aqui usamos `cargo build --target <triple>` com os
// linkers clang do próprio NDK via variáveis de ambiente do Cargo —
// comportamento idêntico ao cargo-ndk, sem dependência extra.
// -------------------------------------------------------------------------

/** Mapeamento triple Rust -> ABI do Android (usado também para o jniLibs). */
val rustTargets = mapOf(
    "aarch64-linux-android" to "arm64-v8a",
    "armv7-linux-androideabi" to "armeabi-v7a",
    "x86_64-linux-android" to "x86_64",
)

val ndkHome: String = System.getenv("ANDROID_NDK_HOME")
    ?: System.getenv("NDK_HOME")
    ?: throw GradleException(
        "ANDROID_NDK_HOME não definido. Aponte para o NDK (ex.: ~/Android/Sdk/ndk/27.0.12077973)."
    )

// A estrutura do NDK difere por SO do host: linux-x86_64 / macosx-x86_64 / windows-x86_64
val ndkHostTag = when {
    System.getProperty("os.name").startsWith("Mac") -> "macosx-x86_64"
    System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
    else -> "linux-x86_64"
}
val ndkBin = "$ndkHome/toolchains/llvm/prebuilt/$ndkHostTag/bin"

/** API level usado nos binários do toolchain do NDK (>= minSdk 26). */
val ANDROID_API_LEVEL = 26

fun buildRustTask(buildType: String) = tasks.register<Exec>("buildRust${buildType.replaceFirstChar { it.uppercase() }}") {
    workingDir = rustProjectDir
    // O Cargo resolve os linkers por variáveis de ambiente — sem precisar
    // de .cargo/config.toml (mantém o projeto independente do caminho do NDK).
    environment(
        mapOf(
            "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" to "$ndkBin/aarch64-linux-android$ANDROID_API_LEVEL-clang",
            "CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER" to "$ndkBin/armv7a-linux-androideabi$ANDROID_API_LEVEL-clang",
            "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER" to "$ndkBin/x86_64-linux-android$ANDROID_API_LEVEL-clang",
            "CARGO_TARGET_DIR" to file("$rootDir/rust/core/target").absolutePath,
        )
    )
    val profileArgs = if (buildType == "release") "--release" else ""
    commandLine(
        "bash", "-c",
        """
        set -euo pipefail
        for triple in ${rustTargets.keys.joinToString(" ")}; do
          cargo build $profileArgs --target ${'$'}triple
        done
        """.trimIndent()
    )
    // Copia os .so para o layout jniLibs/<abi>/ esperado pelo AGP
    doLast {
        rustTargets.forEach { (triple, abi) ->
            project.copy {
                from(file("$rootDir/rust/core/target/$triple/$buildType/libfilemaster.so"))
                into(file("src/main/jniLibs/$abi"))
            }
        }
    }
}

fun hostBuild(buildType: String) = tasks.register<Exec>("buildRustHost${buildType.replaceFirstChar { it.uppercase() }}") {
    workingDir = rustProjectDir
    commandLine("cargo", "build")
    environment("CARGO_TARGET_DIR", file("$rootDir/rust/core/target").absolutePath)
}

// Registro efetivo das tasks (a função acima só define; aqui invocamos):
//   buildRustDebug   -> exigida pelo preBuild (assembleDebug)
//   buildRustRelease -> exigida pelo assembleRelease
buildRustTask("debug")
buildRustTask("release")

// O host build (para gerar bindings a partir do cdylib de host) é registrado
// fora de qualquer bloco de configuração — o Gradle não permite registrar
// tasks dentro da realização de outra task.
val buildRustHostDebug = hostBuild("debug")

tasks.register<Exec>("generateUniFFIBindings") {
    dependsOn(buildRustHostDebug)
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
    // Cada variante empacota as libs do seu próprio perfil Rust:
    //   preDebugBuild   -> .so de debug (rápido de compilar, com símbolos)
    //   preReleaseBuild -> .so de release (LTO + strip, ~7x menor)
    // (usar preBuild genérico aqui fazia o APK release incluir libs debug)
    tasks.named("preDebugBuild") {
        dependsOn("buildRustDebug", "generateUniFFIBindings")
    }
    tasks.named("preReleaseBuild") {
        dependsOn("buildRustRelease", "generateUniFFIBindings")
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
