package io.github.goooseby.keye.ui

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.goooseby.keye.KeyeViewModel
import io.github.goooseby.keye.R
import io.github.goooseby.keye.network.*
import io.github.goooseby.keye.ui.components.AppIcon
import io.github.goooseby.keye.ui.components.EmptyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun SchoolLoginScreen(model: KeyeViewModel,onClose: ()->Unit,initialPage: String=SchoolApi.HOME,verifySession: Boolean=true) {
    var browser by remember {mutableStateOf<WebView?>(null)}
    var generation by remember {mutableIntStateOf(0)}
    var navigation by remember {mutableIntStateOf(0)}
    var currentUrl by remember {mutableStateOf(initialPage)}
    var progress by remember {mutableFloatStateOf(0f)}
    var loading by remember {mutableStateOf(true)}
    var visible by remember {mutableStateOf(false)}
    var slow by remember {mutableStateOf(false)}
    var failure by remember {mutableStateOf<String?>(null)}
    var failedDocument by remember {mutableStateOf<String?>(null)}
    var hint by remember {mutableStateOf<String?>(null)}
    var menu by remember {mutableStateOf(false)}
    var checking by remember {mutableStateOf(false)}
    var observedIdentity by remember {mutableStateOf<Triple<String,String,String>?>(null)}
    var failures by remember {mutableIntStateOf(0)}
    var nextVerification by remember {mutableLongStateOf(0L)}
    var automaticBlocked by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val close by rememberUpdatedState(onClose)

    fun retry(home: Boolean=false) {
        failure=null;failedDocument=null;hint=null;slow=false;loading=true;visible=false;progress=0f
        failures=0;automaticBlocked=false;nextVerification=0
        navigation++
        if(browser==null) {currentUrl=if(home) SchoolApi.HOME else initialPage;generation++}
        else if(home) browser?.loadUrl(SchoolApi.HOME) else browser?.reload()
    }
    suspend fun completeLogin(cookie: String,manual: Boolean=false) {
        if(checking) return
        if(manual) {failures=0;automaticBlocked=false}
        checking=true;hint=null
        try {
            when(val result=model.login(cookie)) {
                SchoolLoginResult.Connected ->{
                    val verified=SchoolApi.authFromCookies(cookie)
                    val latest=SchoolApi.authFromCookies(CookieManager.getInstance().getCookie(SchoolApi.HOME))
                    if(verified!=null && latest?.jwt==verified.jwt && latest.userId==verified.userId && latest.tenantId==verified.tenantId) close()
                    else {automaticBlocked=false;nextVerification=0;hint=if(latest==null) "请完成学校登录后点“完成”检查" else "登录信息已更新，正在重新检查"}
                }
                SchoolLoginResult.NeedsLogin ->{
                    automaticBlocked=true
                    if(manual || SchoolApi.authFromCookies(cookie)!=null) hint="请在学校页面完成登录，再点“完成”验证"
                }
                SchoolLoginResult.Superseded ->{automaticBlocked=true;hint="验证已中断，可点“完成”重新检查"}
                is SchoolLoginResult.Retryable ->{
                    failures++
                    val wait=loginVerificationRetryDelay(failures)
                    automaticBlocked=wait==null
                    nextVerification=SystemClock.elapsedRealtime()+max(wait ?: 0,result.retryAfterMillis)
                    hint=result.message+if(wait==null) "；可点“完成”重试" else "；稍后自动重试，也可点“完成”检查"
                }
            }
        } finally {checking=false}
    }
    BackHandler {if(browser?.canGoBack()==true) browser?.goBack() else close()}
    LaunchedEffect(navigation,loading) {
        if(loading) {delay(12_000);if(loading) slow=true}
    }
    LaunchedEffect(lifecycle,verifySession) {
        if(verifySession) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(true) {
                delay(1_000)
                val cookie=CookieManager.getInstance().getCookie(SchoolApi.HOME)
                val credentials=SchoolApi.authFromCookies(cookie)
                val identity=credentials?.let {Triple(it.tenantId,it.userId,it.jwt)}
                if(identity!=observedIdentity) {
                    observedIdentity=identity;failures=0;automaticBlocked=false;nextVerification=0
                }
                if(!checking && !automaticBlocked && SystemClock.elapsedRealtime()>=nextVerification && credentials!=null) completeLogin(cookie!!)
            }
        }
    }
    DisposableEffect(browser,lifecycle) {
        val view=browser
        val observer=LifecycleEventObserver {_,event ->
            if(event==Lifecycle.Event.ON_RESUME) view?.onResume()
            if(event==Lifecycle.Event.ON_PAUSE) view?.onPause()
        }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view?.onResume() else view?.onPause()
        onDispose {lifecycle.removeObserver(observer)}
    }
    val animatedProgress by animateFloatAsState(progress,tween(180),label="login progress")
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={close()}) {AppIcon("back","返回")}
            Column(Modifier.weight(1f)) {
                Text("学校登录",style=MaterialTheme.typography.titleMedium)
                Text(Uri.parse(currentUrl).host.orEmpty(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            IconButton(onClick={retry()}) {AppIcon("refresh","刷新登录页")}
            Box {
                IconButton(onClick={menu=true}) {AppIcon("more","登录选项")}
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text("重新打开学校平台")},onClick={menu=false;retry(home=true)})
                }
            }
            TextButton(enabled=!checking && verifySession,onClick={scope.launch {completeLogin(CookieManager.getInstance().getCookie(SchoolApi.HOME).orEmpty(),manual=true)}}) {Text(if(checking) "验证中" else "完成")}
        }
        if(checking) LinearProgressIndicator(modifier=Modifier.fillMaxWidth().height(2.dp))
        else if(loading && failure==null) LinearProgressIndicator(progress={animatedProgress},modifier=Modifier.fillMaxWidth().height(2.dp))
        hint?.let {Text(it,Modifier.padding(horizontal=16.dp,vertical=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
        if(slow && loading && visible && failure==null) Text("学校页面响应较慢，可以继续等待或刷新",Modifier.padding(horizontal=16.dp,vertical=6.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            key(generation) {
                AndroidView(modifier=Modifier.fillMaxSize(),factory={
                    FrameLayout(context).apply {
                        try {
                            val web=WebView(context)
                            browser=web
                            web.id=R.id.school_login_webview
                            web.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_YES
                            web.settings.apply {
                                javaScriptEnabled=true;domStorageEnabled=true
                                allowFileAccess=false;allowContentAccess=false
                            }
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(web,true)
                            fun fail(message: String) {loading=false;failure=message;failedDocument=currentUrl;visible=false}
                            web.webChromeClient=object: WebChromeClient() {
                                override fun onProgressChanged(view: WebView?,newProgress: Int) {if(browser===view) progress=newProgress/100f}
                            }
                            web.webViewClient=object: WebViewClient() {
                                override fun onPageStarted(view: WebView?,url: String?,favicon: Bitmap?) {
                                    if(browser!==view) return
                                    // Chromium may start its own error document after reporting failure.
                                    // Only a new navigation or explicit retry clears that failure.
                                    if(failure!=null && url?.startsWith("chrome-error:")==true) return
                                    val failed=sameLoginDocument(url,failedDocument)
                                    currentUrl=url ?: currentUrl
                                    if(!failed) failure=null
                                    loading=!failed;visible=false;slow=false;progress=0f;navigation++
                                }
                                override fun onPageCommitVisible(view: WebView?,url: String?) {
                                    if(browser===view && sameLoginDocument(url,currentUrl) && failure==null) visible=true
                                }
                                override fun onPageFinished(view: WebView?,url: String?) {
                                    if(browser===view && sameLoginDocument(url,currentUrl)) {loading=false;progress=1f;if(failure==null) visible=true}
                                }
                                override fun shouldOverrideUrlLoading(view: WebView?,request: WebResourceRequest?): Boolean {
                                    if(browser!==view) return true
                                    if(request?.isForMainFrame!=true) return false
                                    val url=request.url
                                    if(url.scheme=="https" && !url.host.isNullOrBlank()) return false
                                    if(url.toString()=="about:blank") return false
                                    if(url.scheme=="http") fail("学校页面跳转到未加密地址，已停止打开；可重新进入学校平台")
                                    else hint="此链接需要外部应用，请使用学校网页登录入口"
                                    return true
                                }
                                override fun onReceivedError(view: WebView?,request: WebResourceRequest?,error: WebResourceError?) {
                                    if(browser!==view || error?.description?.contains("ERR_ABORTED")==true) return
                                    if(request?.isForMainFrame==true && failure==null) {
                                        fail(when(error?.errorCode) {
                                            ERROR_TIMEOUT ->"连接学校页面超时，请检查网络后重试"
                                            ERROR_HOST_LOOKUP ->"暂时无法找到学校服务器，请检查网络、校园网或 VPN"
                                            ERROR_REDIRECT_LOOP ->"学校登录跳转次数过多，请重新打开学校平台"
                                            else ->"暂时无法连接学校页面，请检查网络后重试"
                                        })
                                    } else if(request?.url?.path?.let {it.endsWith(".js") || it.endsWith(".css")}==true) hint="部分网页资源未能加载；若页面空白或按钮无响应，可刷新重试"
                                }
                                override fun onReceivedHttpError(view: WebView?,request: WebResourceRequest?,response: WebResourceResponse?) {
                                    if(browser===view && request?.isForMainFrame==true) {
                                        val code=response?.statusCode
                                        fail(if(code==429) "学校平台访问较频繁，请稍后重试" else "学校页面暂时不可用（HTTP $code），请稍后重试或重新进入")
                                    }
                                }
                                override fun onReceivedSslError(view: WebView?,handler: SslErrorHandler?,error: SslError?) {
                                    handler?.cancel()
                                    if(browser===view) {
                                        if(sameLoginDocument(error?.url,currentUrl)) fail("学校页面的安全证书无法验证，请检查手机日期、网络或联系学校平台")
                                        else hint="部分网页资源的安全证书无法验证，已停止加载"
                                    }
                                }
                                override fun onRenderProcessGone(view: WebView?,detail: RenderProcessGoneDetail?): Boolean {
                                    if(browser===view) {
                                        (view?.parent as? ViewGroup)?.removeView(view)
                                        view?.destroy();browser=null
                                        fail("网页进程已停止，可重新打开登录页；已有登录信息仍会保留")
                                    }
                                    return true
                                }
                            }
                            addView(web,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                            web.loadUrl(currentUrl)
                        } catch(_: Exception) {
                            browser?.let {removeView(it);it.destroy()};browser=null;loading=false
                            failure="无法启动系统网页组件，请检查 Android System WebView 或 Chrome 是否可用"
                        }
                    }
                },onRelease={host ->
                    val web=host.getChildAt(0) as? WebView
                    if(browser===web) browser=null
                    host.removeAllViews()
                    web?.let {runCatching {it.stopLoading();it.destroy()}}
                })
            }
            if(failure!=null) Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    EmptyState("error","学校页面暂时无法打开",detail=failure,action="重新加载",onAction={retry()})
                    TextButton(onClick={retry(home=true)}) {Text("重新进入学校平台")}
                }
            } else if(!visible) Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp),strokeWidth=2.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(if(slow) "学校页面响应较慢" else "正在打开学校网页",style=MaterialTheme.typography.bodyMedium)
                    if(slow) {
                        Text("可继续等待，或重新进入。不会自动重复提交登录表单。",Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick={retry(home=true)}) {Text("重新进入学校平台")}
                    }
                }
            }
        }
    }
}
