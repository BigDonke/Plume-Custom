import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseProperties = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
val updateFeedUrl = providers.gradleProperty("plumeUpdateFeedUrl")
    .orElse(releaseProperties?.getProperty("plumeUpdateFeedUrl").orEmpty())
    .get()
if (updateFeedUrl.isNotEmpty()) {
    val feed = URI(updateFeedUrl)
    require(feed.scheme == "https" && feed.host != null && feed.userInfo == null &&
        feed.query == null && feed.fragment == null && feed.rawPath.endsWith("/latest.properties")) {
        "plumeUpdateFeedUrl must be an HTTPS URL ending in /latest.properties"
    }
}

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
        resValue("string", "update_feed_url", "")
        // What's new: only this version's section of CHANGELOG.md ships, one entry per line, ** marks kept for bold.
        // A ### heading ships as a line that starts with #. A fix release (1.7.1) ships its x.y section after its own,
        // so people who update from 1.6 still see what 1.7 brought.
        val changelog = rootProject.file("CHANGELOG.md").readText().replace("\r", "")
        val section = { v: String -> changelog.substringAfter("\n## $v\n", "").substringBefore("\n## ") }
        val minor = versionName!!.split('.').take(2).joinToString(".")
        val notes = (section(versionName!!) + if (minor != versionName) "\n" + section(minor) else "")
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
    require(updateFeedUrl.isEmpty() || releaseProperties != null) { "Update-enabled builds require keystore.properties" }
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
            resValue("string", "update_feed_url", updateFeedUrl)
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
