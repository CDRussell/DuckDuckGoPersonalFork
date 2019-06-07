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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.global.DuckDuckGoActivity
import kotlinx.android.synthetic.main.activity_offline_pages.*
import kotlinx.android.synthetic.main.content_offline_pages.*
import kotlinx.android.synthetic.main.view_offline_pages.view.*

class OfflinePagesActivity : DuckDuckGoActivity() {

    lateinit var adapter: OfflinePageAdapter
    private val viewModel: OfflinePageViewModel by bindViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_pages)
        setSupportActionBar(toolbar)
        configureList()
        observeViewModel()
    }

    private fun configureList() {
        adapter = OfflinePageAdapter(viewModel)
        list.adapter = adapter
    }

    private fun observeViewModel() {
        viewModel.viewState.observe(this, Observer<OfflinePageViewModel.ViewState> { viewState ->
            viewState?.let {
                adapter.updatePages(it.pages)
            }
        })
        viewModel.command.observe(this, Observer {command ->
            when(command) {
                is OfflinePageViewModel.Command.LoadOfflinePage -> {
                    val data = Intent()
                    data.putExtra("offlinePageId", command.offlinePage.id)
                    setResult(Activity.RESULT_OK, data)
                    finish()
                }
            }
        })
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, OfflinePagesActivity::class.java)
        }
    }

}

class OfflinePageAdapter(val viewModel: OfflinePageViewModel) : RecyclerView.Adapter<OfflinePageViewHolder>() {

    private val offlinePages = mutableListOf<OfflinePageEntity>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OfflinePageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.view_offline_pages, parent, false)
        return OfflinePageViewHolder(viewModel, view)
    }

    override fun getItemCount(): Int = offlinePages.size

    override fun onBindViewHolder(holder: OfflinePageViewHolder, position: Int) {
        holder.update(offlinePages[position])
    }

    fun updatePages(pages: List<OfflinePageEntity>) {
        offlinePages.clear()
        offlinePages.addAll(pages)
        notifyDataSetChanged()
    }
}

class OfflinePageViewHolder(val viewModel: OfflinePageViewModel, itemView: View) : RecyclerView.ViewHolder(itemView) {

    fun update(offlinePage: OfflinePageEntity) {
        itemView.overflowMenu.contentDescription = "More options for ${offlinePage.title}"
        itemView.overflowMenu.setOnClickListener { showOverflowMenu(itemView.overflowMenu, offlinePage) }

        itemView.title.text = offlinePage.title
        itemView.url.text = offlinePage.url

        itemView.setOnClickListener {
            viewModel.onSelected(offlinePage)
        }
    }

    private fun showOverflowMenu(overflowMenu: ImageView, offlinePage: OfflinePageEntity) {
        val popup = PopupMenu(overflowMenu.context, overflowMenu)
        popup.inflate(R.menu.offline_pages_individual_overflow_menu)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.delete -> {
                    deleteOfflinePage(offlinePage); true
                }
                else -> false

            }
        }
        popup.show()
    }

    private fun deleteOfflinePage(offlinePage: OfflinePageEntity) {
        viewModel.deletePage(offlinePage)
    }
}