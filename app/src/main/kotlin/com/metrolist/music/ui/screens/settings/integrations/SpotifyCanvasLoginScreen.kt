/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.SpotifyCanvasEnabledKey
import com.metrolist.music.constants.SpotifyCookieKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.spotify.hasSpotifyPersonalizationCookie
import com.metrolist.music.utils.spotify.mergeSpotifyCookieInputs

private const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

private val SpotifyCookieUrls =
    listOf(
        "https://open.spotify.com",
        "https://accounts.spotify.com",
        "https://www.spotify.com",
        "https://spotify.com",
    )

private fun isSpotifyWebPlayerUrl(url: String?): Boolean =
    url?.startsWith("https://open.spotify.com") == true ||
        url?.startsWith("https://www.spotify.com") == true

/**
 * Spotify's login page renders <main> as `position: absolute; overflow: auto`.
 * Under WebView this collapses to the height of its first laid-out child
 * (~48px) instead of the ~700px+ of actual form content, clipping the
 * login form out of view. This is a layout bug, not a selector change, so
 * we neutralize it with an injected stylesheet rather than touching the UA
 * or DOM structure. A MutationObserver re-applies the fix since Spotify's
 * SPA can re-render/re-collapse `<main>` after onPageFinished fires.
 */
private fun injectSpotifyLayoutFix(view: WebView) {
    val js =
        """
        (function() {
            var STYLE_ID = 'metrolist-spotify-layout-fix';
            function applyFix() {
                if (document.getElementById(STYLE_ID)) return;
                var style = document.createElement('style');
                style.id = STYLE_ID;
                style.type = 'text/css';
                style.innerHTML = [
                    'main {',
                    '  position: relative !important;',
                    '  height: auto !important;',
                    '  min-height: 100% !important;',
                    '  overflow: visible !important;',
                    '}',
                    'main > div {',
                    '  height: auto !important;',
                    '  overflow: visible !important;',
                    '}'
                    ].join('\n');
                (document.head || document.documentElement).appendChild(style);
            }
            applyFix();
            if (window.__metrolistSpotifyObserver) return;
            var observer = new MutationObserver(function() { applyFix(); });
            observer.observe(document.documentElement, { childList: true, subtree: true });
            window.__metrolistSpotifyObserver = observer;
        })();
        """.trimIndent()

    view.evaluateJavascript(js, null)
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyCanvasLoginScreen(
    navController: NavController,
) {
    var spotifyCookie by rememberPreference(SpotifyCookieKey, "")
    var spotifyCanvasEnabled by rememberPreference(SpotifyCanvasEnabledKey, false)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var cookieCaptured by remember { mutableStateOf(false) }

    fun captureCookie(showConfirmation: Boolean) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        val normalizedCookie =
            mergeSpotifyCookieInputs(
                SpotifyCookieUrls
                    .mapNotNull { cookieManager.getCookie(it) },
            )

        if (normalizedCookie != null) {
            spotifyCookie = normalizedCookie
            spotifyCanvasEnabled = true
            if (showConfirmation && hasSpotifyPersonalizationCookie(normalizedCookie)) {
                cookieCaptured = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
        }
    }

    Box(
        modifier =
            Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .fillMaxSize(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }

                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(
                                view: WebView,
                                url: String?,
                            ) {
                                injectSpotifyLayoutFix(view)
                                captureCookie(showConfirmation = isSpotifyWebPlayerUrl(url))
                            }
                        }

                    webView = this
                    loadUrl(SPOTIFY_LOGIN_URL)
                }
            },
        )

        AnimatedVisibility(
            visible = cookieCaptured,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
            ) {
                Text(
                    text = stringResource(R.string.spotify_canvas_cookie_saved),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.spotify_canvas_web_login)) },
        navigationIcon = {
            IconButton(
                onClick = {
                    captureCookie(showConfirmation = false)
                    navController.navigateUp()
                },
                onLongClick = {
                    captureCookie(showConfirmation = false)
                    navController.backToMain()
                },
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )

    BackHandler {
        val currentWebView = webView
        if (currentWebView?.canGoBack() == true) {
            currentWebView.goBack()
        } else {
            captureCookie(showConfirmation = false)
            navController.navigateUp()
        }
    }
}
