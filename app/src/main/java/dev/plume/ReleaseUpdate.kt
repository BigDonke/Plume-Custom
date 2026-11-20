package dev.plume

import java.io.InputStream
import java.io.StringReader
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties

internal class ReleaseUpdate private constructor(
    val versionCode: Int,
    val versionName: String,
    private val sha256: String,
) {
    fun isNewerThan(installedVersionCode: Int) = versionCode > installedVersionCode

    fun apkUrl(feedUrl: String) = "${feedUrl.substringBeforeLast('/')}/Plume-Custom-$versionCode.apk"

    fun matchesApk(apk: InputStream): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = DigestInputStream(apk, digest)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (input.read(buffer) != -1) { /* DigestInputStream hashes the bytes as they are read. */ }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }

    companion object {
        private val versionPattern = Regex("[0-9]+(\\.[0-9]+)+")
        private val checksumPattern = Regex("[0-9a-f]{64}")

        fun parse(manifest: String): ReleaseUpdate? {
            val fields = Properties()
            try {
                fields.load(StringReader(manifest))
            } catch (_: IllegalArgumentException) {
                return null
            }
            val code = fields.getProperty("versionCode")?.toIntOrNull()?.takeIf { it in 1..2_100_000_000 } ?: return null
            val name = fields.getProperty("versionName")?.takeIf(versionPattern::matches) ?: return null
            val checksum = fields.getProperty("sha256")?.takeIf(checksumPattern::matches) ?: return null
            return ReleaseUpdate(code, name, checksum)
        }
    }
}
