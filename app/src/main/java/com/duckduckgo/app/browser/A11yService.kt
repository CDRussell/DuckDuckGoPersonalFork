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

package com.duckduckgo.app.browser

import android.accessibilityservice.AccessibilityService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
import timber.log.Timber

private val AccessibilityNodeInfo.safeHintText: String?
  get() {
    return if (VERSION.SDK_INT >= VERSION_CODES.O) {
      this.hintText as String?
    } else {
      null
    }
  }

class A11yService : AccessibilityService() {

  override fun onAccessibilityEvent(event: AccessibilityEvent) {
      traverse(rootInActiveWindow, event)
  }

  private fun traverse(node: AccessibilityNodeInfo?, event: AccessibilityEvent) {
    if (node == null) return

    if (node.childCount == 0) {
      analyseLeafNode(node, event)
    }

    for (child in 0 until node.childCount) {
      traverse(node.getChild(child), event)
    }
  }

  private fun analyseLeafNode(node: AccessibilityNodeInfo, event: AccessibilityEvent) {
    Timber.v(
        "Node: " +
            "\nhint=${node.safeHintText}" +
            "\nclickable=${node.isContextClickable}" +
            "\nclass=${node.className}" +
            "\nhints=${node.safeHintText}" +
            "\nviewIdResourceName=${node.viewIdResourceName}" +
            "\ntext=${node.text}" +
            "\nisPassword=${node.isPassword}" +
            "\ndescription=${node.contentDescription}" +
            "")

    if (node.className == "android.widget.EditText") {
          // if(node.viewIdResourceName == "omnibarTextInput") { node.performAutofill("updated url bar
          // from accessibility services") }
        when(node.viewIdResourceName) {
            "example1-email" -> node.performAutofill("craig@example.com")
            "ratto" -> node.performAutofill("craig@example.com")
            //"omnibarTextInput" -> node.performAutofill("updated url bar")

        }
    }
  }

  private fun AccessibilityNodeInfo.performAutofill(textToInsert: String) {
    if (text == textToInsert) return
    val bundle = Bundle().also { it.putString(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToInsert) }
    performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
  }

  override fun onInterrupt() {
    Timber.v("onInterrupt")
  }

  override fun onServiceConnected() {
    Timber.e("onServiceConnected")
  }
}
