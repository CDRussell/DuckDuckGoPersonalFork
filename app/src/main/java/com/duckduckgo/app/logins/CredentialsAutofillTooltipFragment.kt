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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.duckduckgo.app.browser.R
import com.duckduckgo.mobile.android.ui.view.toPx
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import timber.log.Timber

class CredentialsAutofillTooltipFragment private constructor() : BottomSheetDialogFragment() {

    private lateinit var recyclerView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.setOnShowListener {
            if (isForIFrame()) {
                val d = it as BottomSheetDialog
                val sheet: FrameLayout = d.findViewById(com.google.android.material.R.id.design_bottom_sheet)!!
                d.findViewById<TextView>(R.id.shareCredentialsTitle)?.text = "login form detected"
                BottomSheetBehavior.from(sheet).setPeekHeight(60.toPx(), true)
            }
        }

        return inflater.inflate(R.layout.content_autofill_credentials_tooltip, container, false)
    }

    fun doOrDoNot(function: ()->Unit) {
        function()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.availableCredentialsRecycler)
        recyclerView.adapter =
            CredentialsAdapter(getAvailableCredentials()) { selectedCredentials ->
                val result =
                    Bundle().also {
                        it.putString("url", getOriginalUrl())
                        it.putParcelable("cred", selectedCredentials)
                    }
                parentFragmentManager.setFragmentResult(RESULT_KEY, result)
            }
    }

    private fun getAvailableCredentials() =
        arguments?.getParcelableArrayList<Credentials>("creds")!!
    private fun getOriginalUrl() = arguments?.getString("url")!!
    private fun isForIFrame() = arguments?.getBoolean("iframe")!!

    companion object {
        const val TAG = "CredentialsBottomSheet"
        const val RESULT_KEY = "CredentialsAutofillTooltipFragment"

        fun instance(
            url: String,
            credentials: List<Credentials>,
            forIFrame: Boolean
        ): CredentialsAutofillTooltipFragment {

            val cr = ArrayList<Credentials>(credentials)

            val fragment = CredentialsAutofillTooltipFragment()
            fragment.arguments =
                Bundle().also {
                    it.putString("url", url)
                    it.putParcelableArrayList("creds", cr)
                    it.putBoolean("iframe", forIFrame)
                }
            return fragment
        }
    }

    class CredentialsAdapter(
        val credentials: List<Credentials>,
        val onCredentialSelected: (credentials: Credentials) -> Unit
    ) : Adapter<CredentialsViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CredentialsViewHolder {
            val root =
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_row_credentials, parent, false)
            return CredentialsViewHolder(root)
        }

        override fun onBindViewHolder(viewHolder: CredentialsViewHolder, position: Int) {
            val credentials = credentials[position]
            viewHolder.textView.text = credentials.username
            viewHolder.root.setOnClickListener {
                Timber.i("selected %s", credentials.username)
                onCredentialSelected(credentials)
            }
        }

        override fun getItemCount(): Int = credentials.size
    }

    class CredentialsViewHolder(val root: View) : ViewHolder(root) {
        val textView: TextView = root.findViewById(R.id.username)
    }
}
