package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdateTest {
    private val repo = "BigDonke/Plume-Custom"
    private val sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val url = "https://github.com/$repo/releases/download/v1.7.3/Plume-Custom.apk"

    @Test fun comparesVersionsByNumber() {
        val release = requireNotNull(ReleaseUpdate.of(repo, "v1.7.10", url, "sha256:$sha256"))
        assertEquals("1.7.10", release.versionName)
        assertTrue(release.isNewerThan("1.7.9"))
        assertTrue(release.isNewerThan("1.7"))
        assertFalse(release.isNewerThan("1.7.10"))
        assertFalse(release.isNewerThan("1.8"))
        assertEquals(0, ReleaseUpdate.compareVersions("1.8", "1.8.0"))
    }

    @Test fun rejectsReleasesItCannotTrust() {
        assertNull(ReleaseUpdate.of(repo, "latest", url, "sha256:$sha256"))
        assertNull(ReleaseUpdate.of(repo, "v1.7.3", url, ""))
        assertNull(ReleaseUpdate.of(repo, "v1.7.3", url, "sha256:bad-checksum"))
        assertNull(ReleaseUpdate.of(repo, "v1.7.3", url.replace(repo, "someone/else"), "sha256:$sha256"))
        assertNull(ReleaseUpdate.of(repo, "v1.7.3", url.replace("https://", "http://"), "sha256:$sha256"))
        assertNull(ReleaseUpdate.of(repo, "v1.7.3", url.replace("Plume-Custom.apk", "other.apk"), "sha256:$sha256"))
    }

    @Test fun savesAndChecksTheApk() {
        val release = requireNotNull(ReleaseUpdate.of(repo, "v1.7.3", url, "sha256:$sha256"))
        val saved = requireNotNull(ReleaseUpdate.decode(repo, release.encode()))
        assertEquals("1.7.3", saved.versionName)
        assertEquals(url, saved.apkUrl)
        assertNull(ReleaseUpdate.decode("someone/else", release.encode()))
        assertTrue(saved.matchesApk("abc".byteInputStream()))
        assertFalse(saved.matchesApk("abcd".byteInputStream()))
    }
}
