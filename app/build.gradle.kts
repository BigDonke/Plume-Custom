import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseProperties = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
// Release builds signed with keystore.properties check this repository's GitHub releases for updates. A build signed
// with another key can't install those APKs, so it skips the check. -PplumeUpdateRepo=owner/name points it elsewhere.
val updateRepo = if (releaseProperties == null) "" else providers.gradleProperty("plumeUpdateRepo").orElse("BigDonke/Plume-Custom").get()
require(updateRepo.isEmpty() || Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+").matches(updateRepo)) { "plumeUpdateRepo must look like owner/name" }

// No dependencies on purpose: framework Views only, to keep RAM and APK size minimal.
android {
    namespace = "dev.plume"
    compileSdk = 36

    defaultConfig {
        // Custom build: its own ID so it installs next to the official Plume, which is signed with another key.
        applicationId = "dev.plume.custom"
        manifestPlaceholders["appLabel"] = "Plume Custom"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.7.2"
        testInstrumentationRunner = "dev.plume.ActivityTest"
        resValue("string", "update_repo", "")
        // What's new: only this version's section of CHANGELOG.md ships, one entry per line, ** marks kept for bold.
        // A ### heading ships as a line that starts with #.
        val changelog = rootProject.file("CHANGELOG.md").readText().replace("\r", "")
        val notes = changelog.substringAfter("\n## $versionName\n", "").substringBefore("\n## ")
            .lines().filter { it.startsWith("- ") || it.startsWith("### ") }
            .map { if (it.startsWith("### ")) "#" + it.removePrefix("### ") else it.removePrefix("- ") }
        require(notes.isNotEmpty()) { "CHANGELOG.md has no entries under \"## $versionName\"" }
        resValue("string", "whats_new", notes.joinToString("\\n") { it.replace("'", "\\'").replace("\"", "\\\"") })
    }

    buildFeatures {
        resValues = true
    }

    // CI fails on new lint errors only; the ones already here are listed in lint-baseline.xml
    // (./gradlew updateLintBaseline rewrites it). Translations have their own check, so lag there isn't an error.
    lint {
        baseline = file("lint-baseline.xml")
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }

    // Release key lives outside the repo; keystore.properties (git-ignored) points at it. Without that file
    // (a fresh clone) release builds fall back to the debug key, so anyone can still build and sideload.
    signingConfigs {
        if (releaseProperties != null) create("release") {
            storeFile = file(releaseProperties.getProperty("storeFile"))
            storePassword = releaseProperties.getProperty("storePassword")
            keyAlias = releaseProperties.getProperty("keyAlias")
            keyPassword = releaseProperties.getProperty("keyPassword")
        }
    }

    // Kotlin's own metadata files (reflection and tooling data) aren't used at run time.
    packaging {
        resources {
            excludes += setOf("kotlin/**", "**/*.kotlin_builtins", "META-INF/*.kotlin_module", "META-INF/*.version", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        // Debug builds install as a third app next to the official and the custom one, so they can be tested with run-as.
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Plume Debug"
        }
        release {
            resValue("string", "update_repo", updateRepo)
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
