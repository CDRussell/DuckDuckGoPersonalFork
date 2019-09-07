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

import android.content.Intent
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.global.image.GlideApp
import timber.log.Timber


class InstalledAppAdapter(
    private val installedAppSelectionListener: InstalledAppSelectionListener,
    private val packageManager: PackageManager
) :
    ListAdapter<InstalledApp, InstalledAppViewHolder>(object : DiffUtil.ItemCallback<InstalledApp>() {

        override fun areItemsTheSame(oldItem: InstalledApp, newItem: InstalledApp): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: InstalledApp, newItem: InstalledApp): Boolean {
            return oldItem == newItem
        }

    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InstalledAppViewHolder {
        val root = LayoutInflater.from(parent.context).inflate(R.layout.installed_app_list_item, parent, false)
        val activityName = root.findViewById<TextView>(R.id.activityName)
        val appIcon = root.findViewById<ImageView>(R.id.appIcon)
        return InstalledAppViewHolder(root, activityName, appIcon)
    }

    override fun onBindViewHolder(holder: InstalledAppViewHolder, position: Int) {
        val app = getItem(position)

        //  package: com.android.camera2, activity: com.android.camera.app.CameraApp
        Timber.i("Binding view holder. package: ${app.packageName}, activity: ${app.fullActivityName}")

        holder.root.setOnClickListener { installedAppSelectionListener.selectedApp(app) }

        try {
            //val bitmap = packageManager.getActivityIcon(ComponentName(app.packageName, app.fullActivityName))
            val bitmap = packageManager.getActivityIcon(app.launchIntent)
            val glide = GlideApp.with(holder.root)
            glide.load(bitmap)
                .into(holder.appIcon)
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.w(e, "Name not found")
        }


        holder.activityName.text = app.shortActivityName
    }

}

data class InstalledAppViewHolder(val root: View, val activityName: TextView, val appIcon: ImageView) : RecyclerView.ViewHolder(root)

data class InstalledApp(
    val shortActivityName: String,
    val fullActivityName: String,
    val packageName: String,
    val launchIntent: Intent
)

interface InstalledAppSelectionListener {
    fun selectedApp(app: InstalledApp)
}
