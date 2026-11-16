plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val buildLlama = (findProperty("advnotes.llama") as String?)?.toBoolean() ?: false &&
    file("src/main/cpp/llama.cpp/CMakeLists.txt").exists()

android {
    namespace = "com.advnotes.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.advnotes.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        buildConfigField("boolean", "HAS_LLAMA", buildLlama.toString())

        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DADVNOTES_LLAMA=${if (buildLlama) "ON" else "OFF"}",
                    "-DGGML_OPENMP=OFF",
                )
                cppFlags += listOf("-O3")
            }
        }
    }

    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Release key from the environment (set in CI from repository secrets); falls back to the debug key.
    val releaseKeystore = System.getenv("ADVNOTES_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("ADVNOTES_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ADVNOTES_KEY_ALIAS")
            keyPassword = System.getenv("ADVNOTES_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.webkit:webkit:1.12.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.google.android.gms:play-services-auth:21.4.0")
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo1.maven.org/maven2")
}

// Builds the calculator engine for the host so JVM (Robolectric) tests exercise the real C++ code
// through JNI. Skipped quietly if no host C++ compiler is installed.
val hostNativeDir = layout.buildDirectory.dir("host-native")
val buildHostCasio = tasks.register<Exec>("buildHostCasio") {
    val out = hostNativeDir.get().asFile
    val javaHome = System.getProperty("java.home")
    inputs.dir("src/main/cpp/casio/src/core")
    inputs.dir("src/main/cpp/casio_shim")
    inputs.dir("src/main/cpp/casio_bridge")
    outputs.dir(out)
    isIgnoreExitValue = true
    doFirst { out.mkdirs() }
    workingDir = file("src/main/cpp")
    commandLine(
        "sh", "-c",
        "g++ -std=c++20 -O1 -shared -fPIC -I'$javaHome/include' -I'$javaHome/include/linux' " +
            "-Icasio_shim -Icasio/src/core -Icasio_bridge casio/src/core/*.cpp casio_bridge/casio_api.cpp casio_bridge/casio_jni.cpp " +
            "-o '$out/libadvnotes_casio.so' 2>/dev/null || echo 'host calculator build skipped'",
    )
}
tasks.withType<Test>().configureEach {
    dependsOn(buildHostCasio)
    inputs.files(buildHostCasio)
    // A native library can be loaded by one classloader per JVM, and Robolectric uses one per test class.
    forkEvery = 1
    System.getProperty("sdocx")?.let { systemProperty("sdocx", it) }
    systemProperty("java.library.path", hostNativeDir.get().asFile.path)
}
