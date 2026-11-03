package io.github.goooseby.keye.ui

import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.goooseby.keye.KeyeViewModel
import io.github.goooseby.keye.network.SchoolApi
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.EmptyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SchoolLoginScreen(model: KeyeViewModel, onClose: () -> Unit) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failure by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var checking by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    suspend fun completeLogin(cookie: String) {
        if(checking) return
        checking=true
        try {
            if(model.login(cookie)) onClose()
            else hint=model.state.value.notice ?: "请先在学校页面完成登录"
        } finally {checking=false}
    }
    BackHandler { if (browser?.canGoBack() == true) browser?.goBack() else onClose() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        var lastCookie:String?=null
        while (true) {
            delay(1000)
            val cookie = CookieManager.getInstance().getCookie(SchoolApi.HOME)
            if(cookie!=lastCookie && SchoolApi.authFromCookies(cookie)!=null && !checking) {
                lastCookie=cookie
                completeLogin(cookie!!)
            }
        }
    }
    val animatedProgress by animateFloatAsState(progress,tween(250),label="login progress")
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Surface {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal=8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { AppIcon("back", "返回") }
                Text("学校登录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { failure = false; browser?.reload() }) { AppIcon("refresh", "刷新登录页") }
                TextButton(enabled=!checking,onClick={scope.launch {completeLogin(CookieManager.getInstance().getCookie(SchoolApi.HOME).orEmpty())}}) {Text(if(checking) "验证中" else "完成")}
            }
        }
        hint?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (progress < 1f && !failure) LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                WebView(context).apply {
                    browser = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress / 100f }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { failure = false; hint = null }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) failure = true
                        }
                    }
                    loadUrl(SchoolApi.HOME)
                }
            }, onRelease = { browser = null; it.stopLoading(); it.destroy() })
            if (failure) Surface(Modifier.fillMaxSize()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    EmptyState("error","登录页面加载失败",detail="请检查网络连接",action="重新加载",onAction={failure=false;browser?.loadUrl(SchoolApi.HOME)})
                }
            }
        }
    }
}
