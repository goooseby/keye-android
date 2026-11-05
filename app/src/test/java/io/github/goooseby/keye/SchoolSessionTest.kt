package io.github.goooseby.keye

import io.github.goooseby.keye.network.hasSchoolLoginCookie
import io.github.goooseby.keye.network.isSchoolAuthenticationFailure
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

    @Test fun loginVerificationRetriesAreBounded() {
        assertEquals(5_000L,io.github.goooseby.keye.network.loginVerificationRetryDelay(1))
        assertEquals(15_000L,io.github.goooseby.keye.network.loginVerificationRetryDelay(2))
        assertNull(io.github.goooseby.keye.network.loginVerificationRetryDelay(3))
        assertNull(io.github.goooseby.keye.network.loginVerificationRetryDelay(4))
    }
    @Test fun loginCallbacksMustBelongToTheCurrentDocument() {
        assertTrue(io.github.goooseby.keye.network.sameLoginDocument("https://sso.example.edu/login#one","https://sso.example.edu/login#two"))
        assertFalse(io.github.goooseby.keye.network.sameLoginDocument("https://video.jw.scut.edu.cn/","https://sso.example.edu/login"))
        assertFalse(io.github.goooseby.keye.network.sameLoginDocument("https://sso.example.edu/login?step=1","https://sso.example.edu/login?step=2"))
        assertFalse(io.github.goooseby.keye.network.sameLoginDocument(null,"https://sso.example.edu/login"))
    }

    @Test fun authenticatedNoClassDayIsNotALoginFailure() {
        assertEquals(io.github.goooseby.keye.network.SchoolScheduleStatus.Empty,io.github.goooseby.keye.network.schoolScheduleStatus(true,400,"课表为空"))
        assertEquals(io.github.goooseby.keye.network.SchoolScheduleStatus.Ready,io.github.goooseby.keye.network.schoolScheduleStatus(true,200,""))
    }
    @Test fun unknownBadRequestsAndFailedEnvelopesAreNotTreatedAsEmptyOrLoggedIn() {
        val unavailable=io.github.goooseby.keye.network.SchoolScheduleStatus.Unavailable
        assertEquals(unavailable,io.github.goooseby.keye.network.schoolScheduleStatus(true,400,"参数错误"))
        assertEquals(unavailable,io.github.goooseby.keye.network.schoolScheduleStatus(false,400,"课表为空"))
        assertEquals(unavailable,io.github.goooseby.keye.network.schoolScheduleStatus(false,200,""))
        assertEquals(unavailable,io.github.goooseby.keye.network.schoolScheduleStatus(null,null,null))
        assertEquals(unavailable,io.github.goooseby.keye.network.schoolScheduleStatus(true,500,""))
        assertEquals(io.github.goooseby.keye.network.SchoolScheduleStatus.NeedsLogin,io.github.goooseby.keye.network.schoolScheduleStatus(false,401,""))
        assertEquals(io.github.goooseby.keye.network.SchoolScheduleStatus.NeedsLogin,io.github.goooseby.keye.network.schoolScheduleStatus(true,403,""))
    }
}
