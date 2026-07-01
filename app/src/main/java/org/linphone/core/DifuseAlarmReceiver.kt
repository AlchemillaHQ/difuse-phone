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
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.tools.Log

class DifuseAlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "[Difuse Heartbeat]"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("$TAG Heartbeat alarm fired, action=[$action]")

        val deviceIdsJson = corePreferences.difuseAccountDeviceIds
        if (deviceIdsJson.isEmpty() || deviceIdsJson == "{}") {
            Log.i("$TAG No Difuse device IDs, skipping heartbeat check")
            return
        }

        if (!coreContext.isCoreAvailable()) {
            Log.w("$TAG Core not available yet, skipping heartbeat check")
            return
        }

        coreContext.postOnCoreThread {
            coreContext.checkDifuseUpstreamRegistrationIfNeededHeartbeat()
        }
    }
}
