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

import android.content.Context
import android.content.Intent
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE
import android.view.autofill.AutofillManager
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.duckduckgo.app.browser.databinding.ActivityAutofillConfigurationBinding
import com.duckduckgo.app.global.DuckDuckGoActivity
import com.duckduckgo.di.scopes.ActivityScope
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.mobile.android.ui.viewbinding.viewBinding
import com.google.android.material.snackbar.Snackbar
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.MergeSubcomponent
import dagger.Binds
import dagger.Module
import dagger.SingleInstanceIn
import dagger.Subcomponent
import dagger.android.AndroidInjector
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import timber.log.Timber

@RequiresApi(VERSION_CODES.O)
class AutofillConfigurationActivity : DuckDuckGoActivity() {

    private val changeAutofillProvider =
        registerForActivityResult(StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                Timber.i("We're set as the default")
            }
            configureCurrentAutofillSettingsViews()
        }

    val binding: ActivityAutofillConfigurationBinding by viewBinding()

    val configureAutofillServiceRequest = GetContent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        configureCurrentAutofillSettingsViews()
        binding.openSettings.setOnClickListener { launchAutofillSystemSettings() }
    }

    private fun configureCurrentAutofillSettingsViews() {
        val enabled = determineIfAutofillEnabled()
        binding.autofillServiceActivated.text = String.format("DDG Autofill enabled: %s", enabled)
        if (enabled) {
            Snackbar.make(binding.root, "Logins+ is the default provider", Snackbar.LENGTH_LONG)
                .show()
        }
    }

    private fun launchAutofillSystemSettings() {
        val intent =
            Intent(ACTION_REQUEST_SET_AUTOFILL_SERVICE).also {
                it.data = "package:$packageName".toUri()
            }
        changeAutofillProvider.launch(intent)
    }

    private fun determineIfAutofillEnabled(): Boolean {
        val autofillManager = getSystemService(AutofillManager::class.java) as AutofillManager
        return autofillManager.hasEnabledAutofillServices().also {
            Timber.i("Our Autofill service enabled: %s", it)
        }
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, AutofillConfigurationActivity::class.java)
        }
    }
}

@SingleInstanceIn(ActivityScope::class)
@MergeSubcomponent(scope = ActivityScope::class)
interface AutofillConfigurationActivityComponent : AndroidInjector<AutofillConfigurationActivity> {
    @Subcomponent.Factory
    interface Factory : AndroidInjector.Factory<AutofillConfigurationActivity>
}

@ContributesTo(AppScope::class)
interface AutofillConfigurationActivityComponentProvider {
    fun provideAutofillConfigurationActivityComponentFactory():
        AutofillConfigurationActivityComponent.Factory
}

@Module
@ContributesTo(AppScope::class)
abstract class AutofillConfigurationActivityBindingModule {
    @Binds
    @IntoMap
    @ClassKey(AutofillConfigurationActivity::class)
    abstract fun AutofillConfigurationActivityComponent.Factory.bind(): AndroidInjector.Factory<*>
}
