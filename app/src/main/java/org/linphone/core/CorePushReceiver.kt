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
package org.linphone.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.LinphoneUtils.Companion.isPushOnly

class CorePushReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "[Push Notification]"
        private const val PUSH_REGISTER_WINDOW_MS = 60 * 1000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.e("$TAG ==================== PUSH RECEIVED (BEGIN) ====================")
        Log.e("$TAG Push broadcast receiver triggered, action=[${intent.action}]")

        val extras = intent.extras
        Log.e("$TAG Extras present=[${extras != null}] count=[${extras?.size() ?: 0}]")
        val callId = extras?.getString("call_id") ?: extras?.getString("call-id")
        val callerName = extras?.getString("caller_name") ?: extras?.getString("from-name")
        val callerUri = extras?.getString("caller_uri")
            ?: extras?.getString("from")
            ?: extras?.getString("remote_uri")

        Log.e("$TAG Push payload call_id=[$callId], caller_uri=[$callerUri], caller_name=[$callerName]")
        logExtrasDump(extras)
        Log.e("$TAG ===================== PUSH RECEIVED (END) =====================")

        coreContext.postOnCoreThread { core ->
            if (callerUri != null && isCallerAlreadyRinging(core, callerUri)) {
                Log.i("$TAG Ignoring duplicate push for caller [$callerUri] because app is already ringing")
                return@postOnCoreThread
            }

            var triggered = 0
            for (account in core.accountList) {
                if (!account.isPushOnly()) continue

                val params = account.params
                if (!params.isRegisterEnabled) {
                    val clone = params.clone()
                    clone.isRegisterEnabled = true
                    account.params = clone
                }

                account.refreshRegister()
                triggered += 1

                // Keep REGISTER enabled only for a short wake window.
                coreContext.postOnCoreThreadDelayed({
                    if (!core.accountList.contains(account)) {
                        return@postOnCoreThreadDelayed
                    }
                    val latest = account.params
                    if (latest.isRegisterEnabled) {
                        val clone = latest.clone()
                        clone.isRegisterEnabled = false
                        account.params = clone
                    }
                }, PUSH_REGISTER_WINDOW_MS)
            }

            if (triggered == 0) {
                Log.w("$TAG No push-only account configured, nothing to REGISTER")
            } else {
                Log.i("$TAG Triggered temporary REGISTER for [$triggered] push-only account(s)")
            }

            if (extras != null) {
                Log.i("$TAG Push extras keys: ${extras.keySet().joinToString()}")
            }
        }
    }

    private fun logExtrasDump(extras: android.os.Bundle?) {
        if (extras == null) {
            Log.e("$TAG Push extras dump: <none>")
            return
        }

        val keys = extras.keySet().toList().sorted()
        if (keys.isEmpty()) {
            Log.e("$TAG Push extras dump: <empty>")
            return
        }

        for (key in keys) {
            val value = extras.getString(key) ?: "<non-string-or-null>"
            Log.e("$TAG Push extra [$key] = [$value]")
        }
    }

    private fun isCallerAlreadyRinging(core: Core, callerUri: String): Boolean {
        return core.calls.any { call ->
            LinphoneUtils.isCallIncoming(call.state) &&
                call.remoteAddress.asStringUriOnly().contains(callerUri, ignoreCase = true)
        }
    }
}
