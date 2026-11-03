package io.github.goooseby.keye

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
import kotlinx.coroutines.delay

@Composable
fun SchoolLoginScreen(model: KeyeViewModel, onClose: () -> Unit) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failure by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    BackHandler { if (browser?.canGoBack() == true) browser?.goBack() else onClose() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val cookie = CookieManager.getInstance().getCookie(SchoolApi.HOME)
            if (SchoolApi.authFromCookies(cookie) != null && model.login(cookie!!)) { onClose(); break }
        }
    }
    val animatedProgress by animateFloatAsState(progress,tween(250),label="login progress")
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Surface {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal=8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { AppIcon("back", "返回") }
                Text("学校登录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { failure = false; browser?.reload() }) { AppIcon("refresh", "刷新登录页") }
                TextButton(onClick = {
                    if (model.login(CookieManager.getInstance().getCookie(SchoolApi.HOME).orEmpty())) onClose()
                    else hint = "请先在学校页面完成登录"
                }) { Text("完成") }
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
