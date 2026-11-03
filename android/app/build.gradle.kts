import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val queryLocalSettings = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun queryConfigLiteral(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    namespace = "com.personal.tpm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.personal.tpm"
        minSdk = 29            // Android 10
        targetSdk = 35
        versionCode = 123
        versionName = "2.1.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_QUERY_USERNAME", queryConfigLiteral("C0382522"))
        buildConfigField("String", "DEFAULT_QUERY_PASSWORD", queryConfigLiteral(queryLocalSettings.getProperty("tpm.defaultQueryPassword", "")))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signing: create your own keystore, keep it out of the repository, and fill it in through
            // gradle.properties on this machine (never commit it). Without it the release APK is unsigned.
            val storeFile = providers.gradleProperty("TPM_STORE_FILE").orNull
            if (storeFile != null) {
                signingConfig = signingConfigs.create("release") {
                    this.storeFile = file(storeFile)
                    storePassword = providers.gradleProperty("TPM_STORE_PASSWORD").get()
                    keyAlias = providers.gradleProperty("TPM_KEY_ALIAS").get()
                    keyPassword = providers.gradleProperty("TPM_KEY_PASSWORD").get()
                }
            } else {
                // No keystore on this machine: sign the release build with the debug key, so it can still be installed and tried
                // (a release build is much smoother than a debug one). Not for handing out: make a real keystore for that.
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// Every APK contains the available release history. A new version cannot build without its own notes.
val releaseVersion = android.defaultConfig.versionName!!
val releaseNotes = rootProject.layout.projectDirectory.dir("releases")
val generatedReleaseAssets = layout.buildDirectory.dir("generated/releaseHistory")
val validateReleaseNotes by tasks.registering {
    inputs.property("releaseVersion", releaseVersion)
    // A separate task still runs when the entire releases directory is empty (Sync can be NO-SOURCE).
    doLast {
        val current = releaseNotes.file("$releaseVersion-notes.txt").asFile
        check(current.isFile) { "缺少当前版本日志：${current.name}，请先记录本版本功能变更" }
        val lines = current.readText(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
        check(lines.size >= 2 && lines.first() == "$releaseVersion 更新日志" && lines.drop(1).any { it.matches(Regex("\\d+\\.\\s+\\S.*")) }) {
            "${current.name} 必须包含版本标题和至少一条编号功能变更，不能只写空标题"
        }
    }
}
val bundleReleaseNotes by tasks.registering(Sync::class) {
    dependsOn(validateReleaseNotes)
    from(releaseNotes) { include("*-notes.txt"); into("release-notes") }
    into(generatedReleaseAssets)
}
android.sourceSets.getByName("main").assets.srcDir(generatedReleaseAssets)
tasks.named("preBuild") { dependsOn(bundleReleaseNotes) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.11.00")
    implementation(composeBom)
    implementation("io.github.kyant0:backdrop:1.0.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    // Keep the Activity Result compatible FragmentActivity used by the application.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // The real org.json for the unit tests: the one in the Android jar is only a stub there.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
