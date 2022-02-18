/*
 * Copyright (c) 2020 DuckDuckGo
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

package com.duckduckgo.app.email

import android.content.Context
import android.webkit.WebView
import androidx.annotation.UiThread
import com.duckduckgo.app.browser.DuckDuckGoUrlDetector
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.email.EmailJavascriptInterface.Companion.JAVASCRIPT_INTERFACE_NAME
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.logins.AutofillCredentials
import com.duckduckgo.app.logins.Credentials
import com.duckduckgo.app.logins.LoginsManager
import com.squareup.moshi.Moshi
import java.io.BufferedReader
import timber.log.Timber

interface EmailInjector {
    fun injectEmailAutofillJs(webView: WebView, url: String?)

    fun addJsInterface(
        webView: WebView,
        onTooltipShown: () -> Unit,
        onCredentialsTooltipShown: () -> Unit,
    )

    @UiThread fun injectAddressInEmailField(webView: WebView, alias: String?)

    @UiThread fun injectCredentialsInField(webView: WebView, credentials: Credentials)
}

class EmailInjectorJs(
    private val emailManager: EmailManager,
    private val loginsManager: LoginsManager,
    private val urlDetector: DuckDuckGoUrlDetector,
    private val dispatcherProvider: DispatcherProvider
) : EmailInjector {
    private val javaScriptInjector = JavaScriptInjector()

    override fun addJsInterface(
        webView: WebView,
        onTooltipShown: () -> Unit,
        onCredentialsTooltipShown: () -> Unit
    ) {
        webView.addJavascriptInterface(
            EmailJavascriptInterface(
                emailManager,
                loginsManager,
                webView,
                urlDetector,
                dispatcherProvider,
                onTooltipShown,
                onCredentialsTooltipShown
            ),
            JAVASCRIPT_INTERFACE_NAME
        )
    }

    @UiThread
    override fun injectEmailAutofillJs(webView: WebView, url: String?) {
        Timber.e("injectEmailAutofillJs")
        // if (isDuckDuckGoUrl(url) || emailManager.isSignedIn()) {
        webView.evaluateJavascript("javascript:${javaScriptInjector.getFunctionsJS()}", null)
        // }
    }

    @UiThread
    override fun injectAddressInEmailField(webView: WebView, alias: String?) {
        webView.evaluateJavascript(
            "javascript:${javaScriptInjector.getAliasFunctions(webView.context, alias)}", null
        )
    }

    @UiThread
    override fun injectCredentialsInField(webView: WebView, credentials: Credentials) {
        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(AutofillCredentials.AutoFillResponse::class.java)

        val credsResponse =
            AutofillCredentials.AutofillResponseCredentials(
                username = credentials.username, password = credentials.password
            )
        val response = AutofillCredentials.AutoFillResponse(credsResponse)
        val json = adapter.toJson(response)

        val js = javaScriptInjector.getCredentialsFunctions(webView.context, json)
        webView.evaluateJavascript("javascript:$js", null)
    }

    private fun isDuckDuckGoUrl(url: String?): Boolean =
        (url != null && urlDetector.isDuckDuckGoEmailUrl(url))

    private class JavaScriptInjector {
        private lateinit var functions: String
        private lateinit var aliasFunctions: String
        private lateinit var credentialsFunctions: String

        fun getFunctionsJS(): String {
            if (!this::functions.isInitialized) {
                functions = loadJs("autofill.js")
            }
            return functions
        }

        fun getAliasFunctions(context: Context, alias: String?): String {
            if (!this::aliasFunctions.isInitialized) {
                aliasFunctions =
                    context.resources.openRawResource(R.raw.inject_alias).bufferedReader().use {
                        it.readText()
                    }
            }
            return aliasFunctions.replace("%s", alias.orEmpty())
        }

        fun getCredentialsFunctions(context: Context, responseJson: String): String {
            if (!this::credentialsFunctions.isInitialized) {
                credentialsFunctions =
                    context
                        .resources
                        .openRawResource(R.raw.inject_credentials)
                        .bufferedReader()
                        .use { it.readText() }
            }
            return credentialsFunctions.replace("%s", responseJson)
        }

        fun loadJs(resourceName: String): String =
            readResource(resourceName).use { it?.readText() }.orEmpty()

        private fun readResource(resourceName: String): BufferedReader? {
            return javaClass.classLoader?.getResource(resourceName)?.openStream()?.bufferedReader()
        }
    }
}
