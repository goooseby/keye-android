package io.github.goooseby.keye

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
