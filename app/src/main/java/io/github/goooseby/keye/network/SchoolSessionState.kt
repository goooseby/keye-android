package io.github.goooseby.keye.network

import java.net.URI

enum class SchoolConnection { SignedOut, Checking, Connected, Unverified, Expired }

fun schoolConnectionLabel(state: SchoolConnection): String = when(state) {
    SchoolConnection.SignedOut -> "未登录"
    SchoolConnection.Checking -> "验证中"
    SchoolConnection.Connected -> "已连接"
    SchoolConnection.Unverified -> "暂时无法验证"
    SchoolConnection.Expired -> "登录已失效"
}

internal fun isSchoolAuthenticationFailure(host: String,status: Int,location: String?=null,imageRequest: Boolean=false): Boolean {
    if(imageRequest) return false
    if(host!="video.jw.scut.edu.cn") return false
    if(status==401 || status==403) return true
    if(status !in 300..399 || location==null) return false
    val path=runCatching {URI(location).path.orEmpty()}.getOrDefault("")
    return Regex("(?:^|/)(?:login|signin)(?:/|$)",RegexOption.IGNORE_CASE).containsMatchIn(path)
}

internal fun hasSchoolLoginCookie(cookie: String?): Boolean = cookie.orEmpty().split(';').any {
    it.substringBefore('=').trim() in setOf("JWTUser","_token")
}

sealed interface SchoolLoginResult {
    data object Connected: SchoolLoginResult
    data object NeedsLogin: SchoolLoginResult
    data object Superseded: SchoolLoginResult
    data class Retryable(val message: String,val retryAfterMillis: Long=5_000): SchoolLoginResult
}

/** Initial attempt plus two retries; manual retry or changed cookies starts a new cycle. */
internal fun loginVerificationRetryDelay(failures: Int): Long? = when(failures) {
    1 ->5_000L;2 ->15_000L;else ->null
}

/** Ignore fragment navigation, but never let a stale request replace a newer document. */
internal fun sameLoginDocument(callback: String?,current: String?): Boolean =
    !callback.isNullOrBlank() && !current.isNullOrBlank() && callback.substringBefore('#')==current.substringBefore('#')
