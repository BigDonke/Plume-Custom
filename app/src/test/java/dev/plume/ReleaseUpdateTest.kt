package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdateTest {
    private val sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val manifest = "versionCode=11\nversionName=1.7.2\nsha256=$sha256\n"

    @Test fun selectsUpdatesByAndroidVersionCode() {
        val release = requireNotNull(ReleaseUpdate.parse(manifest))
        assertEquals("1.7.2", release.versionName)
        assertTrue(release.isNewerThan(10))
        assertFalse(release.isNewerThan(11))
        assertFalse(release.isNewerThan(12))
    }

    @Test fun rejectsInvalidOrIncompleteManifests() {
        listOf(
            "versionCode=11\nversionName=1.7.2\n",
            manifest.replace("versionCode=11", "versionCode=0"),
            manifest.replace("versionName=1.7.2", "versionName="),
            manifest.replace(sha256, "bad-checksum"),
            "versionCode=\\u123",
        ).forEach { assertNull(ReleaseUpdate.parse(it)) }
    }

    @Test fun usesVersionedAssetAndChecksItsBytes() {
        val release = requireNotNull(ReleaseUpdate.parse(manifest))
        assertEquals("https://updates.example.org/plume/Plume-Custom-11.apk",
            release.apkUrl("https://updates.example.org/plume/latest.properties"))
        assertTrue(release.matchesApk("abc".byteInputStream()))
        assertFalse(release.matchesApk("abcd".byteInputStream()))
    }
}
