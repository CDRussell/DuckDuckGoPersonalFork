/*
 * Copyright (c) 2022 DuckDuckGo
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

package com.duckduckgo.app.logins

import java.io.IOException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import timber.log.Timber

class NativeFormDetector {

    suspend fun containsLoginForm(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            val html: Document = downloadHtml(url) ?: return@withContext false
            Timber.v("Retrieved HTML from %s\n%s", url, html.toString())
            return@withContext htmlContainsForm(html)
        }
    }

    private suspend fun htmlContainsForm(document: Document): Boolean {
        return withContext(Dispatchers.Default) {
            with(document.body()) {
                val inputTypes = select(INPUT_TYPE_SELECTOR)
                val submitButton = select(SUBMIT_BUTTON_SELECTOR)
                val formLikely = inputTypes.isNotEmpty() && submitButton.isNotEmpty()

                Timber.i(
                    "Found %d input types and %d submit buttons. hasForm: %s",
                    inputTypes.size,
                    submitButton.size,
                    formLikely
                )

                formLikely
            }
        }
    }

    private suspend fun downloadHtml(url: String): Document? {
        return withContext(Dispatchers.IO) {
            try {
                return@withContext Jsoup.parse(URL(url), 1000)
            } catch (e: IOException) {
                Timber.w(e, "%s is not HTML content", url)
                return@withContext null
            }
        }
    }

    companion object {
        private const val INPUT_TYPE_SELECTOR =
            "\ninput:not([type=submit]):not([type=button]):not([type=checkbox]):not([type=radio]):not([type=hidden]):not([type=file]),\nselect"
        private const val SUBMIT_BUTTON_SELECTOR =
            "\ninput[type=submit],\ninput[type=button],\nbutton:not([role=switch]):not([role=link]),\n[role=button]"
    }
}
