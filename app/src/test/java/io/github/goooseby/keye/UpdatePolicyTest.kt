package io.github.goooseby.keye

import io.github.goooseby.keye.update.StableVersion
import io.github.goooseby.keye.update.matchingUpdateSigners
import io.github.goooseby.keye.update.shouldCheckUpdateToday
import io.github.goooseby.keye.update.validUpdateUrl
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun versionsCompareNumericallyAndOnlyStableTagsAreAccepted() {
        assertTrue(StableVersion.parse("v0.10.0")!!>StableVersion.parse("0.9.9")!!)
        assertTrue(StableVersion.parse("2.0.0")!!>StableVersion.parse("1.99.99")!!)
        assertEquals(StableVersion.parse("v0.2.0"),StableVersion.parse("0.2.0"))
        listOf("v0.2.0-beta.1","0.2","01.2.0","1.2.3.4","999999999999999.0.0","0.2.0 ").forEach {assertNull(it,StableVersion.parse(it))}
    }
    @Test fun automaticChecksPersistOneAttemptPerLocalDayIncludingFailures() {
        val day=LocalDate.of(2026,11,3)
        assertTrue(shouldCheckUpdateToday(day,null,true))
        assertFalse(shouldCheckUpdateToday(day,"2026-11-03",true))
        assertTrue(shouldCheckUpdateToday(day,"2026-11-02",true))
        assertFalse(shouldCheckUpdateToday(day,"2026-11-04",true))
        assertFalse(shouldCheckUpdateToday(day,null,false))
        assertTrue(shouldCheckUpdateToday(day,"corrupt-date",true))
        assertTrue(shouldCheckUpdateToday(day.plusDays(1),day.toString(),true))
    }
    @Test fun packageDownloadsAreRestrictedToThisRepositoriesHttpsAssets() {
        assertTrue(validUpdateUrl("https://github.com/goooseby/keye-android/releases/download/v0.2.0/keye-android.apk"))
        listOf(
            "http://github.com/goooseby/keye-android/releases/download/v0.2.0/app.apk",
            "https://github.com.evil.example/goooseby/keye-android/releases/download/v0.2.0/app.apk",
            "https://github.com/other/project/releases/download/v0.2.0/app.apk",
            "https://user@github.com/goooseby/keye-android/releases/download/v0.2.0/app.apk",
            "https://github.com:8443/goooseby/keye-android/releases/download/v0.2.0/app.apk",
            "https://github.com/goooseby/keye-android/releases/download/../app.apk",
            "https://github.com/goooseby/keye-android/releases/download/%2e%2e/app.apk",
            "https://github.com/goooseby/keye-android/releases/download/v0.2.0/app.apk?token=secret"
        ).forEach {assertFalse(it,validUpdateUrl(it))}
    }
    @Test fun unsignedAndDifferentlySignedPackagesCannotReplaceCurrentInstallation() {
        assertTrue(matchingUpdateSigners(setOf("A","B"),setOf("B","A")))
        assertFalse(matchingUpdateSigners(emptySet(),emptySet()))
        assertFalse(matchingUpdateSigners(setOf("debug"),setOf("release")))
        assertFalse(matchingUpdateSigners(setOf("A","B"),setOf("A")))
    }
}
