package com.sawaria.club

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sawaria.club.ui.theme.SawariaClubTheme

private const val LOGIN_URL = "https://sawaria.club/user/login.php"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SawariaClubTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private fun isOnline(context: Context): Boolean {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

    val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val hasSupportedTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    return hasInternet && hasSupportedTransport
}

/**
 * Hands a non-http(s) link (e.g. `whatsapp://`, `intent://`, `tel:`, `mailto:`) off to
 * whichever app on the device handles it. Returns false if nothing could handle it.
 */
private fun openInExternalApp(context: Context, request: WebResourceRequest): Boolean {
    val uri = request.url
    return try {
        val intent = if (uri.scheme == "intent") {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            true
        } else {
            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
            if (fallbackUrl != null) {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
                true
            } else {
                Toast.makeText(context, "No app found to open this link", Toast.LENGTH_SHORT).show()
                false
            }
        }
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open this link", Toast.LENGTH_SHORT).show()
        false
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember {
        val instance = WebView(context)
        instance.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(instance, true)
            }

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val scheme = request.url.scheme
                    if (scheme == "http" || scheme == "https") {
                        // Keep ordinary web navigation inside the WebView.
                        return false
                    }
                    // Non-web links (whatsapp:, intent:, tel:, mailto:, ...) can't be
                    // loaded by the WebView itself — hand them to an installed app.
                    return openInExternalApp(context, request)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    hasError = false
                    isLoading = true
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    isLoading = false
                    canGoBack = view.canGoBack()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        hasError = true
                        isLoading = false
                    }
                }
            }
        }
    }

    fun loadLoginPage() {
        if (isOnline(context)) {
            hasError = false
            isLoading = true
            webView.loadUrl(LOGIN_URL)
        } else {
            hasError = true
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadLoginPage()
    }

    BackHandler(enabled = canGoBack) {
        webView.goBack()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

        if (isLoading && !hasError) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (hasError) {
            NoConnectionScreen(
                modifier = Modifier.fillMaxSize(),
                onRetry = { loadLoginPage() }
            )
        }
    }
}

@Composable
private fun NoConnectionScreen(modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "No internet connection")
        Text(text = "Please check your network and try again.")
        Button(
            modifier = Modifier.padding(top = 16.dp),
            onClick = onRetry
        ) {
            Text(text = "Retry")
        }
    }
}
