package io.github.goooseby.keye.update

import java.net.URI
import java.time.LocalDate

internal const val UPDATE_REPOSITORY="goooseby/keye-android"
internal const val UPDATE_APK_NAME="keye-android.apk"
internal const val UPDATE_ACTION="io.github.goooseby.keye.SHOW_UPDATES"
internal const val UPDATE_NOTIFICATION_ID=4201

internal data class StableVersion(val major: Int,val minor: Int,val patch: Int): Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int = compareValuesBy(this,other,{it.major},{it.minor},{it.patch})
    companion object {
        fun parse(value: String): StableVersion? {
            val match=Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(value) ?: return null
            val numbers=match.groupValues.drop(1).map {it.toIntOrNull() ?: return null}
            return StableVersion(numbers[0],numbers[1],numbers[2])
        }
    }
}

internal fun shouldCheckUpdateToday(today: LocalDate,lastAttempt: String?,enabled: Boolean): Boolean {
    if(!enabled) return false
    val previous=runCatching {LocalDate.parse(lastAttempt)}.getOrNull()
    return previous==null || today.isAfter(previous)
}

internal fun validUpdateUrl(value: String): Boolean = runCatching {
    val uri=URI(value)
    uri.scheme=="https" && uri.host=="github.com" && uri.userInfo==null && uri.port==-1 &&
        uri.query==null && uri.fragment==null && uri.rawPath.startsWith("/$UPDATE_REPOSITORY/releases/download/") &&
        uri.rawPath.substringAfter("/releases/download/").split('/').size==2 &&
        uri.rawPath.substringAfterLast('/')==UPDATE_APK_NAME &&
        !uri.path.split('/').any {it=="." || it==".."}
}.getOrDefault(false)

internal fun matchingUpdateSigners(current: Set<String>,candidate: Set<String>): Boolean = current.isNotEmpty() && current==candidate
