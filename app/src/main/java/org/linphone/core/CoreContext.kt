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

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.provider.Settings.SettingNotFoundException
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.MutableLiveData
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.json.JSONObject
import kotlin.system.exitProcess
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.compatibility.Compatibility
import org.linphone.contacts.ContactsManager
import org.linphone.core.tools.Log
import org.linphone.notifications.NotificationsManager
import org.linphone.telecom.TelecomManager
import org.linphone.ui.call.CallActivity
import org.linphone.utils.ActivityMonitor
import org.linphone.utils.AppUtils
import org.linphone.utils.AudioUtils
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.LinphoneUtils.Companion.isPushOnly

class CoreContext
    @UiThread
    constructor(val context: Context) : HandlerThread("Core Thread") {
    companion object {
        private const val TAG = "[Core Context]"
        private const val PUSH_REGISTER_WINDOW_MS = 60 * 1000L
        private const val DIFUSE_HEARTBEAT_INTERVAL_MS = 30 * 60 * 1000L
        const val ACTION_DIFUSE_HEARTBEAT = "org.linphone.core.action.DIFUSE_HEARTBEAT"
        private const val PUSH_REGISTER_RETRY_INTERVAL_MS = 1500L
        private const val PUSH_REGISTER_RETRY_ATTEMPTS = 8
        private const val DIFUSE_PUSH_ONLY_PARAM = "difuse_push_only"
        private const val DIFUSE_PAIR_ID_PARAM = "difuse_pair_id"
    }

    lateinit var core: Core

    fun isCoreAvailable(): Boolean {
        return ::core.isInitialized
    }

    val contactsManager = ContactsManager()

    val notificationsManager = NotificationsManager(context)

    val telecomManager = TelecomManager(context)

    @get:AnyThread
    val sdkVersion: String by lazy {
        val sdkVersion = context.getString(R.string.linphone_sdk_version)
        val sdkBranch = context.getString(R.string.linphone_sdk_branch)
        val sdkBuildType = org.linphone.core.BuildConfig.BUILD_TYPE
        "$sdkVersion ($sdkBranch, $sdkBuildType)"
    }

    private val activityMonitor = ActivityMonitor()

    private val mainThread = Handler(Looper.getMainLooper())

    var defaultAccountHasVideoConferenceFactoryUri: Boolean = false

    var bearerAuthInfoPendingPasswordUpdate: AuthInfo? = null
    var digestAuthInfoPendingPasswordUpdate: AuthInfo? = null

    val bearerAuthenticationRequestedEvent: MutableLiveData<Event<Pair<String, String?>>> by lazy {
        MutableLiveData<Event<Pair<String, String?>>>()
    }

    val digestAuthenticationRequestedEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    val clearAuthenticationRequestDialogEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val refreshMicrophoneMuteStateEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val showGreenToastEvent: MutableLiveData<Event<Pair<Int, Int>>> by lazy {
        MutableLiveData<Event<Pair<Int, Int>>>()
    }

    val showRedToastEvent: MutableLiveData<Event<Pair<Int, Int>>> by lazy {
        MutableLiveData<Event<Pair<Int, Int>>>()
    }

    val showFormattedRedToastEvent: MutableLiveData<Event<Pair<String, Int>>> by lazy {
        MutableLiveData<Event<Pair<String, Int>>>()
    }

    val provisioningAppliedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val mdmConfigAppliedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val mdmConfigRemovedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    private var filesToExportToNativeMediaGallery = arrayListOf<String>()
    val filesToExportToNativeMediaGalleryEvent: MutableLiveData<Event<List<String>>> by lazy {
        MutableLiveData<Event<List<String>>>()
    }

    private var keepAliveServiceStarted = false
    private val difuseStatusCheckInProgress = AtomicBoolean(false)
    private val difuseInitialRegisterInProgress = AtomicBoolean(false)
    private var lastDifuseStatusCheckTimestampMs = 0L

    private lateinit var proximityWakeLock: PowerManager.WakeLock

    @SuppressLint("HandlerLeak")
    private lateinit var coreThread: Handler

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        @WorkerThread
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (!addedDevices.isNullOrEmpty()) {
                Log.i("$TAG [${addedDevices.size}] new device(s) have been added:")
                var atLeastOneNewDeviceIsBluetooth = false
                for (device in addedDevices) {
                    Log.i(
                        "$TAG Added device [${device.productName}] with ID [${device.id}] and type [${device.type}]"
                    )

                    when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> {
                            atLeastOneNewDeviceIsBluetooth = true
                        }
                    }
                }

                Log.i("$TAG Reloading sound devices in 500ms")
                postOnCoreThreadDelayed({
                    Log.i("$TAG Reloading sound devices")
                    core.reloadSoundDevices()

                    if (atLeastOneNewDeviceIsBluetooth && core.callsNb > 0 && corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG It seems a bluetooth device is now available, trying to route audio to it")
                        AudioUtils.routeAudioToEitherBluetoothOrHearingAid()
                    }
                }, 500)
            }
        }

        @WorkerThread
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (!removedDevices.isNullOrEmpty()) {
                Log.i("$TAG [${removedDevices.size}] existing device(s) have been removed")
                for (device in removedDevices) {
                    Log.i(
                        "$TAG Removed device [${device.id}][${device.productName}][${device.type}]"
                    )
                }

                Log.i("$TAG Reloading sound devices in 500ms")
                postOnCoreThreadDelayed({
                    Log.i("$TAG Reloading sound devices")
                    core.reloadSoundDevices()
                }, 500)
            }
        }
    }

    private var previousCallState = Call.State.Idle

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onPushNotificationReceived(core: Core, payload: String?) {
            Log.e("$TAG ================= CORE PUSH CALLBACK (BEGIN) =================")
            Log.e("$TAG CoreListener.onPushNotificationReceived payload=[$payload]")

            val json = runCatching { JSONObject(payload.orEmpty()) }.getOrNull()
            val callId = extractPushField(json, "call-id", "call_id")
            val callerUri = extractPushField(json, "caller-uri", "caller_uri", "from", "remote_uri")
            val callerName = extractPushField(json, "caller-name", "caller_name", "from-name")
            Log.e("$TAG Parsed push payload call_id=[$callId], caller_uri=[$callerUri], caller_name=[$callerName]")

            triggerTemporaryRegisterForPushOnlyAccounts(core, callerUri)
            Log.e("$TAG ================== CORE PUSH CALLBACK (END) ==================")
        }

        @WorkerThread
        override fun onDefaultAccountChanged(core: Core, account: Account?) {
            defaultAccountHasVideoConferenceFactoryUri = account?.params?.audioVideoConferenceFactoryAddress != null

            val defaultDomain = corePreferences.defaultDomain
            val isAccountOnDefaultDomain = account?.params?.domain == defaultDomain
            val domainFilter = corePreferences.contactsFilter
            Log.i("$TAG Currently selected filter is [$domainFilter]")

            if (!isAccountOnDefaultDomain && domainFilter == defaultDomain) {
                corePreferences.contactsFilter = "*"
                Log.i(
                    "$TAG New default account isn't on default domain, changing filter to any SIP contacts instead"
                )
            } else if (isAccountOnDefaultDomain && domainFilter != "") {
                corePreferences.contactsFilter = defaultDomain
                Log.i("$TAG New default account is on default domain, using that domain as filter instead of wildcard")
            }
        }

        @WorkerThread
        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage?>
        ) {
            if (corePreferences.makePublicMediaFilesDownloaded && core.maxSizeForAutoDownloadIncomingFiles >= 0) {
                for (message in messages) {
                    // Never do auto media export for ephemeral messages!
                    if (message?.isEphemeral == true) continue

                    for (content in message?.contents.orEmpty()) {
                        if (content.isFile) {
                            val path = content.filePath
                            if (path.isNullOrEmpty()) continue

                            val mime = "${content.type}/${content.subtype}"
                            val mimeType = FileUtils.getMimeType(mime)
                            when (mimeType) {
                                FileUtils.MimeType.Image, FileUtils.MimeType.Video, FileUtils.MimeType.Audio -> {
                                    Log.i("$TAG Added file path [$path] to the list of media to export to native media gallery")
                                    filesToExportToNativeMediaGallery.add(path)
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }

            if (filesToExportToNativeMediaGallery.isNotEmpty()) {
                Log.i("$TAG Creating event with [${filesToExportToNativeMediaGallery.size}] files to export to native media gallery")
                filesToExportToNativeMediaGalleryEvent.postValue(Event(filesToExportToNativeMediaGallery))
            }
        }

        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
            Log.i("$TAG Global state changed [$state]")

            if (state == GlobalState.On) {
                // Wait for GlobalState.ON as some settings modification won't be saved
                // in RC file if Core isn't ON
                onCoreStarted()
            } else if (state == GlobalState.Shutdown) {
                onCoreStopped()
            }
        }

        @WorkerThread
        override fun onConfiguringStatus(
            core: Core,
            status: ConfiguringState?,
            message: String?
        ) {
            Log.i("$TAG Configuring state changed [$status], message is [$message]")
            if (status == ConfiguringState.Successful) {
                val accounts = core.accountList
                if (core.defaultAccount == null && accounts.isNotEmpty()) {
                    val firstAccount = accounts.firstOrNull { !it.isPushOnly() } ?: accounts.firstOrNull()
                    if (firstAccount != null) {
                        val sipUri = firstAccount.params.identityAddress?.asStringUriOnly()
                        Log.w(
                            "$TAG Default account is null but account list isn't empty, using account [$sipUri] as default"
                        )
                        core.defaultAccount = firstAccount
                    }
                }

                provisioningAppliedEvent.postValue(Event(true))
                corePreferences.firstLaunch = false
                showGreenToastEvent.postValue(
                    Event(
                        Pair(
                            org.linphone.R.string.remote_provisioning_config_applied_toast,
                            org.linphone.R.drawable.smiley
                        )
                    )
                )
            } else if (status == ConfiguringState.Failed) {
                showRedToastEvent.postValue(
                    Event(
                        Pair(
                            org.linphone.R.string.remote_provisioning_config_failed_toast,
                            org.linphone.R.drawable.warning_circle
                        )
                    )
                )
            }
        }

        @WorkerThread
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            val currentState = call.state
            Log.i(
                "$TAG Call [${call.remoteAddress.asStringUriOnly()}] state changed [$currentState]"
            )
            when (currentState) {
                Call.State.IncomingReceived -> {
                    if (!core.isInBackground) {
                        postOnMainThread {
                            showCallActivity()
                        }
                    }

                    if (corePreferences.autoAnswerEnabled) {
                        val autoAnswerDelay = corePreferences.autoAnswerDelay
                        if (autoAnswerDelay == 0) {
                            Log.w("$TAG Auto answering call immediately")
                            answerCall(call, true)
                        } else {
                            Log.i("$TAG Scheduling auto answering in $autoAnswerDelay milliseconds")
                            postOnCoreThreadDelayed({
                                Log.w("$TAG Auto answering call")
                                answerCall(call, true)
                            }, autoAnswerDelay.toLong())
                        }
                    }
                }
                Call.State.IncomingEarlyMedia -> {
                    if (core.ringDuringIncomingEarlyMedia) {
                        val speaker = core.audioDevices.find {
                            it.type == AudioDevice.Type.Speaker
                        }
                        if (speaker != null) {
                            Log.i("$TAG Ringing during incoming early media enabled, make sure speaker audio device [${speaker.id}] is used")
                            call.outputAudioDevice = speaker
                        } else {
                            Log.w("$TAG No speaker device found, incoming call early media ringing will be played on default device")
                        }
                    }
                }
                Call.State.OutgoingInit -> {
                    val conferenceInfo = core.findConferenceInformationFromUri(call.remoteAddress)
                    // Do not show outgoing call view for conference calls, wait for connected state
                    if (conferenceInfo == null) {
                        postOnMainThread {
                            showCallActivity()
                        }
                    } else {
                        Log.i(
                            "$TAG Call peer address matches known conference, delaying in-call UI until Connected state"
                        )
                    }
                }
                Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                    if (corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG Trying to route audio to either bluetooth or hearing aid if available")
                        AudioUtils.routeAudioToEitherBluetoothOrHearingAid(call)
                    }
                }
                Call.State.Connected -> {
                    postOnMainThread {
                        showCallActivity()
                    }
                    if (corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG Call is connected, trying to route audio to either bluetooth or hearing aid if available")
                        AudioUtils.routeAudioToEitherBluetoothOrHearingAid(call)
                    }
                }
                Call.State.StreamsRunning -> {
                    if (previousCallState == Call.State.Connected) {
                        if (corePreferences.automaticallyStartCallRecording && !call.params.isRecording) {
                            if (call.conference == null) { // TODO: FIXME: Conference recordings are currently disabled
                                Log.i("$TAG Auto record calls is enabled, starting it now")
                                call.startRecording()
                            }
                        }

                        if (core.isInBackground) {
                            // App is in background which means user likely answered the call from the notification
                            // In this case start proximity sensor, otherwise CallActivity will handle it
                            postOnMainThread {
                                Log.i("$TAG App is in background, start proximity sensor")
                                enableProximitySensor(true)
                            }
                        }
                    }
                }
                Call.State.Error -> {
                    val errorInfo = call.errorInfo
                    Log.w(
                        "$TAG Call error reason is [${errorInfo.reason}](${errorInfo.protocolCode}): ${errorInfo.phrase}"
                    )
                    val text = LinphoneUtils.getCallErrorInfoToast(call)
                    showFormattedRedToastEvent.postValue(
                        Event(Pair(text, org.linphone.R.drawable.warning_circle))
                    )
                }
                else -> {
                }
            }

            previousCallState = currentState
        }

        @WorkerThread
        override fun onTransferStateChanged(core: Core, transfered: Call, state: Call.State) {
            Log.i(
                "$TAG Transferred call [${transfered.remoteAddress.asStringUriOnly()}] state changed [$state]"
            )
            if (state == Call.State.Connected) {
                val icon = org.linphone.R.drawable.phone_transfer
                showGreenToastEvent.postValue(
                    Event(Pair(org.linphone.R.string.call_transfer_successful_toast, icon))
                )
            }
        }

        @WorkerThread
        override fun onAudioDevicesListUpdated(core: Core) {
            Log.i("$TAG Available audio devices list was updated")
        }

        @WorkerThread
        override fun onFirstCallStarted(core: Core) {
            Log.i("$TAG First call started")
        }

        @WorkerThread
        override fun onLastCallEnded(core: Core) {
            Log.i("$TAG Last call ended")
            val currentCamera = core.videoDevice
            if (currentCamera != "FrontFacingCamera") {
                val frontFacing = core.videoDevicesList.find { it == "FrontFacingCamera" }
                if (frontFacing == null) {
                    Log.w("$TAG Failed to find [FrontFacingCamera] camera, doing nothing...")
                } else {
                    Log.i("$TAG Last call ended, setting [$frontFacing] as the default one")
                    core.videoDevice = frontFacing
                }
            }

            postOnMainThread {
                Log.i("$TAG Releasing proximity sensor if it was enabled")
                enableProximitySensor(false)
            }
        }

        @WorkerThread
        override fun onAuthenticationRequested(core: Core, authInfo: AuthInfo, method: AuthMethod) {
            when (method) {
                AuthMethod.Bearer -> {
                    if (authInfo.authorizationServer == null) {
                        Log.e(
                            "$TAG Authentication request using Bearer method but authorization server is null!"
                        )
                        return
                    }

                    val serverUrl = authInfo.authorizationServer
                    val username = authInfo.username
                    if (!serverUrl.isNullOrEmpty()) {
                        Log.i(
                            "$TAG Authentication requested method is Bearer, starting Single Sign On activity with server URL [$serverUrl] and username [$username]"
                        )
                        bearerAuthInfoPendingPasswordUpdate = authInfo
                        bearerAuthenticationRequestedEvent.postValue(
                            Event(Pair(serverUrl, username))
                        )
                    } else {
                        Log.e(
                            "$TAG Authentication requested method is Bearer but no authorization server was found in auth info!"
                        )
                    }
                }
                AuthMethod.HttpDigest -> {
                    if (authInfo.username == null || authInfo.domain == null || authInfo.realm == null) {
                        Log.e(
                            "$TAG Authentication request using Digest method but either username [${authInfo.username}], domain [${authInfo.domain}] or realm [${authInfo.realm}] is null!"
                        )
                        return
                    }

                    val accountFound = core.accountList.find {
                        val identity = it.params.identityAddress
                        identity?.username == authInfo.username && (identity?.domain == authInfo.domain || it.params.serverAddress?.domain == authInfo.domain)
                    }
                    if (accountFound == null) {
                        Log.w(
                            "$TAG Failed to find account matching auth info, aborting auth dialog"
                        )
                        return
                    }

                    val identity = "${authInfo.username}@${authInfo.domain}"
                    Log.i(
                        "$TAG Authentication requested method is HttpDigest, showing dialog asking user for password for identity [$identity]"
                    )
                    digestAuthInfoPendingPasswordUpdate = authInfo
                    digestAuthenticationRequestedEvent.postValue(Event(identity))
                }
                AuthMethod.Tls -> {
                    Log.w("$TAG Authentication requested method is TLS, not doing anything...")
                }
                else -> {
                    Log.w("$TAG Unexpected authentication request method [$method]")
                }
            }
        }

        @WorkerThread
        override fun onAccountAdded(core: Core, account: Account) {
            // Prevent this trigger when core is stopped/start in remote prov
            if (core.globalState == GlobalState.Off) return

            Log.i(
                "$TAG New account configured: [${account.params.identityAddress?.asStringUriOnly()}]"
            )
            if (!core.isPushNotificationAvailable || !account.params.isPushNotificationAvailable) {
                if (!corePreferences.keepServiceAlive) {
                    Log.w(
                        "$TAG Newly added account (or the whole Core) doesn't support push notifications, enabling keep-alive foreground service..."
                    )
                    corePreferences.keepServiceAlive = true
                    startKeepAliveService()
                } else {
                    Log.i(
                        "$TAG Newly added account (or the whole Core) doesn't support push notifications but keep-alive foreground service is already enabled, nothing to do"
                    )
                }
            }
        }

        @WorkerThread
        override fun onAccountRemoved(core: Core, account: Account) {
            Log.i("$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] removed, clearing auth request dialog if needed")
            if (account.findAuthInfo() == digestAuthInfoPendingPasswordUpdate) {
                Log.i("$TAG Removed account matches auth info pending password update, removing dialog")
                clearAuthenticationRequestDialogEvent.postValue(Event(true))
                digestAuthInfoPendingPasswordUpdate = null
            }

            if (core.defaultAccount == null || core.defaultAccount == account) {
                Log.w("$TAG Removed account was the default one, choosing another as default if possible")
                val newDefaultAccount = core.accountList.find {
                    it.params.isRegisterEnabled && !it.isPushOnly()
                } ?: core.accountList.find {
                    !it.isPushOnly()
                } ?: core.accountList.firstOrNull()
                if (newDefaultAccount == null) {
                    Log.e("$TAG Failed to find a new default account!")
                } else {
                    Log.i("$TAG New default account will be [${newDefaultAccount.params.identityAddress?.asStringUriOnly()}]")
                    // Delay changing default account to allow for other onAccountRemoved listeners to trigger first
                    postOnCoreThread {
                        core.defaultAccount = newDefaultAccount
                    }
                }
            }
        }
    }

    private var logcatEnabled: Boolean = corePreferences.printLogsInLogcat

    private var crashlyticsEnabled: Boolean = corePreferences.sendLogsToCrashlytics
    private var crashlyticsAvailable = BuildConfig.CRASHLYTICS_ENABLED

    private val loggingServiceListener = object : LoggingServiceListenerStub() {
        @WorkerThread
        override fun onLogMessageWritten(
            logService: LoggingService,
            domain: String,
            level: LogLevel,
            message: String
        ) {
            if (logcatEnabled) {
                when (level) {
                    LogLevel.Error -> android.util.Log.e(domain, message)
                    LogLevel.Warning -> android.util.Log.w(domain, message)
                    LogLevel.Message -> android.util.Log.i(domain, message)
                    LogLevel.Fatal -> android.util.Log.wtf(domain, message)
                    else -> android.util.Log.d(domain, message)
                }
            }
            if (crashlyticsAvailable && crashlyticsEnabled) {
                FirebaseCrashlytics.getInstance().log("[$domain] [${level.name}] $message")
            }
        }
    }

    init {
        (context as Application).registerActivityLifecycleCallbacks(activityMonitor)
    }

    @WorkerThread
    override fun run() {
        Log.i("$TAG Creating Core")
        Looper.prepare()

        if (BuildConfig.CRASHLYTICS_ENABLED) {
            Log.i("$TAG Crashlytics is enabled, registering logging service listener")
            try {
                FirebaseCrashlytics.getInstance()
                Factory.instance().loggingService.addListener(loggingServiceListener)
            } catch (e: Exception) {
                Log.e("$TAG Failed to instantiate Crashlytics: $e")
                crashlyticsEnabled = false
                crashlyticsAvailable = false
            }
        } else {
            Log.i("$TAG Crashlytics is disabled")
            crashlyticsAvailable = false
        }
        Log.i("=========================================")
        Log.i("==== Linphone-android information dump ====")
        val gitVersion = AppUtils.getString(org.linphone.R.string.linphone_app_version)
        val gitBranch = AppUtils.getString(org.linphone.R.string.linphone_app_branch)
        Log.i("VERSION=${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE} ($gitVersion from $gitBranch branch)")
        Log.i("PACKAGE=${BuildConfig.APPLICATION_ID}")
        Log.i("BUILD TYPE=${BuildConfig.BUILD_TYPE}")
        Log.i("=========================================")

        val looper = Looper.myLooper() ?: return
        coreThread = Handler(looper)

        core = Factory.instance().createCoreWithConfig(corePreferences.config, context)
        core.isAutoIterateEnabled = true
        core.addListener(coreListener)

        defaultAccountHasVideoConferenceFactoryUri = core.defaultAccount?.params?.audioVideoConferenceFactoryAddress != null

        coreThread.postDelayed({
            startCore()
            ManagedConfiguration.applyMdmConfigToCore(context, core)
        }, 50)

        Looper.loop()
    }

    override fun quit(): Boolean {
        destroyCore()
        return super.quit()
    }

    override fun quitSafely(): Boolean {
        destroyCore()
        return super.quitSafely()
    }

    @WorkerThread
    fun startCore() {
        Log.i("$TAG Starting Core")

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, coreThread)

        val accounts = core.accountList
        if (core.defaultAccount == null && accounts.isNotEmpty()) {
            Log.e("$TAG No default account set but accounts list not empty!")
            val firstAccount = accounts.firstOrNull { !it.isPushOnly() } ?: accounts.first()
            core.defaultAccount = firstAccount
            Log.w("$TAG Set account [${firstAccount?.params?.identityAddress?.asStringUriOnly()}] as default")
        }

        computeUserAgent()
        Log.i("$TAG Core has been configured with user-agent [${core.userAgent}], starting it")
        core.start()
    }

    @WorkerThread
    fun onCoreStarted() {
        Log.i("$TAG Core started, updating configuration if required")
        core.videoCodecPriorityPolicy = CodecPriorityPolicy.Auto

        val currentVersion = BuildConfig.VERSION_CODE
        val oldVersion = corePreferences.linphoneConfigurationVersion
        Log.w("$TAG Current configuration version is [$oldVersion]")

        if (oldVersion < currentVersion) {
            Log.w("$TAG Migrating configuration to [$currentVersion]")

            if (oldVersion < 600000) { // 6.0.0 initial release
                configurationMigration5To6()
            } else if (oldVersion < 600004) { // 6.0.4
                disablePushNotificationsFromThirdPartySipAccounts()
            } else if (oldVersion < 600009) { // 6.0.9
                removePortFromSipIdentity()
            }

            if (core.logCollectionUploadServerUrl.isNullOrEmpty()) {
                Log.w("$TAG Logs sharing server URL not set, leaving empty")
            }

            corePreferences.linphoneConfigurationVersion = currentVersion
            Log.w(
                "$TAG Core configuration updated to version [${corePreferences.linphoneConfigurationVersion}]"
            )
        } else {
            Log.i("$TAG No configuration migration required")
        }

        contactsManager.onCoreStarted(core)
        telecomManager.onCoreStarted(core)
        notificationsManager.onCoreStarted(core, oldVersion < 600000) // Re-create channels when migrating from a non 6.0 version
        Log.i("$TAG Started contacts, telecom & notifications managers")

        // Ensure every account's proxy uses TLS — fixes accounts saved before this was enforced
        enforceTlsOnAllAccounts()
        DifuseApi.baseUrl = corePreferences.difuseApiBaseUrl
        refreshDifusePushTokenIfNeeded()
        registerDifuseDeviceIfNeeded()
        checkDifuseUpstreamRegistrationIfNeeded()
        scheduleDifuseHeartbeat()

        if (corePreferences.keepServiceAlive) {
            if (activityMonitor.isInForeground() || corePreferences.autoStart) {
                Log.i("$TAG Keep alive service is enabled and either app is in foreground or auto start is enabled, starting it")
                startKeepAliveService()
            } else {
                Log.w("$TAG Keep alive service is enabled but auto start isn't and app is not in foreground, not starting it")
            }
        }

        val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            Log.w("$TAG PROXIMITY_SCREEN_OFF_WAKE_LOCK isn't supported on this device!")
        } else {
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "${context.packageName};proximity_sensor"
            )
        }
    }

    @WorkerThread
    private fun onCoreStopped() {
        Log.w("$TAG Core is being shut down, notifying managers so they can remove their listeners and do some cleanup if needed")
        contactsManager.onCoreStopped(core)
        telecomManager.onCoreStopped(core)
        notificationsManager.onCoreStopped(core)
    }

    @WorkerThread
    private fun destroyCore() {
        if (!::core.isInitialized) {
            return
        }

        val state = core.globalState
        if (state != GlobalState.On) {
            Log.w("$TAG Core is in state [$state], do not continue destroy process")
            return
        }
        Log.w("$TAG Stopping Core and destroying context related objects")

        postOnMainThread {
            (context as Application).unregisterActivityLifecycleCallbacks(activityMonitor)
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)

        core.stop()

        // It's very unlikely the process will survive until the Core reaches GlobalStateOff sadly
        Log.w("$TAG Core has been shut down")
        exitProcess(0)
    }

    @AnyThread
    fun isReady(): Boolean {
        return ::core.isInitialized
    }

    @AnyThread
    fun postOnCoreThread(
        @WorkerThread lambda: (core: Core) -> Unit
    ) {
        if (::coreThread.isInitialized) {
            coreThread.post {
                lambda.invoke(core)
            }
        } else {
            Log.e("$TAG Core's thread not initialized yet!")
        }
    }

    @AnyThread
    fun postOnCoreThreadDelayed(
        @WorkerThread lambda: (core: Core) -> Unit,
        delay: Long
    ) {
        if (::coreThread.isInitialized) {
            coreThread.postDelayed({
                lambda.invoke(core)
            }, delay)
        } else {
            Log.e("$TAG Core's thread not initialized yet!")
        }
    }

    @AnyThread
    fun postOnCoreThreadWhenAvailableForHeavyTask(@WorkerThread lambda: (core: Core) -> Unit, name: String) {
        postOnCoreThread {
            if (core.callsNb >= 1) {
                Log.i("$TAG At least one call is active, wait until there is no more call before executing lambda [$name] (checking again in 1 sec)")
                coreContext.postOnCoreThreadDelayed({
                    postOnCoreThreadWhenAvailableForHeavyTask(lambda, name)
                }, 1000)
            } else {
                Log.i("$TAG No active call at the moment, executing lambda [$name] right now")
                lambda.invoke(core)
            }
        }
    }

    @AnyThread
    fun postOnMainThread(
        @UiThread lambda: () -> Unit
    ) {
        mainThread.post {
            lambda.invoke()
        }
    }

    @UiThread
    fun onForeground() {
        postOnCoreThread {
            // We can't rely on defaultAccount?.params?.isPublishEnabled
            // as it will be modified by the SDK when changing the presence status
            if (corePreferences.publishPresence) {
                Log.i("$TAG App is in foreground, PUBLISHING presence as Online")
                core.consolidatedPresence = ConsolidatedPresence.Online
            }

            if (corePreferences.keepServiceAlive && !keepAliveServiceStarted) {
                startKeepAliveService()
            }

            refreshDifusePushTokenIfNeeded()
            registerDifuseDeviceIfNeeded()
            checkDifuseUpstreamRegistrationIfNeeded()
        }
    }

    @UiThread
    fun onBackground() {
        postOnCoreThread {
            // We can't rely on defaultAccount?.params?.isPublishEnabled
            // as it will be modified by the SDK when changing the presence status
            if (corePreferences.publishPresence) {
                Log.i("$TAG App is in background, un-PUBLISHING presence info")
                // We don't use ConsolidatedPresence.Busy but Offline to do an unsubscribe,
                // Flexisip will handle the Busy status depending on other devices
                core.consolidatedPresence = ConsolidatedPresence.Offline
            }
        }
    }

    @WorkerThread
    fun updateAuthInfo(password: String) {
        val authInfo = digestAuthInfoPendingPasswordUpdate
        if (authInfo != null) {
            Log.i(
                "$TAG Updating password for username [${authInfo.username}] using auth info [$authInfo]"
            )
            authInfo.password = password
            core.addAuthInfo(authInfo)
            digestAuthInfoPendingPasswordUpdate = null
            core.refreshRegisters()
        } else {
            Log.e("$TAG No pending auth info for digest authentication!")
        }
    }

    @WorkerThread
    fun isAddressMyself(address: Address): Boolean {
        val found = core.accountList.find {
            it.params.identityAddress?.weakEqual(address) == true
        }
        return found != null
    }

    @WorkerThread
    fun clearFilesToExportToNativeGallery() {
        filesToExportToNativeMediaGallery.clear()
    }

    @WorkerThread
    fun startAudioCall(
        address: Address,
        forceZRTP: Boolean = false,
        localAddress: Address? = null
    ) {
        val params = core.createCallParams(null)
        params?.isVideoEnabled = false
        startCall(address, params, forceZRTP, localAddress)
    }

    @WorkerThread
    fun startVideoCall(
        address: Address,
        forceZRTP: Boolean = false,
        localAddress: Address? = null
    ) {
        val params = core.createCallParams(null)
        params?.isVideoEnabled = true
        params?.videoDirection = MediaDirection.SendRecv
        startCall(address, params, forceZRTP, localAddress)
    }

    @WorkerThread
    fun startCall(
        address: Address,
        callParams: CallParams? = null,
        forceZRTP: Boolean = false,
        localAddress: Address? = null
    ) {
        if (!core.isNetworkReachable) {
            Log.e("$TAG Network unreachable, abort outgoing call")
            return
        }

        val currentCall = core.currentCall
        if (currentCall != null) {
            Log.w(
                "$TAG Found current call [${currentCall.remoteAddress.asStringUriOnly()}], pausing it first"
            )
            currentCall.pause()
        }

        val params = callParams ?: core.createCallParams(null)
        if (params == null) {
            val call = core.inviteAddress(address)
            Log.w("$TAG Starting call $call without params")
            return
        }

        if (forceZRTP) {
            params.mediaEncryption = MediaEncryption.ZRTP
        }

        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(address)

        if (localAddress != null) {
            val account = core.accountList.find { account ->
                account.params.identityAddress?.weakEqual(localAddress) == true
            }
            if (account != null) {
                params.account = account
                Log.i(
                    "$TAG Using account matching address ${localAddress.asStringUriOnly()} as From"
                )
            } else {
                val defaultAccount = core.defaultAccount
                params.account = defaultAccount
                Log.e(
                    "$TAG Failed to find account matching address ${localAddress.asStringUriOnly()}, using default one [${defaultAccount?.params?.identityAddress?.asStringUriOnly()}]"
                )
            }
        } else {
            val defaultAccount = core.defaultAccount
            params.account = defaultAccount
            Log.i("$TAG No local address given, using default account [${defaultAccount?.params?.identityAddress?.asStringUriOnly()}]")
        }

        val username = address.username.orEmpty()
        val domain = address.domain.orEmpty()
        val account = params.account ?: core.defaultAccount
        if (account != null && Compatibility.isIpAddress(domain)) {
            Log.i("$TAG SIP URI [${address.asStringUriOnly()}] seems to have an IP address as domain")
            if (username.isNotEmpty() && (username.startsWith("+") || username.isDigitsOnly())) {
                val identityDomain = account.params.identityAddress?.domain
                Log.w("$TAG Username [$username] looks like a phone number, replacing domain [$domain] by the local account one [$identityDomain]")
                if (identityDomain != null) {
                    val newAddress = address.clone()
                    newAddress.domain = identityDomain

                    core.inviteAddressWithParams(newAddress, params)
                    Log.i("$TAG Starting call to [${newAddress.asStringUriOnly()}]")
                    return
                }
            }
        }

        core.inviteAddressWithParams(address, params)
        Log.i("$TAG Starting call to [${address.asStringUriOnly()}]")
    }

    @WorkerThread
    fun switchCamera() {
        val currentDevice = core.videoDevice
        Log.i("$TAG Current camera device is $currentDevice")

        for (camera in core.videoDevicesList) {
            if (camera != currentDevice && camera != "StaticImage: Static picture") {
                Log.i("$TAG New camera device will be $camera")
                core.videoDevice = camera
                break
            }
        }

        val call = core.currentCall
        if (call == null) {
            Log.w("$TAG Switching camera while not in call")
            return
        }
        call.update(null)
    }

    @WorkerThread
    fun showSwitchCameraButton(): Boolean {
        return core.isVideoCaptureEnabled && core.videoDevicesList.size > 2 // Count StaticImage camera
    }

    @WorkerThread
    fun answerCall(call: Call, autoAnswer: Boolean = false) {
        Log.i(
            "$TAG Answering call with remote address [${call.remoteAddress.asStringUriOnly()}] and to address [${call.toAddress.asStringUriOnly()}]"
        )
        val params = core.createCallParams(call)
        if (params == null) {
            Log.w("$TAG Answering call without params!")
            call.accept()
            return
        }

        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(call.remoteAddress)

        /*if (LinphoneUtils.checkIfNetworkHasLowBandwidth(context)) {
            Log.w("$TAG Enabling low bandwidth mode!")
            params.isLowBandwidthEnabled = true
        }*/

        if (call.callLog.wasConference()) {
            // Prevent incoming group call to start in audio only layout
            // Do the same as the conference waiting room
            params.isVideoEnabled = true
            params.videoDirection = if (core.videoActivationPolicy.automaticallyInitiate) MediaDirection.SendRecv else MediaDirection.RecvOnly
            Log.i(
                "$TAG Enabling video on call params to prevent audio-only layout when answering"
            )
        } else if (autoAnswer) {
            val videoBothWays = corePreferences.autoAnswerVideoCallsWithVideoDirectionSendReceive
            if (videoBothWays) {
                Log.i("$TAG Call is being auto-answered, requesting video in both ways according to user setting")
                params.videoDirection = MediaDirection.SendRecv
            }
        }

        call.acceptWithParams(params)
    }

    @WorkerThread
    fun terminateCall(call: Call) {
        if (call.dir == Call.Dir.Incoming && LinphoneUtils.isCallIncoming(call.state)) {
            val reason = if (call.core.callsNb > 1) Reason.Busy else Reason.Declined
            Log.i(
                "$TAG Declining call [${call.remoteAddress.asStringUriOnly()}] with reason [$reason]"
            )
            call.decline(reason)
        } else {
            Log.i("$TAG Terminating call [${call.remoteAddress.asStringUriOnly()}]")
            call.terminate()
        }
    }

    @UiThread
    fun showCallActivity() {
        Log.i("$TAG Starting Call activity")
        val intent = Intent(context, CallActivity::class.java)
        // This flag is required to start an Activity from a Service context
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        val options = Compatibility.getPendingIntentActivityOptions(true)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options.toBundle()
        )

        val senderOptions = Compatibility.getPendingIntentActivityOptions(false)
        Compatibility.sendPendingIntent(pendingIntent, senderOptions.toBundle())
    }

    @WorkerThread
    fun scheduleDifuseHeartbeat() {
        val deviceId = corePreferences.difuseDeviceId
        if (deviceId.isEmpty()) {
            Log.i("$TAG No Difuse device ID, skipping heartbeat scheduling")
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_DIFUSE_HEARTBEAT).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            DIFUSE_HEARTBEAT_INTERVAL_MS,
            DIFUSE_HEARTBEAT_INTERVAL_MS,
            pendingIntent
        )
        Log.i("$TAG Difuse heartbeat scheduled every 30 minutes for device [$deviceId]")
    }

    @WorkerThread
    fun cancelDifuseHeartbeat() {
        val intent = Intent(ACTION_DIFUSE_HEARTBEAT).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
        Log.i("$TAG Difuse heartbeat alarm cancelled")
    }

    @WorkerThread
    fun startKeepAliveService() {
        if (keepAliveServiceStarted) {
            Log.w("$TAG Keep alive service already started, skipping")
        }

        val serviceIntent = Intent(Intent.ACTION_MAIN).setClass(
            context,
            CoreKeepAliveThirdPartyAccountsService::class.java
        )
        Log.i("$TAG Starting Keep alive for third party accounts Service")
        try {
            context.startService(serviceIntent)
            keepAliveServiceStarted = true
        } catch (e: Exception) {
            Log.e("$TAG Failed to start keep alive service: $e")
        }
    }

    @WorkerThread
    fun stopKeepAliveService() {
        val serviceIntent = Intent(Intent.ACTION_MAIN).setClass(
            context,
            CoreKeepAliveThirdPartyAccountsService::class.java
        )
        Log.i(
            "$TAG Stopping Keep alive for third party accounts Service"
        )
        context.stopService(serviceIntent)
        keepAliveServiceStarted = false
    }

    @WorkerThread
    fun playDtmf(character: Char, duration: Int = 200, ignoreSystemPolicy: Boolean = false) {
        try {
            if (ignoreSystemPolicy || Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.DTMF_TONE_WHEN_DIALING
                ) != 0
            ) {
                core.playDtmf(character, duration)
            } else {
                Log.w("$TAG Numpad DTMF tones are disabled in system settings, not playing them")
            }
        } catch (snfe: SettingNotFoundException) {
            Log.e("$TAG DTMF_TONE_WHEN_DIALING system setting not found: $snfe")
        }
    }

    @WorkerThread
    fun computeUserAgent() {
        val savedDeviceName = corePreferences.deviceName
        val deviceName = if (savedDeviceName.isEmpty()) {
            Log.i("$TAG Device name not fetched yet, doing it now")
            AppUtils.getDeviceName(context)
        } else if (savedDeviceName.contains("'")) {
            // Some VoIP providers such as voip.ms seem to not like apostrophe in user-agent
            // https://github.com/BelledonneCommunications/linphone-android/issues/2287
            Log.i("$TAG Found an apostrophe in device name, removing it")
            savedDeviceName.replace("'", "")
        } else {
            savedDeviceName
        }
        if (savedDeviceName != deviceName) {
            corePreferences.deviceName = deviceName
        }
        Log.i("$TAG Device name for user-agent is [$deviceName]")

        val appName = context.getString(org.linphone.R.string.app_name)
        val androidVersion = BuildConfig.VERSION_NAME
        val userAgent = "${appName}Android/$androidVersion ($deviceName) LinphoneSDK"
        val sdkVersion = context.getString(R.string.linphone_sdk_version)
        val sdkBranch = context.getString(R.string.linphone_sdk_branch)
        val sdkUserAgent = "$sdkVersion ($sdkBranch)"
        core.setUserAgent(userAgent, sdkUserAgent)
    }

    /**
     * Ensures every account's proxy server (and outbound routes) use TLS transport.
     * Runs on every startup to fix accounts that were persisted before TLS was enforced.
     */
    @WorkerThread
    private fun enforceTlsOnAllAccounts() {
        for (account in core.accountList) {
            if (account.isPushOnly()) {
                // Push-only Difuse accounts may use UDP/TCP/TLS depending on backend setup.
                continue
            }
            val params = account.params
            val serverAddress = params.serverAddress ?: continue
            if (serverAddress.transport != TransportType.Tls) {
                Log.w(
                    "$TAG Account [${params.identityAddress?.asStringUriOnly()}] has transport " +
                        "[${serverAddress.transport}] — forcing TLS"
                )
                val clone = params.clone()
                val newServer = serverAddress.clone()
                newServer.transport = TransportType.Tls
                clone.serverAddress = newServer
                val fixedRoutes = params.routesAddresses.map { r ->
                    val rc = r.clone()
                    rc.transport = TransportType.Tls
                    rc
                }.toTypedArray()
                if (fixedRoutes.isNotEmpty()) clone.setRoutesAddresses(fixedRoutes)
                account.params = clone
            }
        }
    }

    @WorkerThread
    private fun refreshDifusePushTokenIfNeeded() {
        val deviceId = corePreferences.difuseDeviceId
        if (deviceId.isEmpty()) return
        if (corePreferences.difuseB2buaSipUri.isEmpty()) return

        val token = core.pushNotificationConfig?.prid.orEmpty().trim()
        if (token.isEmpty()) return

        val previousToken = corePreferences.difusePushToken
        if (token == previousToken) return

        Log.i("$TAG Difuse push token changed, refreshing device registration")
        DifuseApi.refreshDeviceAsync(deviceId, token) { success ->
            if (success) {
                corePreferences.difusePushToken = token
            }
        }
    }

    @WorkerThread
    fun checkDifuseUpstreamRegistrationIfNeededHeartbeat() {
        checkDifuseUpstreamRegistrationIfNeeded()
    }

    @WorkerThread
    private fun checkDifuseUpstreamRegistrationIfNeeded() {
        val deviceId = corePreferences.difuseDeviceId
        if (deviceId.isEmpty()) return
        if (corePreferences.difuseB2buaSipUri.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastDifuseStatusCheckTimestampMs < 60_000L) return
        if (!difuseStatusCheckInProgress.compareAndSet(false, true)) return
        lastDifuseStatusCheckTimestampMs = now
        Log.i("$TAG Checking Difuse status for device [$deviceId]")

        DifuseApi.getDeviceStatusAsync(deviceId) { status ->
            if (status == null) {
                difuseStatusCheckInProgress.set(false)
                return@getDeviceStatusAsync
            }

            if (status.statusCode == 404) {
                Log.w("$TAG Difuse status returned 404, attempting register fallback")
                registerDeviceOnDifuseFromCurrentAccount(deviceId, "status_404") {
                    difuseStatusCheckInProgress.set(false)
                }
                return@getDeviceStatusAsync
            }

            if (status.statusCode !in 200..299) {
                Log.w("$TAG Difuse status check failed with status [${status.statusCode}] body [${status.responseBodySnippet}]")
                difuseStatusCheckInProgress.set(false)
                return@getDeviceStatusAsync
            }

            if (status.disabled) {
                Log.i("$TAG Difuse device is disabled, skipping upstream registration check")
                difuseStatusCheckInProgress.set(false)
                return@getDeviceStatusAsync
            }

            if (status.upstreamRegistered) {
                difuseStatusCheckInProgress.set(false)
                return@getDeviceStatusAsync
            }

            Log.w("$TAG Difuse reports upstream not registered, forcing reregister")
            DifuseApi.reregisterDeviceDetailedAsync(deviceId) { result ->
                if (result == null) {
                    Log.e("$TAG Difuse forced reregister request failed")
                    difuseStatusCheckInProgress.set(false)
                    return@reregisterDeviceDetailedAsync
                }

                if (result.success) {
                    difuseStatusCheckInProgress.set(false)
                    return@reregisterDeviceDetailedAsync
                }

                if (result.statusCode == 404) {
                    Log.w("$TAG Difuse reregister returned 404, attempting register fallback")
                    registerDeviceOnDifuseFromCurrentAccount(deviceId, "reregister_404") {
                        difuseStatusCheckInProgress.set(false)
                    }
                    return@reregisterDeviceDetailedAsync
                }

                Log.e("$TAG Difuse forced reregister request failed with status [${result.statusCode}] body [${result.responseBodySnippet}]")
                difuseStatusCheckInProgress.set(false)
            }
        }
    }

    @WorkerThread
    private fun registerDifuseDeviceIfNeeded() {
        val token = core.pushNotificationConfig?.prid.orEmpty().trim().ifEmpty {
            corePreferences.difusePushToken
        }
        if (token.isEmpty()) {
            Log.i("$TAG Difuse initial register is pending: push token is still empty")
            return
        }

        // Check if any visible account is missing a B2BUA URI
        val visibleAccounts = core.accountList.filter { !it.isPushOnly() }
        val b2buaMapping = try {
            JSONObject(corePreferences.difuseAccountB2buaSipUris)
        } catch (e: Exception) {
            JSONObject()
        }

        val missingRegistration = visibleAccounts.any { account ->
            val identity = account.params.identityAddress
            if (identity == null) {
                true
            } else {
                b2buaMapping.optString(identity.asStringUriOnly(), "").isEmpty()
            }
        }

        if (!missingRegistration) {
            Log.i("$TAG All visible accounts have B2BUA URIs, skipping registration")
            return
        }

        if (!difuseInitialRegisterInProgress.compareAndSet(false, true)) return

        Log.i("$TAG Some accounts missing B2BUA URIs, registering all accounts on Difuse")
        registerAllAccountsOnDifuse()
        difuseInitialRegisterInProgress.set(false)
    }

    @AnyThread
    private fun registerDeviceOnDifuseFromCurrentAccount(
        deviceId: String,
        trigger: String,
        onComplete: () -> Unit
    ) {
        postOnCoreThread {
            Log.w("$TAG Starting Difuse register fallback for device [$deviceId], trigger=[$trigger]")
            val account = core.accountList.find {
                !it.isPushOnly() && it.params.isRegisterEnabled
            } ?: core.defaultAccount?.takeIf { !it.isPushOnly() }
                ?: core.accountList.find { !it.isPushOnly() }

            if (account == null) {
                Log.e("$TAG Can't perform Difuse register fallback, no visible SIP account found")
                onComplete.invoke()
                return@postOnCoreThread
            }

            val identity = account.params.identityAddress
            val authInfo = account.findAuthInfo()
            val server = account.params.serverAddress

            var upstreamHost = authInfo?.domain.orEmpty().ifEmpty {
                server?.domain.orEmpty().ifEmpty { identity?.domain.orEmpty() }
            }
            var upstreamUser = authInfo?.username.orEmpty().ifEmpty { identity?.username.orEmpty() }
            var upstreamPassword = authInfo?.password.orEmpty()
            var upstreamRealm = authInfo?.realm.orEmpty().ifEmpty { upstreamHost }
            var upstreamTransport = when (server?.transport) {
                TransportType.Udp -> "udp"
                TransportType.Tcp -> "tcp"
                else -> "tls"
            }
            var upstreamPort = server?.port?.takeIf { it > 0 } ?: if (upstreamTransport == "tls") 5061 else 5060
            val pushToken = core.pushNotificationConfig?.prid.orEmpty().trim().ifEmpty {
                corePreferences.difusePushToken
            }
            var displayName = identity?.displayName.orEmpty()

            if (pushToken.isEmpty()) {
                Log.e("$TAG Can't perform Difuse register fallback, push token is empty")
                onComplete.invoke()
                return@postOnCoreThread
            }

            if (upstreamHost.isEmpty() || upstreamUser.isEmpty() || upstreamPassword.isEmpty()) {
                Log.w("$TAG Runtime account/auth credentials incomplete, trying persisted Difuse fallback credentials")
                upstreamHost = corePreferences.difuseUpstreamHost
                upstreamUser = corePreferences.difuseUpstreamUser
                upstreamPassword = corePreferences.difuseUpstreamPassword
                upstreamRealm = corePreferences.difuseUpstreamRealm.ifEmpty { upstreamHost }
                upstreamTransport = corePreferences.difuseUpstreamTransport.ifEmpty { upstreamTransport }
                val cachedPort = corePreferences.difuseUpstreamPort
                if (cachedPort > 0) {
                    upstreamPort = cachedPort
                } else if (upstreamPort <= 0) {
                    upstreamPort = if (upstreamTransport == "tls") 5061 else 5060
                }
                if (displayName.isEmpty()) {
                    displayName = corePreferences.difuseDisplayName
                }
            }

            if (upstreamHost.isEmpty() || upstreamUser.isEmpty() || upstreamPassword.isEmpty()) {
                Log.e("$TAG Can't perform Difuse register fallback, upstream credentials are incomplete")
                onComplete.invoke()
                return@postOnCoreThread
            }

            Thread {
                val result = DifuseApi.registerDevice(
                    deviceId = deviceId,
                    pushToken = pushToken,
                    upstreamHost = upstreamHost,
                    upstreamPort = upstreamPort,
                    upstreamTransport = upstreamTransport,
                    upstreamUser = upstreamUser,
                    upstreamPassword = upstreamPassword,
                    upstreamRealm = upstreamRealm,
                    displayName = displayName,
                )

                if (result != null && result.statusCode in 200..299 && result.b2buaSipUri.isNotEmpty()) {
                    corePreferences.difuseB2buaSipUri = result.b2buaSipUri
                    corePreferences.difusePushToken = pushToken
                    Log.i("$TAG Difuse register fallback succeeded for device [$deviceId], trigger=[$trigger]")
                    postOnCoreThread {
                        ensurePushOnlyDifuseAccountExists(account, result.b2buaSipUri)
                    }
                } else {
                    Log.e("$TAG Difuse register fallback failed for device [$deviceId], trigger=[$trigger]")
                }

                onComplete.invoke()
            }.start()
        }
    }

    @AnyThread
    fun forceReregisterAllAccounts() {
        postOnCoreThread {
            Log.i("$TAG Force re-registering all accounts on Difuse")
            registerAllAccountsOnDifuse()
        }
    }

    @WorkerThread
    private fun registerAllAccountsOnDifuse() {
        val pushToken = core.pushNotificationConfig?.prid.orEmpty().trim().ifEmpty {
            corePreferences.difusePushToken
        }
        if (pushToken.isEmpty()) {
            Log.w("$TAG Can't register all accounts, push token is empty")
            return
        }

        val visibleAccounts = core.accountList.filter { !it.isPushOnly() }
        if (visibleAccounts.isEmpty()) {
            Log.w("$TAG No visible accounts to register on Difuse")
            return
        }

        Log.i("$TAG Registering [${visibleAccounts.size}] accounts on Difuse")

        // Load existing device ID mapping
        val deviceIdMapping = try {
            JSONObject(corePreferences.difuseAccountDeviceIds)
        } catch (e: Exception) {
            JSONObject()
        }

        // Load existing B2BUA URI mapping
        val b2buaMapping = try {
            JSONObject(corePreferences.difuseAccountB2buaSipUris)
        } catch (e: Exception) {
            JSONObject()
        }

        for (account in visibleAccounts) {
            val identity = account.params.identityAddress
            if (identity == null) {
                Log.w("$TAG Skipping account with null identity")
                continue
            }

            val accountKey = identity.asStringUriOnly()

            // Get or generate device ID for this account
            var deviceId = deviceIdMapping.optString(accountKey, "")
            if (deviceId.isEmpty()) {
                deviceId = UUID.randomUUID().toString()
                deviceIdMapping.put(accountKey, deviceId)
                Log.i("$TAG Generated new device ID for account [$accountKey]: $deviceId")
            }

            // Get account credentials
            val authInfo = account.findAuthInfo()
            val server = account.params.serverAddress

            val upstreamHost = authInfo?.domain.orEmpty().ifEmpty {
                server?.domain.orEmpty().ifEmpty { identity.domain.orEmpty() }
            }
            val upstreamUser = authInfo?.username.orEmpty().ifEmpty { identity.username.orEmpty() }
            val upstreamPassword = authInfo?.password.orEmpty()
            val upstreamRealm = authInfo?.realm.orEmpty().ifEmpty { upstreamHost }
            val upstreamTransport = when (server?.transport) {
                TransportType.Udp -> "udp"
                TransportType.Tcp -> "tcp"
                else -> "tls"
            }
            val upstreamPort = server?.port?.takeIf { it > 0 } ?: if (upstreamTransport == "tls") 5061 else 5060
            val displayName = identity.displayName.orEmpty()

            if (upstreamHost.isEmpty() || upstreamUser.isEmpty() || upstreamPassword.isEmpty()) {
                Log.w("$TAG Skipping account [$accountKey], incomplete credentials")
                continue
            }

            // Register this account on Difuse
            Thread {
                val result = DifuseApi.registerDevice(
                    deviceId = deviceId,
                    pushToken = pushToken,
                    upstreamHost = upstreamHost,
                    upstreamPort = upstreamPort,
                    upstreamTransport = upstreamTransport,
                    upstreamUser = upstreamUser,
                    upstreamPassword = upstreamPassword,
                    upstreamRealm = upstreamRealm,
                    displayName = displayName,
                )

                postOnCoreThread {
                    if (result != null && result.statusCode in 200..299 && result.b2buaSipUri.isNotEmpty()) {
                        Log.i("$TAG Difuse registration succeeded for account [$accountKey], B2BUA URI: ${result.b2buaSipUri}")

                        // Store B2BUA URI mapping
                        b2buaMapping.put(accountKey, result.b2buaSipUri)
                        corePreferences.difuseAccountB2buaSipUris = b2buaMapping.toString()

                        // Update legacy single-URI preference for backward compatibility
                        corePreferences.difuseB2buaSipUri = result.b2buaSipUri
                        corePreferences.difuseDeviceId = deviceId

                        // Ensure push-only account exists
                        ensurePushOnlyDifuseAccountExists(account, result.b2buaSipUri)
                    } else {
                        Log.e("$TAG Difuse registration failed for account [$accountKey]")
                    }
                }
            }.start()
        }

        // Save the updated device ID mapping
        corePreferences.difuseAccountDeviceIds = deviceIdMapping.toString()
    }

    @WorkerThread
    private fun triggerTemporaryRegisterForPushOnlyAccounts(core: Core, callerUri: String?) {
        if (!callerUri.isNullOrEmpty() && isCallerAlreadyRinging(core, callerUri)) {
            Log.i("$TAG [core_listener] Ignoring duplicate push for caller [$callerUri] because app is already ringing")
            return
        }

        val triggered = refreshRegistersForIncomingPush(core)

        // Retry REGISTER bursts a few times in case the first one misses the INVITE race window.
        scheduleIncomingPushRegisterRetry(core, callerUri, 1)

        if (triggered == 0) {
            Log.w("$TAG [core_listener] No push-only account configured, nothing to REGISTER")
        } else {
            Log.i("$TAG [core_listener] Triggered temporary REGISTER for [$triggered] push-only account(s)")
        }
    }

    @WorkerThread
    private fun refreshRegistersForIncomingPush(core: Core): Int {
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

            postOnCoreThreadDelayed({
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

        val visibleEnabledAccounts = core.accountList.filter { !it.isPushOnly() && it.params.isRegisterEnabled }
        if (visibleEnabledAccounts.isNotEmpty()) {
            Log.i("$TAG [core_listener] Refreshing REGISTER on [${visibleEnabledAccounts.size}] visible account(s) as push fallback")
            core.refreshRegisters()
        }

        return triggered
    }

    @WorkerThread
    private fun scheduleIncomingPushRegisterRetry(core: Core, callerUri: String?, attempt: Int) {
        if (attempt > PUSH_REGISTER_RETRY_ATTEMPTS) return

        postOnCoreThreadDelayed({
            if (!callerUri.isNullOrEmpty() && isCallerAlreadyRinging(core, callerUri)) {
                Log.i("$TAG [core_listener] Incoming call is now ringing, stop REGISTER retries")
                return@postOnCoreThreadDelayed
            }

            val triggered = refreshRegistersForIncomingPush(core)
            Log.i("$TAG [core_listener] Push retry #$attempt refreshed push-only REGISTER on [$triggered] account(s)")

            scheduleIncomingPushRegisterRetry(core, callerUri, attempt + 1)
        }, PUSH_REGISTER_RETRY_INTERVAL_MS)
    }

    @WorkerThread
    private fun isCallerAlreadyRinging(core: Core, callerUri: String): Boolean {
        return core.calls.any { call ->
            LinphoneUtils.isCallIncoming(call.state) &&
                call.remoteAddress.asStringUriOnly().contains(callerUri, ignoreCase = true)
        }
    }

    private fun extractPushField(json: JSONObject?, vararg keys: String): String? {
        if (json == null) return null
        for (key in keys) {
            val value = json.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    // Migration between versions related

    @WorkerThread
    private fun removePortFromSipIdentity() {
        for (account in core.accountList) {
            val params = account.params
            val identity = params.identityAddress
            if (identity != null && identity.port != 0) {
                val clone = params.clone()
                val newIdentity = identity.clone()
                newIdentity.port = 0
                clone.identityAddress = newIdentity
                Log.w("$TAG Found account with identity address [${identity.asStringUriOnly()}] that contains port information in domain, removing port information in new identity [${newIdentity.asStringUriOnly()}]")
                account.params = clone
            }
        }
    }

    @WorkerThread
    private fun ensurePushOnlyDifuseAccountExists(visibleAccount: Account, b2buaSipUri: String) {
        if (!core.accountList.contains(visibleAccount)) {
            Log.w("$TAG Can't ensure Difuse push-only account, visible account no longer exists")
            return
        }

        val b2buaIdentity = Factory.instance().createAddress(b2buaSipUri)
        if (b2buaIdentity == null || b2buaIdentity.username.isNullOrEmpty() || b2buaIdentity.domain.isNullOrEmpty()) {
            Log.e("$TAG Failed to parse Difuse B2BUA SIP URI [$b2buaSipUri]")
            return
        }

        var pairId = visibleAccount.params.getCustomParam(DIFUSE_PAIR_ID_PARAM)
        if (pairId.isNullOrEmpty()) {
            pairId = UUID.randomUUID().toString()
            val clone = visibleAccount.params.clone()
            clone.addCustomParam(DIFUSE_PAIR_ID_PARAM, pairId)
            visibleAccount.params = clone
            Log.i("$TAG Added missing Difuse pair ID to visible account [${visibleAccount.params.identityAddress?.asStringUriOnly()}]")
        }

        val existingPushAccount = core.accountList.find {
            it.isPushOnly() && it.params.getCustomParam(DIFUSE_PAIR_ID_PARAM) == pairId
        }
        if (existingPushAccount != null) {
            Log.i("$TAG Difuse push-only account already exists for pair [$pairId], nothing to create")
            return
        }

        val pushParams = core.createAccountParams()
        pushParams.identityAddress = b2buaIdentity
        val pushServer = Factory.instance().createAddress("sip:${b2buaIdentity.domain}")
        pushParams.serverAddress = pushServer
        pushParams.pushNotificationAllowed = true
        pushParams.isRegisterEnabled = false
        pushParams.addCustomParam(DIFUSE_PUSH_ONLY_PARAM, "true")
        pushParams.addCustomParam(DIFUSE_PAIR_ID_PARAM, pairId)

        val pushAccount = core.createAccount(pushParams)
        core.addAccount(pushAccount)
        Log.i("$TAG Created missing Difuse push-only account [${b2buaIdentity.asStringUriOnly()}] for pair [$pairId]")
    }

    @WorkerThread
    private fun disablePushNotificationsFromThirdPartySipAccounts() {
        for (account in core.accountList) {
            val params = account.params
            val pushAvailableForDomain = params.identityAddress?.domain in corePreferences.pushNotificationCompatibleDomains
            if (!pushAvailableForDomain && params.pushNotificationAllowed) {
                val clone = params.clone()
                clone.pushNotificationAllowed = false
                Log.w("$TAG Updating account [${params.identityAddress?.asStringUriOnly()}] params to disable push notifications, they won't work and may cause issues when used with UDP transport protocol")
                account.params = clone
            }
        }
    }

    @WorkerThread
    private fun configurationMigration5To6() {
        val policy = core.videoActivationPolicy.clone()
        policy.automaticallyInitiate = false
        policy.automaticallyAccept = true
        policy.automaticallyAcceptDirection = MediaDirection.RecvOnly
        core.videoActivationPolicy = policy
        Log.i(
            "$TAG Updated video activation policy to disable auto initiate, enable auto accept with media direction RecvOnly"
        )

        core.isFecEnabled = true
        Log.i("$TAG Video FEC has been enabled")

        core.config.setBool("magic_search", "return_empty_friends", true)
        Log.i("$TAG Showing 'empty' friends enabled")

        if (LinphoneUtils.getDefaultAccount()?.params?.domain == corePreferences.defaultDomain) {
            corePreferences.contactsFilter = corePreferences.defaultDomain
            Log.i(
                "$TAG Setting default contacts list filter to [${corePreferences.contactsFilter}]"
            )
        }

        for (account in core.accountList) {
            val params = account.params
            if (params.identityAddress?.domain == corePreferences.defaultDomain && params.limeAlgo.isNullOrEmpty()) {
                val clone = params.clone()
                clone.limeAlgo = "c25519"
                Log.i("$TAG Updating account [${params.identityAddress?.asStringUriOnly()}] params to use LIME algo c25519")
                account.params = clone
            }
        }

        Log.i("$TAG Making sure both RFC2833 & SIP INFO are enabled for DTMFs")
        core.useRfc2833ForDtmf = true
        core.useInfoForDtmf = true

        // Add that flag back, was disabled for a time during dev process
        Log.i("$TAG Enabling hiding empty chat rooms")
        core.config.setBool("misc", "hide_empty_chat_rooms", true)

        Log.i("$TAG IMDN threshold set to 1 (meaning only sender will receive delivery & read notifications)")
        core.imdnToEverybodyThreshold = 1

        Log.i("$TAG Removing previous grammar files (without .belr extension)")
        corePreferences.clearPreviousGrammars()
    }

    @WorkerThread
    fun isCrashlyticsAvailable(): Boolean {
        return crashlyticsAvailable
    }

    @WorkerThread
    fun updateLogcatEnabledSetting(enabled: Boolean) {
        logcatEnabled = enabled
    }

    @WorkerThread
    fun updateCrashlyticsEnabledSetting(enabled: Boolean) {
        crashlyticsEnabled = enabled
    }

    @UiThread
    fun enableProximitySensor(enable: Boolean) {
        if (::proximityWakeLock.isInitialized) {
            if (enable && !proximityWakeLock.isHeld) {
                Log.i("$TAG Acquiring proximity sensor wake lock for 2 hours")
                proximityWakeLock.acquire(7200 * 1000L) // 2 hours
            } else if (!enable && proximityWakeLock.isHeld) {
                Log.i("$TAG Releasing proximity sensor wake lock")
                proximityWakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        }
    }

    fun setBackCamera(): Boolean {
        for (camera in core.videoDevicesList) {
            if (camera.contains("Back")) {
                Log.i("TAG Found back facing camera [$camera], using it")
                coreContext.core.videoDevice = camera
                return true
            }
        }
        return false
    }

    fun setFrontCamera(): Boolean {
        for (camera in core.videoDevicesList) {
            if (camera.contains("Front")) {
                Log.i("$TAG Found front facing camera [$camera], using it")
                coreContext.core.videoDevice = camera
                return true
            }
        }
        return false
    }
}
