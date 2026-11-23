package dev.plume

import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** The latest GitHub release of this build, with the APK that Plume downloads and the SHA-256 that GitHub gives for it. */
internal class ReleaseUpdate private constructor(
    val versionName: String,
    val apkUrl: String,
    private val sha256: String,
) {
    fun isNewerThan(installedVersionName: String) = compareVersions(versionName, installedVersionName) > 0

    fun matchesApk(apk: InputStream): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = DigestInputStream(apk, digest)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (input.read(buffer) != -1) { /* DigestInputStream hashes the bytes as they are read. */ }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }

    /** One line each for the version, the APK address and its SHA-256, as saved in prefs. */
    fun encode() = "$versionName\n$apkUrl\n$sha256"

    companion object {
        /** The release asset that Plume installs. */
        const val APK_NAME = "Plume-Custom.apk"

        private val versionPattern = Regex("[0-9]+(\\.[0-9]+)+")
        private val checksumPattern = Regex("[0-9a-f]{64}")

        /** Null unless the tag is a version such as v1.7.3, the APK comes from [repo]'s releases and GitHub gave its SHA-256. */
        fun of(repo: String, tag: String, apkUrl: String, digest: String): ReleaseUpdate? {
            val name = tag.removePrefix("v").takeIf(versionPattern::matches) ?: return null
            if (!apkUrl.startsWith("https://github.com/$repo/releases/download/") || !apkUrl.endsWith("/$APK_NAME")) return null
            val checksum = digest.removePrefix("sha256:").takeIf { digest.startsWith("sha256:") && checksumPattern.matches(it) } ?: return null
            return ReleaseUpdate(name, apkUrl, checksum)
        }

        fun decode(repo: String, saved: String): ReleaseUpdate? =
            saved.split('\n').takeIf { it.size == 3 }?.let { (name, url, sha) -> of(repo, "v$name", url, "sha256:$sha") }

        /** Compares 1.7.10 and 1.7.9 by number. A missing part counts as 0, so 1.8 equals 1.8.0. */
        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
