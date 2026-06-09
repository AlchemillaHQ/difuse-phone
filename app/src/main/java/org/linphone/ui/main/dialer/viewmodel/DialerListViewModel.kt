/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.dialer.viewmodel

import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log
import org.linphone.ui.main.history.model.NumpadModel
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import org.linphone.utils.LinphoneUtils

class DialerListViewModel
    @UiThread
    constructor() : AbstractMainViewModel() {
    companion object {
        private const val TAG = "[Dialer ViewModel]"
    }

    val digits = MutableLiveData<String>()

    val numpadModel = NumpadModel(
        inCallNumpad = false,
        onDigitClicked = { digit -> appendDigit(digit) },
        onVoicemailClicked = {},
        onBackspaceClicked = { removeLastDigit() },
        onCallClicked = { onCallClicked() },
        onTransferCallClicked = {},
        onClearClicked = { clearDigits() }
    )

    init {
        searchBarVisible.value = false
    }

    @UiThread
    fun appendDigit(digit: String) {
        Log.i("$TAG Appending digit [$digit]")
        digits.value = digits.value.orEmpty() + digit
    }

    @UiThread
    fun removeLastDigit() {
        val current = digits.value.orEmpty()
        if (current.isNotEmpty()) {
            digits.value = current.dropLast(1)
        }
    }

    @UiThread
    fun clearDigits() {
        digits.value = ""
    }

    @UiThread
    fun setDigitsFromPaste(value: String) {
        Log.i("$TAG Setting digits from paste [$value]")
        digits.value = value
    }

    @UiThread
    fun onCallClicked() {
        val number = digits.value.orEmpty().trim()
        if (number.isEmpty()) {
            Log.w("$TAG Can't start call, number is empty")
            return
        }

        Log.i("$TAG Starting call to [$number]")
        coreContext.postOnCoreThread { core ->
            val address = core.interpretUrl(number, LinphoneUtils.applyInternationalPrefix())
            if (address != null) {
                coreContext.startAudioCall(address)
            } else {
                Log.e("$TAG Failed to interpret [$number] as a valid address")
            }
        }
    }
}
