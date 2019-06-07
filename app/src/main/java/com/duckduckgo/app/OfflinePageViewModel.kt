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

package com.duckduckgo.app

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import com.duckduckgo.app.global.SingleLiveEvent
import timber.log.Timber
import kotlin.concurrent.thread


class OfflinePageViewModel(val dao: OfflinePageDao) : ViewModel() {

    data class ViewState(val pages: List<OfflinePageEntity> = emptyList())

    sealed class Command {
        data class LoadOfflinePage(val offlinePage: OfflinePageEntity) : Command()
    }

    val viewState: MutableLiveData<ViewState> = MutableLiveData()
    val command: SingleLiveEvent<Command> = SingleLiveEvent()

    private val offlinePageObserver = Observer<List<OfflinePageEntity>> { it?.let { onListChanged(it) } }

    private val currentViewState: ViewState
        get() = viewState.value!!

    init {
        viewState.value = ViewState()
        dao.getAll().observeForever(offlinePageObserver)
    }

    private fun onListChanged(pages: List<OfflinePageEntity>) {
        Timber.i("There are ${pages.size} offline pages")
        viewState.value = currentViewState.copy(pages = pages)
    }

    fun deletePage(page: OfflinePageEntity) {
        thread { dao.delete(page.id) }
    }

    fun onSelected(offlinePage: OfflinePageEntity) {
        command.value = Command.LoadOfflinePage(offlinePage)
    }


}