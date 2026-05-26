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
package org.linphone.ui.main.help.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.launch
import java.io.File
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.LogCollectionState
import org.linphone.core.VersionUpdateCheckResult
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.FileUtils

class HelpViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Help ViewModel]"

        private const val NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE = 6
    }

    val version = MutableLiveData<String>()

    val appVersion = MutableLiveData<String>()

    val sdkVersion = MutableLiveData<String>()

    val firebaseProjectId = MutableLiveData<String>()

    val checkUpdateAvailable = MutableLiveData<Boolean>()

    val uploadLogsAvailable = MutableLiveData<Boolean>()

    val logsUploadInProgress = MutableLiveData<Boolean>()

    val canConfigFileBeViewed = MutableLiveData<Boolean>()

    val newVersionAvailableEvent: MutableLiveData<Event<Pair<String, String>>> by lazy {
        MutableLiveData<Event<Pair<String, String>>>()
    }

    val versionUpToDateEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val errorEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val debugLogsCleanedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val uploadDebugLogsFinishedEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    val showConfigFileEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    private var versionClickCount: Int = 0

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onVersionUpdateCheckResultReceived(
            core: Core,
            result: VersionUpdateCheckResult,
            version: String?,
            url: String?
        ) {
            when (result) {
                VersionUpdateCheckResult.NewVersionAvailable -> {
                    Log.i("$TAG Update available, version [$version], url [$url]")
                    if (!version.isNullOrEmpty() && !url.isNullOrEmpty()) {
                        newVersionAvailableEvent.postValue(Event(Pair(version, url)))
                    }
                }
                VersionUpdateCheckResult.UpToDate -> {
                    Log.i("$TAG This version is up-to-date")
                    versionUpToDateEvent.postValue(Event(true))
                }
                else -> {
                    Log.e("$TAG Can't check for update, an error happened [$result]")
                    errorEvent.postValue(Event(true))
                }
            }
        }
    }

    init {
        val currentVersion = BuildConfig.VERSION_NAME
        version.value = currentVersion

        val versionCode = BuildConfig.VERSION_CODE
        val appGitDescribe = AppUtils.getString(R.string.linphone_app_version)
        val appBranch = AppUtils.getString(R.string.linphone_app_branch)
        appVersion.value = "$versionCode - $appGitDescribe ($appBranch)"

        sdkVersion.value = coreContext.sdkVersion
        logsUploadInProgress.value = false

        try {
            firebaseProjectId.value = FirebaseApp.getInstance().options.projectId
        } catch (e: Exception) {
            Log.e("$TAG Failed to get FirebaseApp instance: $e")
            firebaseProjectId.value = "unknown"
        }

        versionClickCount = if (corePreferences.showDeveloperSettings) {
            Log.i("$TAG Developer settings are already enabled")
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE
        } else {
            0
        }

        coreContext.postOnCoreThread { core ->
            core.addListener(coreListener)

            checkUpdateAvailable.postValue(corePreferences.checkForUpdateServerUrl.isNotEmpty())
            uploadLogsAvailable.postValue(core.logCollectionEnabled() == LogCollectionState.Enabled)
        }
    }

    @UiThread
    override fun onCleared() {
        super.onCleared()

        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
        }
    }

    @UiThread
    fun versionClicked() {
        versionClickCount += 1
        Log.i("$TAG Version was clicked [$versionClickCount] times")
        when (versionClickCount) {
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE - 2 -> {
                showGreenToast(R.string.settings_developer_two_more_clicks_required_toast, R.drawable.gear)
            }
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE - 1 -> {
                showGreenToast(R.string.settings_developer_one_more_click_required_toast, R.drawable.gear)
            }
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE -> {
                showGreenToast(R.string.settings_developer_enabled_toast, R.drawable.gear)
                coreContext.postOnCoreThread {
                    Log.w("$TAG Enabling developer settings")
                    corePreferences.showDeveloperSettings = true
                }
            }
            NUMBER_OF_CLICK_TO_ENABLE_DEVELOPER_MODE + 1 -> {
                showGreenToast(R.string.settings_developer_already_enabled_toast, R.drawable.gear)
            }
            else -> { }
        }
    }

    @UiThread
    fun cleanLogs() {
        coreContext.postOnCoreThread { core ->
            core.resetLogCollection()
            Log.i("$TAG Debug logs have been cleaned")
            debugLogsCleanedEvent.postValue(Event(true))
        }
    }

    @UiThread
    fun shareLogs() {
        logsUploadInProgress.postValue(true)
        coreContext.postOnCoreThread { core ->
            Log.i("$TAG Compressing debug logs for sharing")
            val compressedPath = core.compressLogCollection()
            Log.i("$TAG Compressed log collection at path [$compressedPath]")
            if (compressedPath.isNotEmpty()) {
                val compressedFile = File(compressedPath)
                val renamedFile = File(compressedFile.parentFile, "difuse_phone_log.gz")
                if (compressedFile.renameTo(renamedFile)) {
                    Log.i("$TAG Renamed compressed logs to [$renamedFile]")
                    uploadDebugLogsFinishedEvent.postValue(Event(renamedFile.absolutePath))
                } else {
                    uploadDebugLogsFinishedEvent.postValue(Event(compressedPath))
                }
            } else {
                Log.e("$TAG compressLogCollection returned empty path")
                showRedToast(R.string.help_troubleshooting_debug_logs_upload_error_toast_message, R.drawable.warning_circle)
            }
            logsUploadInProgress.postValue(false)
        }
    }

    @UiThread
    fun checkForUpdate() {
        val currentVersion = version.value.orEmpty()
        coreContext.postOnCoreThread { core ->
            Log.i("$TAG Checking for update using current version [$currentVersion]")
            core.checkForUpdate(currentVersion)
        }
    }

    @UiThread
    fun showConfigFile() {
        coreContext.postOnCoreThread { core ->
            Log.i("$TAG Dumping & displaying Core's config")
            val config = core.config.dump()
            val file = FileUtils.getFileStorageCacheDir(
                "linphonerc.txt",
                overrideExisting = true
            )
            viewModelScope.launch {
                if (FileUtils.dumpStringToFile(config, file)) {
                    Log.i("$TAG .linphonerc string saved as file in cache folder")
                    showConfigFileEvent.postValue(Event(file.absolutePath))
                } else {
                    Log.e("$TAG Failed to save .linphonerc string as file in cache folder")
                }
            }
        }
    }
}
