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

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.duckduckgo.app.browser.DuckDuckGoUrlDetector
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.logins.AutofillInit
import com.duckduckgo.app.logins.LoginsManager
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber

class EmailJavascriptInterface(
    private val emailManager: EmailManager,
    private val loginsManager: LoginsManager,
    private val webView: WebView,
    private val urlDetector: DuckDuckGoUrlDetector,
    private val dispatcherProvider: DispatcherProvider,
    private val showNativeTooltip: () -> Unit,
    private val showCredentialsTooltip: () -> Unit
) {

    val moshi: Moshi = Moshi.Builder().build()
    val jsonAdapter: JsonAdapter<AutofillInit.AutoFillResponse> =
        moshi.adapter(AutofillInit.AutoFillResponse::class.java)

    private fun isUrlFromDuckDuckGoEmail(): Boolean {
        return runBlocking(dispatcherProvider.main()) {
            val url = webView.url
            (url != null && urlDetector.isDuckDuckGoEmailUrl(url))
        }
    }

    @JavascriptInterface
    fun isSignedIn(): String {
        Timber.e("isSignedIn")
        return if (isUrlFromDuckDuckGoEmail()) {
            emailManager.isSignedIn().toString()
        } else {
            ""
        }
    }

    @JavascriptInterface
    fun getAutofillInitData(): String {
        Timber.e("getAutofillInitData called")

        return runBlocking {
            val currentPage = withContext(dispatcherProvider.main()) {
                webView.url ?: noSavedCredentialsResponse().asJson()
            }

            val existingCredentials = loginsManager.getCredentials(currentPage)
            if (existingCredentials.isEmpty()) return@runBlocking noSavedCredentialsResponse()

            val success = AutofillInit.SuccessfulAutoFillResponse(listOf(AutofillInit.AutofillResponseCredentials(username = "")))
            return@runBlocking AutofillInit.AutoFillResponse(success)
        }.asJson()
    }

    @JavascriptInterface
    fun storeFormData() {
        Timber.e("storeFormData")
    }

    private fun noSavedCredentialsResponse() = AutofillInit.AutoFillResponse()

    fun AutofillInit.AutoFillResponse.asJson(): String {
        return jsonAdapter.toJson(this).also { Timber.i("getAutofillInitData response:\n%s", it) }
    }

    /**
     * This is a trigger for us to retrieve existing autofill credentials if they exist. This
     * function returns immediately. If there are credentials to share, that is sent to the WebView
     * as a separate JS command.
     */
    @JavascriptInterface
    fun getAutofillCredentials() {
        Timber.e("getAutofillCredentials called")
        showCredentialsTooltip()
    }

    @JavascriptInterface
    fun storeCredentials(token: String, username: String, cohort: String) {
        Timber.e("storeCredentials")
        if (isUrlFromDuckDuckGoEmail()) {
            emailManager.storeCredentials(token, username, cohort)
        }
    }

    @JavascriptInterface
    fun showTooltip() {
        showNativeTooltip()
    }

    companion object {
        const val JAVASCRIPT_INTERFACE_NAME = "EmailInterface"
    }
}
