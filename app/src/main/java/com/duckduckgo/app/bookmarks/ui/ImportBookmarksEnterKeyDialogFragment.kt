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

import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.support.v7.app.AlertDialog
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.global.view.showKeyboard
import org.jetbrains.anko.find
import timber.log.Timber


class ImportBookmarksEnterKeyDialogFragment : DialogFragment() {

    interface Listener {
        fun onBookmarkImportKeyEntered(key: String)
    }

    var listener: Listener? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        val rootView = View.inflate(activity, R.layout.import_bookmark_enter_key, null)
        val keyInput = rootView.find<EditText>(R.id.keyInput)

        val alertBuilder = AlertDialog.Builder(activity!!)
            .setTitle(R.string.importBookmarks)
            .setView(rootView)
            .setPositiveButton(R.string.importBookmarks) { _, _ ->
                userAcceptedDialog(keyInput)
            }
            .setNegativeButton(android.R.string.cancel) {_, _ ->

            }

        val alert = alertBuilder.create()
        showKeyboard(keyInput, alert)
        return alert
    }

    private fun userAcceptedDialog(keyInput: EditText) {
        val key = keyInput.text.toString()
        if(key.isBlank()) {
            Toast.makeText(activity, R.string.invalidBookmarkKey, Toast.LENGTH_SHORT).show()
            return
        } else {
            Timber.i("Key entered - $key")
            listener?.onBookmarkImportKeyEntered(keyInput.text.toString())
        }
    }

    private fun showKeyboard(titleInput: EditText, alert: AlertDialog) {
        titleInput.setSelection(titleInput.text.length)
        titleInput.showKeyboard()
        alert.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

}