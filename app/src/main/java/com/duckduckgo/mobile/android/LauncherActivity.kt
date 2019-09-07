/*
 * Copyright (c) 2019 DuckDuckGo
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

package com.duckduckgo.mobile.android

import android.app.SearchManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.duckduckgo.app.SelectedTextSearchActivity
import com.duckduckgo.app.autocomplete.api.AutoCompleteApi
import com.duckduckgo.app.autocomplete.api.AutoCompleteApi.AutoCompleteSuggestion
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.autocomplete.BrowserAutoCompleteSuggestionsAdapter
import com.duckduckgo.app.global.DuckDuckGoActivity
import com.duckduckgo.app.global.isAndroidMainThread
import com.duckduckgo.app.privacy.db.PrivacyProtectionCountDao
import kotlinx.android.synthetic.main.activity_launcher.*
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.*
import javax.inject.Inject

class LauncherActivity(private val mainScope: CoroutineScope = MainScope()) : DuckDuckGoActivity() {

    private var debounceJob: Job = Job()

    @Inject
    lateinit var autoCompleteApi: AutoCompleteApi

    @Inject
    lateinit var privacyProtectionCountDao: PrivacyProtectionCountDao

    private lateinit var trackersBlockedStatView: TextView
    private lateinit var httpsUpgrades: TextView

    private val jobExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.w(throwable, "Job failed")
    }

    private val installedAppSelectionListener = object : InstalledAppSelectionListener {
        override fun selectedApp(app: InstalledApp) {
            Timber.i("package: ${app.packageName} - app: ${app.shortActivityName}")
            startActivity(app.launchIntent)
        }

    }

    private val installedAppAdapter: InstalledAppAdapter by lazy { InstalledAppAdapter(installedAppSelectionListener, packageManager) }
    private val autocompleteAdapter = BrowserAutoCompleteSuggestionsAdapter({ launchDuckDuckGoAppWithQuery(it.phrase) }, {})

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        trackersBlockedStatView = findViewById(R.id.trackersBlocked)
        httpsUpgrades = findViewById(R.id.httpsUpgrades)

        val inputText: EditText = findViewById(R.id.searchInputBox)
        inputText.doAfterTextChanged { text ->
            val query = text.toString()
            performSearch(query)
        }

        inputText.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, keyEvent ->
            if (actionId == EditorInfo.IME_ACTION_GO || keyEvent?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val query = inputText.text.toString()
                launchDuckDuckGoAppWithQuery(query)


                return@OnEditorActionListener true
            }
            false
        })

        val installedAppsRecycler: RecyclerView = findViewById(R.id.installedAppsRecycler)
        installedAppsRecycler.adapter = installedAppAdapter

        val autcompleteRecycler: RecyclerView = findViewById(R.id.autoCompleteRecycler)
        autcompleteRecycler.adapter = autocompleteAdapter
    }


    override fun onStart() {
        super.onStart()
        Timber.i("onStart")

        mainScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val trackersBlocked = privacyProtectionCountDao.getTrackersBlockedCount()
                val upgradeCount = privacyProtectionCountDao.getUpgradeCount()
                Pair(trackersBlocked, upgradeCount)
            }

            Timber.i("Updating ui with $stats on main thread? ${Thread.currentThread().isAndroidMainThread()}")
            trackersBlockedStatView.text = "Trackers Blocked: ${stats.first}"
            httpsUpgrades.text = "HTTPS Upgrades: ${stats.second}"
        }
    }

    private fun launchDuckDuckGoAppWithQuery(query: String) {
        val intent = Intent(this, SelectedTextSearchActivity::class.java)
        intent.putExtra(SearchManager.QUERY, query)
        startActivity(intent)
    }

    private fun performSearch(query: String) {
        Timber.i("Performing search for: $query")

        if (debounceJob.isActive) {
            Timber.i("Cancelling existing job")
            debounceJob.cancel()
        }

        debounceJob = mainScope.launch(jobExceptionHandler) {
            val apps = if (query.isEmpty()) {
                emptyList()
            } else {
                delay(300)
                queryInstalledApps(query)

            }

            val autocompleteResults = if(query.isEmpty()) {
                emptyList()
            } else {
                queryWebAutocomplete(query)
            }

            withContext(Dispatchers.Main) {
                installedAppAdapter.submitList(apps)
                autocompleteAdapter.updateData(autocompleteResults)
                autoCompleteRecycler.smoothScrollToPosition(0)
            }


            //queryInstalledApps2(query)
            //queryInstalledApps3(query)
        }
    }

    private suspend fun queryWebAutocomplete(query: String): List<AutoCompleteSuggestion> {
        return withContext(Dispatchers.IO) {
            val list = autoCompleteApi.autoComplete(query).blockingFirst().suggestions

            //val list = autoCompleteService.autoCompleteCo(query).map { AutoCompleteSuggestion.AutoCompleteSearchSuggestion(it.phrase, false) }
            return@withContext list.take(6)
        }
    }

    private suspend fun queryInstalledApps(query: String): List<InstalledApp> {
        return withContext(Dispatchers.Default) {
            val intent = Intent(Intent.ACTION_MAIN)
            val resInfos = packageManager.queryIntentActivities(intent, 0)

            val mainPackages = mutableSetOf<String>()
            val appInfos = mutableListOf<ApplicationInfo>()

            for (resInfo in resInfos) {
                val packageName = resInfo.activityInfo.packageName
                //val activityName = resInfo.activityInfo.name
                mainPackages.add(packageName)
            }

            for (packageName in mainPackages) {
                appInfos.add(packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA))
            }

            Collections.sort(appInfos, ApplicationInfo.DisplayNameComparator(packageManager))

            Timber.i("Found ${appInfos.size} matching apps")

            val appsList: List<InstalledApp> = appInfos.map {

                val shortName = packageManager.getApplicationLabel(it).toString()
                val packageName = it.packageName
                val fullActivityName = packageManager.getApplicationInfo(packageName, 0).className
                Timber.i("Short name: $shortName, package: $packageName, full activity name: $fullActivityName")

                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (shortName.contains(query, ignoreCase = true) && fullActivityName != null && launchIntent != null) {
                    return@map InstalledApp(shortName, fullActivityName, packageName, launchIntent)
                } else {
                    return@map null
                }
            }
                .filterNotNull()
                .toList()

            Timber.i("Found ${appsList.size} matching Activities")

            return@withContext appsList
        }
    }

    private suspend fun queryInstalledApps2(query: String): List<InstalledApp> {
        return withContext(Dispatchers.Default) {

            val matching = mutableSetOf<InstalledApp>()
            val packages = packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            for (packageInfo in packages) {
                val activities = packageInfo.activities
                if (activities.isNullOrEmpty()) continue

                for (activity in activities) {
                    val activityName = activity.name.substringAfterLast(".")
                    Timber.v("Found Activity: $activityName, full name: ${activity.name}, package: ${activity.packageName}")

                    if (activity.packageName.contains(query, ignoreCase = true)) {
                        Timber.d("Found Matching Activity: $activityName, full name: ${activity.name}, package: ${activity.packageName}")
                        val launchIntent = packageManager.getLaunchIntentForPackage(activity.packageName)
                        if (launchIntent == null) {
                            Timber.w("No launch intent found")
                            continue
                        }
                        val installedApp = InstalledApp(
                            shortActivityName = activityName,
                            fullActivityName = activity.name,
                            packageName = activity.packageName,
                            launchIntent = launchIntent
                        )
                        matching.add(installedApp)
                    }
                }
            }

            Timber.i(
                "Found ${matching.size} matching Activities matching $query. ${matching.joinToString(
                    separator = "\n\t",
                    transform = { it.shortActivityName })} "
            )

            return@withContext matching.toList()
        }
    }

    private suspend fun queryInstalledApps3(query: String) {
        withContext(Dispatchers.Default) {


            //            val apps = packageManager.getInstalledApplications(PackageManager.GET_GIDS)
//            val matchingAppsLaunchIntents = mutableSetOf<Intent>()
//
//            apps.asSequence()
//                .forEach {
//                    val intent = packageManager.getLaunchIntentForPackage(it.packageName)
//                    if (intent != null) {
//                        matchingAppsLaunchIntents.add(intent)
//                    }
//                }
//
//
//            Timber.i(
//                "Found ${matchingAppsLaunchIntents.size} launchable Activities matching $query. ${matchingAppsLaunchIntents.joinToString(
//                    separator = "\n\t",rom
//                    transform = { it.component?.flattenToString() ?: "null" })} "
//            )

        }
    }
}
