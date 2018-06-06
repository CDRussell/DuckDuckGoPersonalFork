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

import android.app.AlertDialog
import android.app.ProgressDialog
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.RecyclerView.*
import android.text.Html
import android.view.*
import android.widget.ImageView
import android.widget.PopupMenu
import com.duckduckgo.app.bookmarks.db.BookmarkEntity
import com.duckduckgo.app.browser.BrowserActivity
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.global.DuckDuckGoActivity
import com.duckduckgo.app.global.ViewModelFactory
import com.duckduckgo.app.global.baseHost
import com.duckduckgo.app.global.faviconLocation
import com.duckduckgo.app.global.image.GlideApp
import com.duckduckgo.app.global.view.gone
import com.duckduckgo.app.global.view.show
import kotlinx.android.synthetic.main.content_bookmarks.*
import kotlinx.android.synthetic.main.include_toolbar.*
import kotlinx.android.synthetic.main.view_bookmark_entry.view.*
import org.jetbrains.anko.alert
import org.jetbrains.anko.indeterminateProgressDialog
import timber.log.Timber
import javax.inject.Inject

class BookmarksActivity : DuckDuckGoActivity(), ImportBookmarksEnterKeyDialogFragment.Listener {


    @Inject
    lateinit var viewModelFactory: ViewModelFactory
    lateinit var adapter: BookmarksAdapter
    private var deleteDialog: AlertDialog? = null

    private lateinit var progress: ProgressDialog

    private val viewModel: BookmarksViewModel by lazy {
        ViewModelProviders.of(this, viewModelFactory).get(BookmarksViewModel::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmarks)
        progress = indeterminateProgressDialog(message = "Please wait a moment", title = "Downloading bookmarks")
        setupActionBar()
        setupBookmarksRecycler()
        observeViewModel()

        consumeDeepLink()
    }

    private fun consumeDeepLink() {
        Timber.i("Checking if deep link exists")

        val action: String? = intent.action
        val data: Uri? = intent.data
        val key = intent.data?.authority

        Timber.i("Action: $action, data: $data, key: $key")

        if (action != null && data != null && key != null) {
            viewModel.onBookmarkImportKeyEntered(key)
        }
    }

    private fun setupBookmarksRecycler() {
        adapter = BookmarksAdapter(applicationContext, viewModel)
        recycler.adapter = adapter

        val separator = DividerItemDecoration(this, VERTICAL)
        recycler.addItemDecoration(separator)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.bookmarks_global_overflow_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.importBookmarks -> {
                importBookmarks()
                true
            }
            R.id.exportBookmarks -> {
                exportBookmarks()
                true
            }
            else -> false
        }
    }

    private fun exportBookmarks() {
        Timber.i("Will export bookmarks")
    }

    private fun importBookmarks() {
        Timber.i("Will import bookmarks")
        val importDialog = ImportBookmarksEnterKeyDialogFragment()
        importDialog.listener = this
        importDialog.show(supportFragmentManager, IMPORT_BOOKMARK_FRAGMENT_TAG)
    }

    override fun onBookmarkImportKeyEntered(key: String) {
        viewModel.onBookmarkImportKeyEntered(key)
    }

    private fun setupActionBar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun observeViewModel() {
        viewModel.viewState.observe(this, Observer<BookmarksViewModel.ViewState> { viewState ->
            viewState?.let {
                if (it.showBookmarks) showBookmarks() else hideBookmarks()
                adapter.bookmarks = it.bookmarks

                if (it.isWorking) {
                    progress.show()
                } else {
                    progress.hide()
                }
            }
        })

        viewModel.command.observe(this, Observer {
            when (it) {
                is BookmarksViewModel.Command.ConfirmDeleteBookmark -> confirmDeleteBookmark(it.bookmark)
                is BookmarksViewModel.Command.OpenBookmark -> openBookmark(it.bookmark)
                is BookmarksViewModel.Command.ShowEditBookmark -> showEditBookmarkDialog(it.bookmark)
            }
        })
    }

    private fun showEditBookmarkDialog(bookmark: BookmarkEntity) {
        val dialog = SaveBookmarkDialogFragment.createDialogEditingMode(bookmark)
        dialog.show(supportFragmentManager, EDIT_BOOKMARK_FRAGMENT_TAG)
        dialog.listener = viewModel
    }

    private fun showBookmarks() {
        recycler.show()
        emptyBookmarks.gone()
    }

    private fun hideBookmarks() {
        recycler.gone()
        emptyBookmarks.show()
    }

    private fun openBookmark(bookmark: BookmarkEntity) {
        startActivity(BrowserActivity.intent(this, bookmark.url))
        finish()
    }

    @Suppress("deprecation")
    private fun confirmDeleteBookmark(bookmark: BookmarkEntity) {
        val message =
            Html.fromHtml(getString(R.string.bookmarkDeleteConfirmMessage, bookmark.title))
        val title = getString(R.string.bookmarkDeleteConfirmTitle)
        deleteDialog = alert(message, title) {
            positiveButton(android.R.string.yes) { delete(bookmark) }
            negativeButton(android.R.string.no) { }
        }.build()
        deleteDialog?.show()
    }

    private fun delete(bookmark: BookmarkEntity) {
        viewModel.delete(bookmark)
    }

    override fun onDestroy() {
        deleteDialog?.dismiss()
        progress.dismiss()
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, BookmarksActivity::class.java)
        }

        // Fragment Tags
        private const val EDIT_BOOKMARK_FRAGMENT_TAG = "EDIT_BOOKMARK"
        private const val IMPORT_BOOKMARK_FRAGMENT_TAG = "IMPORT_BOOKMARK"
        private const val EXPORT_BOOKMARK_FRAGMENT_TAG = "EXPORT_BOOKMARK"

    }

    class BookmarksAdapter(
        private val context: Context,
        private val viewModel: BookmarksViewModel
    ) : Adapter<BookmarksViewHolder>() {

        var bookmarks: List<BookmarkEntity> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarksViewHolder {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.view_bookmark_entry, parent, false)
            return BookmarksViewHolder(view, viewModel)
        }

        override fun onBindViewHolder(holder: BookmarksViewHolder, position: Int) {
            holder.update(bookmarks[position])
        }

        override fun getItemCount(): Int {
            return bookmarks.size
        }
    }

    class BookmarksViewHolder(itemView: View?, private val viewModel: BookmarksViewModel) :
        ViewHolder(itemView) {

        lateinit var bookmark: BookmarkEntity

        fun update(bookmark: BookmarkEntity) {
            this.bookmark = bookmark

            itemView.overflowMenu.contentDescription = itemView.context.getString(
                R.string.bookmarkOverflowContentDescription,
                bookmark.title
            )

            itemView.title.text = bookmark.title
            itemView.url.text = parseDisplayUrl(bookmark.url)
            loadFavicon(bookmark.url)

            itemView.overflowMenu.setOnClickListener {
                showOverFlowMenu(itemView.overflowMenu, bookmark)
            }

            itemView.setOnClickListener {
                viewModel.onSelected(bookmark)
            }
        }

        private fun loadFavicon(url: String) {
            val faviconUrl = Uri.parse(url).faviconLocation()

            GlideApp.with(itemView)
                .load(faviconUrl)
                .placeholder(R.drawable.ic_globe_white_16dp)
                .error(R.drawable.ic_globe_white_16dp)
                .into(itemView.favicon)
        }

        private fun parseDisplayUrl(urlString: String): String {
            val uri = Uri.parse(urlString)
            return uri.baseHost ?: return urlString
        }

        private fun showOverFlowMenu(overflowMenu: ImageView, bookmark: BookmarkEntity) {
            val popup = PopupMenu(overflowMenu.context, overflowMenu)
            popup.inflate(R.menu.bookmarks_individual_overflow_menu)
            popup.setOnMenuItemClickListener {
                when (it.itemId) {

                    R.id.edit -> {
                        editBookmark(bookmark); true
                    }
                    R.id.delete -> {
                        deleteBookmark(bookmark); true
                    }
                    else -> false

                }
            }
            popup.show()
        }


        private fun editBookmark(bookmark: BookmarkEntity) {
            Timber.i("Editing bookmark ${bookmark.title}")
            viewModel.onEditBookmarkRequested(bookmark)
        }

        private fun deleteBookmark(bookmark: BookmarkEntity) {
            Timber.i("Deleting bookmark ${bookmark.title}")
            viewModel.onDeleteRequested(bookmark)
        }
    }
}