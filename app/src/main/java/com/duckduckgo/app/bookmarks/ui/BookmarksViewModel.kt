/*
 * Copyright (c) 2018 DuckDuckGo
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

package com.duckduckgo.app.bookmarks.ui

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModel
import com.duckduckgo.app.bookmarks.api.BookmarkSyncService
import com.duckduckgo.app.bookmarks.db.BookmarkEntity
import com.duckduckgo.app.bookmarks.db.BookmarksDao
import com.duckduckgo.app.bookmarks.ui.BookmarksViewModel.Command.*
import com.duckduckgo.app.bookmarks.ui.SaveBookmarkDialogFragment.SaveBookmarkListener
import com.duckduckgo.app.global.SingleLiveEvent
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.io.IOException

class BookmarksViewModel(
    val dao: BookmarksDao,
    val bookmarkSyncService: BookmarkSyncService
) : SaveBookmarkListener, ViewModel(), ImportBookmarksEnterKeyDialogFragment.Listener {

    data class ViewState(
        val showBookmarks: Boolean = false,
        val bookmarks: List<BookmarkEntity> = emptyList(),
        val isWorking: Boolean = false
    )

    sealed class Command {

        class OpenBookmark(val bookmark: BookmarkEntity) : Command()
        class ConfirmDeleteBookmark(val bookmark: BookmarkEntity) : Command()
        class ShowEditBookmark(val bookmark: BookmarkEntity) : Command()

    }

    val viewState: MutableLiveData<ViewState> = MutableLiveData()
    val command: SingleLiveEvent<Command> = SingleLiveEvent()

    private val bookmarks: LiveData<List<BookmarkEntity>> = dao.bookmarks()
    private val bookmarksObserver = Observer<List<BookmarkEntity>> { onBookmarksChanged(it!!) }

    init {
        viewState.value = ViewState()
        bookmarks.observeForever(bookmarksObserver)
    }

    override fun onCleared() {
        super.onCleared()
        bookmarks.removeObserver(bookmarksObserver)
    }

    override fun onBookmarkSaved(id: Int?, title: String, url: String) {
        id?.let {
            editBookmark(it, title, url)
        }
    }

    private fun onBookmarksChanged(bookmarks: List<BookmarkEntity>) {
        viewState.value = viewState.value?.copy(showBookmarks = bookmarks.isNotEmpty(), bookmarks = bookmarks)
    }

    fun onSelected(bookmark: BookmarkEntity) {
        command.value = OpenBookmark(bookmark)
    }

    fun onDeleteRequested(bookmark: BookmarkEntity) {
        command.value = ConfirmDeleteBookmark(bookmark)
    }

    fun onEditBookmarkRequested(bookmark: BookmarkEntity) {
        command.value = ShowEditBookmark(bookmark)
    }

    override fun onBookmarkImportKeyEntered(key: String) {
        viewState.value = viewState.value!!.copy(isWorking = true)

        Timber.i("Received bookmark import key $key")
        val single: Single<BookmarkSyncService.BookmarkSyncResponse> = Single.fromCallable({
            val response = bookmarkSyncService.getBookmarks(key).execute()
            if (!response.isSuccessful) {
                throw IOException("Failed to obtain bookmarks - ${response.errorBody()?.string()}")
            }

            val body = response.body()
            if (body == null) {
                Timber.w("Response body was null")
                throw IOException("Response body was null")
            }

            return@fromCallable body
        })


        single
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doAfterTerminate({
                viewState.value = viewState.value!!.copy(isWorking = false)
            })
            .observeOn(Schedulers.io())
            .subscribe({
                Timber.i("Successfully retrieved ${it.bookmarks.size} bookmarks")
                it.bookmarks.forEach {
                    dao.insert(it)
                }
            }, { throwable ->
                Timber.w(throwable, "Failed to retrieve bookmarks")

            })

    }

    fun delete(bookmark: BookmarkEntity) {
        Schedulers.io().scheduleDirect {
            dao.delete(bookmark)
        }
    }

    private fun editBookmark(id: Int, title: String, url: String) {
        Schedulers.io().scheduleDirect {
            dao.update(BookmarkEntity(id, title, url))
        }
    }

}