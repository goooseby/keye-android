package io.github.goooseby.keye

import io.github.goooseby.keye.data.school.hasSchoolLoginCookie
import io.github.goooseby.keye.data.school.isSchoolAuthenticationFailure
import org.junit.Assert.*
import org.junit.Test

class SchoolSessionTest {
    @Test fun schoolRejectingCredentialsRequiresLogin() {
        assertTrue(isSchoolAuthenticationFailure("video.jw.scut.edu.cn",401))
        assertTrue(isSchoolAuthenticationFailure("video.jw.scut.edu.cn",403))
        assertTrue(isSchoolAuthenticationFailure("video.jw.scut.edu.cn",302,"https://sso.example.edu/cas/login?service=classroom"))
        assertTrue(isSchoolAuthenticationFailure("video.jw.scut.edu.cn",302,"/login"))
    }

    @Test fun ordinaryWebsiteCookiesDoNotImplyAnExpiredSchoolLogin() {
        assertFalse(hasSchoolLoginCookie(null))
        assertFalse(hasSchoolLoginCookie("theme=light; language=zh-CN"))
        assertTrue(hasSchoolLoginCookie("theme=light; JWTUser=expired"))
        assertTrue(hasSchoolLoginCookie("_token=expired"))
    }

    @Test fun brokenImagesAndServerFailuresMustNotSignOutTheAccount() {
        assertFalse(isSchoolAuthenticationFailure("cdn.example.edu",403))
        assertFalse(isSchoolAuthenticationFailure("video.jw.scut.edu.cn",403,imageRequest=true))
        assertFalse(isSchoolAuthenticationFailure("video.jw.scut.edu.cn",500))
        assertFalse(isSchoolAuthenticationFailure("video.jw.scut.edu.cn",200))
        assertFalse(isSchoolAuthenticationFailure("video.jw.scut.edu.cn",302,"/maintenance"))
        assertFalse(isSchoolAuthenticationFailure("video.jw.scut.edu.cn",302))
    }
}
