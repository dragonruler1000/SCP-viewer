package us.minecraftchest2.wikidot_viewer

import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Card
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import us.minecraftchest2.wikidot_viewer.ui.theme.WikidotviewerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WikidotviewerTheme {
                WebViewerApp()
            }
        }
    }
}

@Composable
fun WebViewerApp() {
    val startUrl = "https://scp-wiki.wikidot.com/"
    var title by remember { mutableStateOf("SCP Wiki") }
    var progress by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    // Theme discovery and preferences
    val availableThemes by remember { mutableStateOf(loadThemesFromAssets(context)) }
    var selectedTheme by remember {
        mutableStateOf(loadSelectedTheme(context, availableThemes))
    }
    // Preload CSS contents for themes
    val themeCssMap by remember { mutableStateOf(loadThemeCssMap(context, availableThemes)) }
    // Ignore list from assets once
    val ignoreSet by remember { mutableStateOf(loadIgnoreListFromAssets(context)) }
    // Trigger to re-apply theme when changed
    var themeVersion by remember { mutableIntStateOf(0) }
    // Sidebar state
    var showSidebar by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            WebViewScreen(
                url = startUrl,
                onTitleChanged = { title = it ?: "SCP Wiki" },
                onProgressChanged = { progress = it },
                selectedThemeCss = themeCssMap[selectedTheme.file].orEmpty(),
                themeVersion = themeVersion,
                ignoreSlugs = ignoreSet,
                modifier = Modifier.fillMaxSize()
            )

            if (progress in 0f..0.99f) {
                LinearProgressIndicator(progress = { progress })
            }

            // Floating button on right to open sidebar
            FloatingActionButton(
                onClick = { showSidebar = true },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
            ) {
                Text("Theme")
            }

            ThemeSideBar(
                visible = showSidebar,
                themes = availableThemes,
                selected = selectedTheme,
                onSelect = { theme: ThemeItem ->
                    selectedTheme = theme
                    saveSelectedTheme(context, theme)
                    themeVersion++ // trigger re-apply
                    showSidebar = false
                },
                onClose = { showSidebar = false },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun ThemeSideBar(
    visible: Boolean,
    themes: List<ThemeItem>,
    selected: ThemeItem,
    onSelect: (ThemeItem) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val width = 300.dp
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(animationSpec = tween(200)) { fullWidth -> fullWidth },
        exit = slideOutHorizontally(animationSpec = tween(200)) { fullWidth -> fullWidth },
        modifier = modifier
    ) {
        Surface(
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxHeight()
                .width(width)
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Select Theme", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(themes) { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(theme) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (theme.file == selected.file),
                                onClick = { onSelect(theme) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(theme.title)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WebViewScreen(
    url: String,
    modifier: Modifier = Modifier,
    onTitleChanged: (String?) -> Unit = {},
    onProgressChanged: (Float) -> Unit = {},
    selectedThemeCss: String = "",
    themeVersion: Int = 0,
    ignoreSlugs: Set<String> = emptySet()
) {
    val activity = (LocalContext.current as? ComponentActivity)
    var webView: WebView? by remember { mutableStateOf(null) }
    var lastEligible by remember { mutableStateOf(false) }
    var lastUrl by remember { mutableStateOf("") }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Enable cookies for wikidot auth/session features
                try {
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    // Third-party cookies for embedded wikidot subresources when required
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                } catch (_: Throwable) {
                    // Best-effort; ignore if WebView engine rejects
                }
                // Enable caching behavior: use cache when offline, network when available
                val online = isNetworkAvailable(context)
                settings.cacheMode = if (online) {
                    WebSettings.LOAD_DEFAULT
                } else {
                    WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
                // Allow file access for WebView resource cache
                settings.allowFileAccess = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        onTitleChanged(title)
                    }

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress / 100f)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val target = request?.url ?: return false
                        // Keep navigation inside WebView for wikidot and scp-wiki hosts
                        val host = target.host ?: ""
                        val internal = host.endsWith("wikidot.com")
                        return if (internal) {
                            false
                        } else {
                            // Open external links in a browser
                            context.startActivity(Intent(Intent.ACTION_VIEW, target))
                            true
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val u = url ?: return
                        val parsed = Uri.parse(u)
                        val host = parsed.host ?: return
                        if (host != "scp-wiki.wikidot.com") return
                        val path = parsed.path ?: "/"
                        if (ignoreSlugs.contains(path)) return
                        lastEligible = true
                        lastUrl = u
                        // Apply selected theme
                        applyThemeCss(view, selectedThemeCss)
                    }
                }

                loadUrl(url)
                webView = this
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.destroy()
            if (webView === view) webView = null
        }
    )

    BackHandler(enabled = (webView?.canGoBack() == true)) {
        webView?.goBack()
    }

    // When themeVersion changes, re-apply on the current page if eligible
    LaunchedEffect(themeVersion) {
        if (lastEligible) {
            applyThemeCss(webView, selectedThemeCss)
        }
    }
}

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    // Consider internet capability sufficient; VALIDATED may be false on captive portals
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// --- Assets loading helpers ---
// --- Theme discovery and persistence ---
private const val THEMES_DIR = "css"
private const val THEME_SUFFIX = ".colors.css"
private const val PREFS_NAME = "viewer_prefs"
private const val PREFS_KEY_THEME = "selected_theme_file"

private data class ThemeItem(val file: String, val title: String)

private fun loadThemesFromAssets(context: Context): List<ThemeItem> {
    val am = context.assets
    val files = try {
        am.list(THEMES_DIR)?.filter { it.endsWith(THEME_SUFFIX, ignoreCase = true) }?.sorted()
    } catch (_: Throwable) { null } ?: emptyList()
    return files.map { fname ->
        val title = fname.removeSuffix(THEME_SUFFIX)
            .replace('_', ' ').replace('-', ' ')
            .split(' ').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        ThemeItem(file = fname, title = title)
    }
}

private fun loadThemeCssMap(context: Context, themes: List<ThemeItem>): Map<String, String> {
    val am = context.assets
    val map = HashMap<String, String>(themes.size)
    for (t in themes) {
        val path = "$THEMES_DIR/${t.file}"
        val css = try { am.open(path).bufferedReader().use { it.readText() } } catch (_: Throwable) { "" }
        map[t.file] = css
    }
    return map
}

private fun loadSelectedTheme(context: Context, themes: List<ThemeItem>): ThemeItem {
    val defaultFile = "none.colors.css"
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val saved = prefs.getString(PREFS_KEY_THEME, defaultFile) ?: defaultFile
    return themes.firstOrNull { it.file == saved } ?: themes.firstOrNull { it.file == defaultFile } ?: themes.firstOrNull() ?: ThemeItem(defaultFile, "None")
}

private fun saveSelectedTheme(context: Context, theme: ThemeItem) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(PREFS_KEY_THEME, theme.file).apply()
}

private fun loadIgnoreListFromAssets(context: Context): Set<String> {
    val am = context.assets
    val file = "css_ignore_list.txt"
    return try {
        am.open(file).bufferedReader().useLines { lines ->
            lines
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { if (!it.startsWith("/")) "/$it" else it }
                .toSet()
        }
    } catch (_: Throwable) {
        emptySet()
    }
}

private fun injectCss(view: WebView?, css: String, elementId: String) {
    if (view == null || css.isEmpty()) return
    // Base64-encode to avoid escaping issues
    val b64 = Base64.encodeToString(css.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    val js = """
        (function(){
          try {
            var style = document.getElementById('$elementId');
            if(!style){
              style = document.createElement('style');
              style.type = 'text/css';
              style.id = '$elementId';
              document.head.appendChild(style);
            }
            var cssText = atob('$b64');
            style.appendChild(document.createTextNode(cssText));
          } catch(e) { /* no-op */ }
        })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
}

private fun removeInjectedCss(view: WebView?, elementId: String) {
    if (view == null) return
    val js = """
        (function(){
          try {
            var style = document.getElementById('$elementId');
            if(style && style.parentNode){ style.parentNode.removeChild(style); }
          } catch(e) { /* no-op */ }
        })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
}

private fun applyThemeCss(view: WebView?, css: String) {
    val id = "__app_theme_css__"
    removeInjectedCss(view, id)
    if (css.isNotEmpty()) {
        injectCss(view, css, id)
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    WikidotviewerTheme {
        Text("SCP Wiki")
    }
}