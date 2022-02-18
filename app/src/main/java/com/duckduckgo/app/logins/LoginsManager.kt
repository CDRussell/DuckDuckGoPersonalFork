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

import android.os.Parcelable
import androidx.core.net.toUri
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.global.domain
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber

class LoginsManager(dispatcherProvider: DispatcherProvider, appCoroutineScope: CoroutineScope) {

    init {
        Timber.e("Creating new instance of LoginsManager: %s", this)
    }
    // temporary hacky, in-memory data store
    private val dataStore = HashMap<String, List<Credentials>>()

    fun getCredentials(url: String): List<Credentials> {
        Timber.i("Checking if credentials stored for %s", url)

        val originRule = determineOrigin(url)

        val savedCredentials = dataStore[originRule]
        if (savedCredentials.isNullOrEmpty()) {
            Timber.d("No credentials stored for %s", originRule)
            return emptyList()
        }

        return savedCredentials
    }

    fun saveCredentials(url: String, username: String, password: String?) {
        Timber.i("Saving credentials for %s. username:%s", url, username)
        if (username.isEmpty() && password.isNullOrEmpty()) return

        val originRule = determineOrigin(url)

        val creds = Credentials(username, password)
        val existingCredentialsForUrl = dataStore[originRule]
        if (existingCredentialsForUrl == null) {
            dataStore[originRule] = listOf(creds)
        } else {
            val newList = mutableListOf<Credentials>().also {
                it.addAll(existingCredentialsForUrl)
                it.add(creds)
            }
            dataStore[originRule] = newList
        }
    }

    private fun determineOrigin(url: String): String {
        val uri = url.toUri()
        val domain = uri.domain()
        val scheme = uri.scheme
        return "$scheme://$domain"
    }
}

@Parcelize
data class Credentials(val username: String, val password: String? = null) : Parcelable
