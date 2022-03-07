/*
 * Copyright (c) 2017 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.browser

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.net.http.SslError.*
import android.os.Build
import android.util.Base64
import android.webkit.*
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewCompat.WebMessageListener
import androidx.webkit.WebViewFeature
import com.duckduckgo.app.accessibility.AccessibilityManager
import com.duckduckgo.app.browser.certificates.rootstore.CertificateValidationState
import com.duckduckgo.app.browser.certificates.rootstore.TrustedCertificateStore
import com.duckduckgo.app.browser.cookies.ThirdPartyCookieManager
import com.duckduckgo.app.browser.httpauth.WebViewHttpAuthStore
import com.duckduckgo.app.browser.logindetection.DOMLoginDetector
import com.duckduckgo.app.browser.logindetection.WebNavigationEvent
import com.duckduckgo.app.browser.model.BasicAuthenticationRequest
import com.duckduckgo.app.browser.navigation.safeCopyBackForwardList
import com.duckduckgo.app.email.EmailInjector
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.global.UriString
import com.duckduckgo.app.global.domain
import com.duckduckgo.app.global.exception.UncaughtExceptionRepository
import com.duckduckgo.app.global.exception.UncaughtExceptionSource.*
import com.duckduckgo.app.logins.NativeFormDetector
import com.duckduckgo.app.statistics.store.OfflinePixelCountDataStore
import com.duckduckgo.privacy.config.api.Gpc
import com.duckduckgo.privacy.config.api.TrackingLinkDetector
import java.net.URI
import java.util.*
import kotlinx.coroutines.*
import timber.log.Timber

class BrowserWebViewClient(
    private val webViewHttpAuthStore: WebViewHttpAuthStore,
    private val trustedCertificateStore: TrustedCertificateStore,
    private val requestRewriter: RequestRewriter,
    private val specialUrlDetector: SpecialUrlDetector,
    private val requestInterceptor: RequestInterceptor,
    private val offlinePixelCountDataStore: OfflinePixelCountDataStore,
    private val uncaughtExceptionRepository: UncaughtExceptionRepository,
    private val cookieManager: CookieManager,
    private val loginDetector: DOMLoginDetector,
    private val dosDetector: DosDetector,
    private val gpc: Gpc,
    private val thirdPartyCookieManager: ThirdPartyCookieManager,
    private val appCoroutineScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val emailInjector: EmailInjector,
    private val accessibilityManager: AccessibilityManager,
    private val trackingLinkDetector: TrackingLinkDetector,
    private val assetLoader: WebViewAssetLoader
) : WebViewClient() {

    var webViewClientListener: WebViewClientListener? = null
    private var lastPageStarted: String? = null

    private val postMessageKeys = mutableSetOf<String>()

    /** This is the new method of url overriding available from API 24 onwards */
    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        return shouldOverride(view, url, request.isForMainFrame)
    }

    /** * This is the old, deprecated method of url overriding available until API 23 */
    @Suppress("OverridingDeprecatedMember")
    override fun shouldOverrideUrlLoading(view: WebView, urlString: String): Boolean {
        val url = Uri.parse(urlString)
        return shouldOverride(view, url, isForMainFrame = true)
    }

    /** API-agnostic implementation of deciding whether to override url or not */
    private fun shouldOverride(webView: WebView, url: Uri, isForMainFrame: Boolean): Boolean {

        Timber.v("shouldOverride $url")
        try {
            if (isForMainFrame && dosDetector.isUrlGeneratingDos(url)) {
                webView.loadUrl("about:blank")
                webViewClientListener?.dosAttackDetected()
                return false
            }

            return when (val urlType = specialUrlDetector.determineType(url)) {
                is SpecialUrlDetector.UrlType.Email -> {
                    webViewClientListener?.sendEmailRequested(urlType.emailAddress)
                    true
                }
                is SpecialUrlDetector.UrlType.Telephone -> {
                    webViewClientListener?.dialTelephoneNumberRequested(urlType.telephoneNumber)
                    true
                }
                is SpecialUrlDetector.UrlType.Sms -> {
                    webViewClientListener?.sendSmsRequested(urlType.telephoneNumber)
                    true
                }
                is SpecialUrlDetector.UrlType.AppLink -> {
                    Timber.i("Found app link for ${urlType.uriString}")
                    webViewClientListener?.let { listener ->
                        return listener.handleAppLink(urlType, isForMainFrame)
                    }
                    false
                }
                is SpecialUrlDetector.UrlType.NonHttpAppLink -> {
                    Timber.i("Found non-http app link for ${urlType.uriString}")
                    webViewClientListener?.let { listener ->
                        return listener.handleNonHttpAppLink(urlType)
                    }
                    true
                }
                is SpecialUrlDetector.UrlType.Unknown -> {
                    Timber.w("Unable to process link type for ${urlType.uriString}")
                    webView.originalUrl?.let { webView.loadUrl(it) }
                    false
                }
                is SpecialUrlDetector.UrlType.SearchQuery -> false
                is SpecialUrlDetector.UrlType.Web -> {
                    if (requestRewriter.shouldRewriteRequest(url)) {
                        val newUri = requestRewriter.rewriteRequestWithCustomQueryParams(url)
                        webView.loadUrl(newUri.toString())
                        return true
                    }
                    if (isForMainFrame) {
                        webViewClientListener?.willOverrideUrl(url.toString())
                    }
                    false
                }
                is SpecialUrlDetector.UrlType.ExtractedTrackingLink -> {
                    if (isForMainFrame) {
                        webViewClientListener?.startProcessingTrackingLink()
                        Timber.d(
                            "Tracking link detection: Loading extracted URL: ${urlType.extractedUrl}"
                        )
                        webView.loadUrl(urlType.extractedUrl)
                        return true
                    }
                    false
                }
                is SpecialUrlDetector.UrlType.CloakedTrackingLink -> {
                    val lastTrackingLinkInfo = trackingLinkDetector.lastTrackingLinkInfo
                    if (isForMainFrame &&
                        (
                            lastTrackingLinkInfo == null ||
                                lastPageStarted != lastTrackingLinkInfo.destinationUrl
                            )
                    ) {
                        webViewClientListener?.let { listener ->
                            listener.handleCloakedTrackingLink(urlType.trackingUrl)
                            return true
                        }
                    }
                    false
                }
            }
        } catch (e: Throwable) {
            appCoroutineScope.launch(dispatcherProvider.default()) {
                uncaughtExceptionRepository.recordUncaughtException(e, SHOULD_OVERRIDE_REQUEST)
                throw e
            }
            return false
        }
    }

    @UiThread
    override fun onPageStarted(webView: WebView, url: String?, favicon: Bitmap?) {
        try {
            Timber.v("onPageStarted webViewUrl: ${webView.url} URL: $url")
            url?.let {
                appCoroutineScope.launch(dispatcherProvider.default()) {
                    thirdPartyCookieManager.processUriForThirdPartyCookies(webView, url.toUri())
                }

                // hackEnablePostMessages(webView, it)
            }
            val navigationList = webView.safeCopyBackForwardList() ?: return
            webViewClientListener?.navigationStateChanged(WebViewNavigationState(navigationList))
            if (url != null && url == lastPageStarted) {
                webViewClientListener?.pageRefreshed(url)
            }
            lastPageStarted = url
            emailInjector.injectEmailAutofillJs(webView, url) // Needs to be injected
            // onPageStarted
            injectGpcToDom(webView, url)
            loginDetector.onEvent(WebNavigationEvent.OnPageStarted(webView))
        } catch (e: Throwable) {
            appCoroutineScope.launch(dispatcherProvider.default()) {
                uncaughtExceptionRepository.recordUncaughtException(e, ON_PAGE_STARTED)
                throw e
            }
        }
    }

    private fun hackEnablePostMessages(webView: WebView, originalUrl: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {

            // adding duplicate web message listeners causes a crash (can't catch it').
            // maybe makes it too fiddly to add and remove dynamically based on which site is
            // loading

            val uri = originalUrl.toUri()
            val domain = uri.domain()
            val scheme = uri.scheme
            val originRule = "$scheme://$domain"

            var encodedDomain = Base64.encodeToString(domain!!.toByteArray(), Base64.NO_PADDING).lowercase(Locale.US)
            encodedDomain = "wm"
            Timber.i("Registering for post messages on %s, using key %s", originRule, encodedDomain)

            postMessageKeys.filterNot { it == encodedDomain }.forEach {
                WebViewCompat.removeWebMessageListener(webView, it)
            }

            val webMessageListener =
                WebMessageListener { _, message, sourceOrigin, isMainFrame, replyProxy ->
                    Timber.i(
                        "Received message from JS. message=%s, origin=%s, mainFrame=%s",
                        message.data,
                        sourceOrigin,
                        isMainFrame
                    )

                    val messageData = message.data ?: ""
                    val isUrl = UriString.isWebUrl(messageData)
                    if (isUrl) {
                        Timber.i("Looks like we have a URL to investigate")
                        appCoroutineScope.launch(dispatcherProvider.io()) {
                            val loginForm = NativeFormDetector().containsLoginForm(message.data!!)
                            if (loginForm) {
                                webViewClientListener?.formDetectedInIFrame(messageData)
                            }
                        }
                    }

                    replyProxy.postMessage("response from the native layer")
                }

            if (!postMessageKeys.contains(encodedDomain)) {
                postMessageKeys.add(encodedDomain)
                WebViewCompat.addWebMessageListener(webView, "wm", setOf("*"), webMessageListener)
            }

            // WebViewCompat.addWebMessageListener(webView, "wm", setOf("*"), webMessageListener)
        }
    }

    @RequiresApi(23)
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        val errorMessage = String.format("%s %s", error?.description, error?.errorCode?.toString())
        Timber.w("Error %s. url=%s", errorMessage, request?.url)
    }

    @UiThread
    override fun onPageFinished(webView: WebView, url: String?) {
        try {
            accessibilityManager.onPageFinished(webView, url)
            Timber.v("onPageFinished webViewUrl: ${webView.url} URL: $url")
            val navigationList = webView.safeCopyBackForwardList() ?: return
            webViewClientListener?.run {
                navigationStateChanged(WebViewNavigationState(navigationList))
                url?.let { prefetchFavicon(url) }
            }
            flushCookies()
        } catch (e: Throwable) {
            appCoroutineScope.launch(dispatcherProvider.default()) {
                uncaughtExceptionRepository.recordUncaughtException(e, ON_PAGE_FINISHED)
                throw e
            }
        }
    }

    private fun injectGpcToDom(webView: WebView, url: String?) {
        url?.let {
            if (gpc.canGpcBeUsedByUrl(url)) {
                webView.evaluateJavascript("javascript:${gpc.getGpcJs()}", null)
            }
        }
    }

    private fun flushCookies() {
        appCoroutineScope.launch(dispatcherProvider.io()) { cookieManager.flush() }
    }

    @WorkerThread
    override fun shouldInterceptRequest(
        webView: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {

        val localResponse = assetLoader.shouldInterceptRequest(request.url)
        Timber.i("Should intercept request: %s, mainFrame:%s, localResponse:%s", request.url, request.isForMainFrame, localResponse != null)
        if (localResponse != null) {
            if (request.isForMainFrame) hackEnablePostMessages(webView, request.url.toString())
            return localResponse
        }

        Timber.i("shouldInterceptRequest %s, mainFrame: %s", request.url, request.isForMainFrame)

        return runBlocking {
            try {
                val documentUrl = withContext(Dispatchers.Main) { webView.url }
                withContext(Dispatchers.Main) {
                    loginDetector.onEvent(
                        WebNavigationEvent.ShouldInterceptRequest(webView, request)
                    )
                }
                Timber.v(
                    "Intercepting resource ${request.url} type:${request.method} on page $documentUrl"
                )
                return@runBlocking requestInterceptor.shouldIntercept(
                    request, webView, documentUrl, webViewClientListener
                )
            } catch (e: Throwable) {
                uncaughtExceptionRepository.recordUncaughtException(e, SHOULD_INTERCEPT_REQUEST)
                throw e
            }
        }
            .also { response ->
                if (response == null && request.isForMainFrame) {
                    Timber.w("Would inject hook here for %s", request.url)
                    hackEnablePostMessages(webView, request.url.toString())
                }
            }
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        Timber.i("onPageCommitVisible: %s", url)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        Timber.w("onRenderProcessGone. Did it crash? ${detail?.didCrash()}")
        if (detail?.didCrash() == true) {
            offlinePixelCountDataStore.webRendererGoneCrashCount += 1
        } else {
            offlinePixelCountDataStore.webRendererGoneKilledCount += 1
        }

        webViewClientListener?.recoverFromRenderProcessGone()
        return true
    }

    @UiThread
    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler?,
        host: String?,
        realm: String?
    ) {
        try {
            Timber.v("onReceivedHttpAuthRequest ${view?.url} $realm, $host")
            if (handler != null) {
                Timber.v(
                    "onReceivedHttpAuthRequest - useHttpAuthUsernamePassword [${handler.useHttpAuthUsernamePassword()}]"
                )
                if (handler.useHttpAuthUsernamePassword()) {
                    val credentials =
                        view?.let {
                            webViewHttpAuthStore.getHttpAuthUsernamePassword(
                                it, host.orEmpty(), realm.orEmpty()
                            )
                        }

                    if (credentials != null) {
                        handler.proceed(credentials.username, credentials.password)
                    } else {
                        requestAuthentication(view, handler, host, realm)
                    }
                } else {
                    requestAuthentication(view, handler, host, realm)
                }
            } else {
                super.onReceivedHttpAuthRequest(view, handler, host, realm)
            }
        } catch (e: Throwable) {
            appCoroutineScope.launch(dispatcherProvider.default()) {
                uncaughtExceptionRepository.recordUncaughtException(e, ON_HTTP_AUTH_REQUEST)
                throw e
            }
        }
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
        var trusted: CertificateValidationState = CertificateValidationState.UntrustedChain
        when (error.primaryError) {
            SSL_UNTRUSTED -> {
                Timber.d(
                    "The certificate authority ${error.certificate.issuedBy.dName} is not trusted"
                )
                trusted = trustedCertificateStore.validateSslCertificateChain(error.certificate)
            }
            else -> Timber.d("SSL error ${error.primaryError}")
        }

        Timber.d("The certificate authority validation result is $trusted")
        if (trusted is CertificateValidationState.TrustedChain) handler.proceed()
        else super.onReceivedSslError(view, handler, error)
    }

    private fun requestAuthentication(
        view: WebView?,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?
    ) {
        webViewClientListener?.let {
            Timber.v("showAuthenticationDialog - $host, $realm")

            val siteURL =
                if (view?.url != null) "${URI(view.url).scheme}://$host" else host.orEmpty()

            val request =
                BasicAuthenticationRequest(
                    handler = handler,
                    host = host.orEmpty(),
                    realm = realm.orEmpty(),
                    site = siteURL
                )

            it.requiresAuthentication(request)
        }
    }
}
