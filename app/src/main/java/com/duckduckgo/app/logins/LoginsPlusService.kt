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

import android.app.assist.AssistStructure
import android.app.assist.AssistStructure.ViewNode
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.View
import android.view.ViewStructure.HtmlInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.duckduckgo.app.logins.LoginsPlusService.NativeAutofillType.CreditCardExpiry
import com.duckduckgo.app.logins.LoginsPlusService.NativeAutofillType.CreditCardNumber
import com.duckduckgo.app.logins.LoginsPlusService.NativeAutofillType.CreditCardSecurityCode
import com.duckduckgo.app.logins.LoginsPlusService.NativeAutofillType.EmailAddress
import com.duckduckgo.app.logins.LoginsPlusService.NativeAutofillType.NameFull
import com.duckduckgo.app.logins.LoginsPlusService.NativeAutofillType.TelephoneFull
import com.duckduckgo.app.logins.LoginsPlusService.NativeAutofillType.Unknown
import com.duckduckgo.app.logins.LoginsPlusService.OurAutofillResponse.SingleFieldResponse
import timber.log.Timber

@RequiresApi(VERSION_CODES.O)
class LoginsPlusService : AutofillService() {
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        if (VERSION.SDK_INT >= VERSION_CODES.R) {
            request.processInlineSuggestions()
        }

        val context: List<FillContext> = request.fillContexts
        val structure: AssistStructure = context[context.size - 1].structure

        val parsedStructure = parseStructure(structure)
        val fillResponse = parsedStructure.buildFillResponse()
        callback.onSuccess(fillResponse)
    }

    private fun parseStructure(structure: AssistStructure): OurAutofillResponse {
        val ourAutofillResponse = OurAutofillResponse()

        for (i in 0 until structure.windowNodeCount) {
            val node: ViewNode = structure.getWindowNodeAt(i).rootViewNode
            traverseView(node, ourAutofillResponse)
        }

        return ourAutofillResponse

    }

    private fun traverseView(
        node: ViewNode,
        ourAutofillResponse: OurAutofillResponse
    ) {

        if (node.childCount == 0) {
            val autofillType = analyseLeafNode(node)
            ourAutofillResponse.addAutofillResponseForNode(node, autofillType)
        }

        for (i in 0 until node.childCount) {
            traverseView(node.getChildAt(i), ourAutofillResponse)
        }
    }

    class OurAutofillResponse {
        val creditCardNumber: MutableList<SingleFieldResponse> = mutableListOf()
        val emailAddress: MutableList<SingleFieldResponse> = mutableListOf()
        var fullName: SingleFieldResponse? = null
        val telephoneNumber: MutableList<SingleFieldResponse> = mutableListOf()
        val creditCardExpiry: MutableList<SingleFieldResponse> = mutableListOf()
        val creditCardSecurityCode: MutableList<SingleFieldResponse> = mutableListOf()

        fun addAutofillResponseForNode(
            node: ViewNode,
            autofillType: NativeAutofillType
        ) {

            when (autofillType) {
                Unknown -> {}
                NameFull -> fullName = node.simpleResponse("Craig Russell")
                TelephoneFull -> telephoneNumber.add(node.simpleResponse("+447891234567", "Work phone (+447891234567)"))
                EmailAddress -> {
                    emailAddress.add(node.simpleResponse("craig@foo.com", "Work email (craig@foo.com)"))
                    emailAddress.add(node.simpleResponse("craig@example.com", "Personal email (craig@example.com)"))
                }
                CreditCardNumber -> {
                    creditCardNumber.add(node.simpleResponse("012345678910", "Work credit card"))
                }
                CreditCardExpiry -> {
                    creditCardExpiry.add(node.simpleResponse("0123", "Work credit card expiry"))
                }
                CreditCardSecurityCode -> {
                    creditCardSecurityCode.add(node.simpleResponse("987", "Work credit card cvc"))
                }
            }
        }

        data class SingleFieldResponse(
            val nodeId: AutofillId,
            val value: String,
            val displayString: String
        )

        fun ViewNode.simpleResponse(value: String, displayString: String? = null): SingleFieldResponse {
            return SingleFieldResponse(autofillId!!, value, displayString ?: value)
        }
    }

    private fun analyseLeafNode(node: ViewNode): NativeAutofillType {
        val autofillType = determineAutofillType(node)

        logNode(node, autofillType)

        if (autofillType == Unknown) {
            val nativeHint = node.autofillHints?.joinToString()
            val htmlHint = node.htmlInfo.autofillHint()
            if (nativeHint != null && htmlHint != null) {
                Timber.e(
                    "Failed to recognise type:\nnativeHint: %s\nhtmlHint:%s",
                    nativeHint,
                    htmlHint
                )
            }
        }

        return autofillType
    }

    private fun logNode(
        node: ViewNode,
        autofillType: NativeAutofillType
    ) {
        val message =
            String.format(
                """autofillId: %s
                    text: %s
                    type: %s
                    hint: %s
                    htmlInfo: %s
                    inputType: %s
                    importantForAutofill: %s
                    autofillHints: %s
                    recognisedAutofillType: %s
                        """.trim(),
                node.autofillId,
                node.text,
                autofillType(node),
                node.hint,
                node.htmlInfo?.attributes?.joinToString { "${it.first}:${it.second}" },
                inputType(node),
                importantForAutofill(node),
                node.autofillHints?.joinToString { it },
                autofillType
            )

        Timber.i("%s", message)
        if (autofillType != Unknown) {
            Timber.w("Autofill type: %s", autofillType)
        }
    }

    private fun inputType(node: ViewNode): String {
        return when (node.inputType) {
            else -> node.inputType.toString()
        }
    }

    sealed class NativeAutofillType(
        open val nativeAutofillHint: String? = null,
        open val htmlAutofillHint: String? = null
    ) {

        override fun toString(): String {
            return this::class.java.simpleName
        }

        object CreditCardNumber : NativeAutofillType("cc-number", "CREDIT_CARD_NUMBER")
        object CreditCardExpiry : NativeAutofillType("cc-exp", "CREDIT_CARD_EXP_DATE_2_DIGIT_YEAR")
        object CreditCardSecurityCode : NativeAutofillType("cc-csc", "CREDIT_CARD_VERIFICATION_CODE")
        object NameFull : NativeAutofillType("name", "NAME_FULL")
        object TelephoneFull : NativeAutofillType("tel", "PHONE_HOME_WHOLE_NUMBER")
        object EmailAddress : NativeAutofillType("email", "EMAIL_ADDRESS")
        object Unknown : NativeAutofillType()
    }

    private fun OurAutofillResponse.buildFillResponse(): FillResponse? {
        val rb = FillResponse.Builder()
        var fieldSet = false

        emailAddress.forEach { rb.add(it); fieldSet = true }
        creditCardNumber.forEach { rb.add(it); fieldSet = true }
        fullName?.let { rb.add(it); fieldSet = true }
        creditCardExpiry.forEach { rb.add(it); fieldSet = true }
        creditCardSecurityCode.forEach { rb.add(it); fieldSet = true }
        telephoneNumber.forEach { rb.add(it); fieldSet = true }

        return if (fieldSet) rb.build() else null
    }

    private fun FillResponse.Builder.add(field: SingleFieldResponse) {
        addDataset(
            Dataset.Builder()
                .setValue(field.nodeId, AutofillValue.forText(field.value), presentation(field.displayString))
                .build()
        )
    }

    private fun determineAutofillType(node: ViewNode): NativeAutofillType {
        val typeFromNativeHints = determineAutofillTypeFromNativeHints(node.autofillHints)
        if (typeFromNativeHints != Unknown) return typeFromNativeHints

        val typeFromHtmlHint = determineAutofillTypeFromHtmlHints(node.htmlInfo.autofillHint())
        if (typeFromHtmlHint != Unknown) return typeFromHtmlHint

        return Unknown
    }

    private fun determineAutofillTypeFromNativeHints(hints: Array<String>?): NativeAutofillType {
        if (hints.isNullOrEmpty()) return Unknown

        return NativeAutofillType::class.nestedClasses
            .map { it.objectInstance as NativeAutofillType }
            .firstOrNull { hints.contains(it.nativeAutofillHint) }
            ?: Unknown
    }

    private fun determineAutofillTypeFromHtmlHints(hint: String?): NativeAutofillType {
        if (hint.isNullOrEmpty()) return Unknown

        return NativeAutofillType::class.nestedClasses
            .map { it.objectInstance as NativeAutofillType }
            .firstOrNull { hint == it.htmlAutofillHint }
            ?: Unknown
    }

    private fun autofillType(node: ViewNode): String {
        return when (node.autofillType) {
            View.AUTOFILL_TYPE_DATE -> "date"
            View.AUTOFILL_TYPE_LIST -> "list"
            View.AUTOFILL_TYPE_NONE -> "none"
            View.AUTOFILL_TYPE_TEXT -> "text"
            View.AUTOFILL_TYPE_TOGGLE -> "toggle"
            else -> "unknown"
        }
    }

    private fun importantForAutofill(node: ViewNode): Boolean {
        return if (VERSION.SDK_INT >= VERSION_CODES.P) {
            node.importantForAutofill == 1
        } else {
            false
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        Timber.w("onSaveRequest")
        callback.onSuccess()
    }

    fun presentation(displayString: String): RemoteViews {
        return RemoteViews(packageName, android.R.layout.simple_list_item_1).also {
            it.setTextViewText(android.R.id.text1, displayString)
        }
    }
}

@RequiresApi(VERSION_CODES.R)
private fun FillRequest.processInlineSuggestions() {
    val inlineRequest = this.inlineSuggestionsRequest ?: return
    Timber.i("Inline suggestions: %s requesting max %d ", inlineRequest.hostPackageName, inlineRequest.maxSuggestionCount)

}

private fun HtmlInfo?.autofillHint(): String? {
    if (this == null) return null

    if (VERSION.SDK_INT < VERSION_CODES.O) {
        return null
    }

    if (attributes.isNullOrEmpty()) return null

    return attributes?.firstOrNull { it.first == "ua-autofill-hints" }?.second
}
