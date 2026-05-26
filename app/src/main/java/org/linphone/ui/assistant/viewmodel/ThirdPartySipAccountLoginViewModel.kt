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
package org.linphone.ui.assistant.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.Account
import org.linphone.core.AuthInfo
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.DifuseApi
import org.linphone.core.Factory
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event

class ThirdPartySipAccountLoginViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Third Party SIP Account Login ViewModel]"
        private const val DIFUSE_PUSH_ONLY_PARAM = "difuse_push_only"
        private const val DIFUSE_PAIR_ID_PARAM = "difuse_pair_id"
    }

    val showBackButton = MutableLiveData<Boolean>()

    val username = MutableLiveData<String>()

    val authId = MutableLiveData<String>()

    val password = MutableLiveData<String>()

    val displayName = MutableLiveData<String>()

    /** PBX SIP server hostname. */
    val upstreamHost = MutableLiveData<String>()

    val upstreamTransport = MutableLiveData<String>()

    val internationalPrefix = MutableLiveData<String>()

    val internationalPrefixIsoCountryCode = MutableLiveData<String>()

    val showPassword = MutableLiveData<Boolean>()

    val expandAdvancedSettings = MutableLiveData<Boolean>()

    val proxy = MutableLiveData<String>()

    val outboundProxy = MutableLiveData<String>()

    val loginEnabled = MediatorLiveData<Boolean>()

    val registrationInProgress = MutableLiveData<Boolean>()

    val accountLoggedInEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val accountLoginErrorEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    private lateinit var newlyCreatedAuthInfo: AuthInfo
    private lateinit var newlyCreatedAccount: Account        // direct PBX account (visible)
    private lateinit var newlyCreatedPushAccount: Account    // B2BUA push-only account (hidden)

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            // We track the direct PBX account — that's the one the user cares about
            if (account == newlyCreatedAccount) {
                Log.i("$TAG Direct PBX account registration state is [$state] ($message)")

                if (state == RegistrationState.Ok) {
                    registrationInProgress.postValue(false)
                    core.removeListener(this)
                    core.defaultAccount = newlyCreatedAccount
                    accountLoggedInEvent.postValue(Event(true))
                } else if (state == RegistrationState.Failed) {
                    registrationInProgress.postValue(false)
                    core.removeListener(this)

                    val error = when (account.error) {
                        Reason.Forbidden -> {
                            AppUtils.getString(R.string.assistant_account_login_forbidden_error)
                        }
                        else -> {
                            AppUtils.getFormattedString(
                                R.string.assistant_account_login_error,
                                account.error.toString()
                            )
                        }
                    }
                    accountLoginErrorEvent.postValue(Event(error))

                    Log.e("$TAG Account failed to REGISTER [$message], removing both accounts")
                    core.removeAuthInfo(newlyCreatedAuthInfo)
                    core.removeAccount(newlyCreatedAccount)
                    if (::newlyCreatedPushAccount.isInitialized) {
                        core.removeAccount(newlyCreatedPushAccount)
                    }
                }
            }
        }
    }

    init {
        showPassword.value = false
        expandAdvancedSettings.value = false
        registrationInProgress.value = false

        loginEnabled.addSource(username) {
            loginEnabled.value = isLoginButtonEnabled()
        }
        loginEnabled.addSource(upstreamHost) {
            loginEnabled.value = isLoginButtonEnabled()
        }
        loginEnabled.addSource(password) {
            loginEnabled.value = isLoginButtonEnabled()
        }

        coreContext.postOnCoreThread { core ->
            // Hide back button when no account is configured yet (first-time setup)
            showBackButton.postValue(core.accountList.isNotEmpty())
        }
    }

    @UiThread
    fun login() {
        coreContext.postOnCoreThread { core ->
            core.loadConfigFromXml(corePreferences.thirdPartyDefaultValuesPath)
            core.isIpv6Enabled = false

            // Allow to enter SIP identity instead of simply username
            var user = username.value.orEmpty().trim()
            if (user.startsWith("sip:")) {
                user = user.substring("sip:".length)
            } else if (user.startsWith("sips:")) {
                user = user.substring("sips:".length)
            }
            if (user.contains("@")) {
                user = user.split("@")[0]
            }

            val userId = authId.value.orEmpty().trim()
            val upstreamHostValue = upstreamHost.value.orEmpty().trim()
            val passwordValue = password.value.orEmpty().trim()
            val normalizedUpstreamHost = upstreamHostValue
                .removePrefix("sip:")
                .removePrefix("sips:")
            // Upstream host may include port (e.g. pbx.example.com:5061) — strip port for identity
            val upstreamHostName = normalizedUpstreamHost.substringBefore(":")
            val upstreamPort = normalizedUpstreamHost.substringAfter(":", "").toIntOrNull() ?: 5061
            Log.i("$TAG Parsed username [$user], user ID [$userId], upstream host [$upstreamHostValue]")

            // ── Account 1: direct PBX (visible to user) ──────────────────────────
            val directIdentity = Factory.instance().createAddress("sip:$user@$upstreamHostName")
            if (directIdentity == null) {
                Log.e("$TAG Can't parse [sip:$user@$upstreamHostName] as Address!")
                showRedToast(R.string.assistant_login_cant_parse_address_toast, R.drawable.warning_circle)
                return@postOnCoreThread
            }
            if (displayName.value.orEmpty().isNotEmpty()) {
                directIdentity.displayName = displayName.value.orEmpty().trim()
            }

            val existingDirect = core.accountList.find {
                it.params.identityAddress?.weakEqual(directIdentity) == true
            }
            if (existingDirect != null) {
                Log.w("$TAG Account [${directIdentity.asStringUriOnly()}] already exists")
                showRedToast(R.string.assistant_account_login_already_connected_error, R.drawable.warning_circle)
                return@postOnCoreThread
            }

            val deviceId = getOrCreateDifuseDeviceId()
            val pushToken = core.pushNotificationConfig?.prid.orEmpty().trim().ifEmpty {
                corePreferences.difusePushToken
            }
            val sipDisplayName = displayName.value.orEmpty().trim()
            corePreferences.difuseUpstreamHost = upstreamHostName
            corePreferences.difuseUpstreamUser = user
            corePreferences.difuseUpstreamPassword = passwordValue
            corePreferences.difuseUpstreamRealm = upstreamHostName
            corePreferences.difuseUpstreamTransport = upstreamTransport.value ?: "tls"
            corePreferences.difuseUpstreamPort = upstreamPort
            corePreferences.difuseDisplayName = sipDisplayName

            registrationInProgress.postValue(true)
            val transportType = parseTransportType(upstreamTransport.value ?: "tls")
            if (pushToken.isEmpty()) {
                Log.w("$TAG Push token is not available yet, continuing with direct PBX account and deferring Difuse registration")
                val created = createAccountsAndStartRegistration(
                    core = core,
                    directIdentity = directIdentity,
                    upstreamHostValue = upstreamHostValue,
                    user = user,
                    userId = userId,
                    passwordValue = passwordValue,
                    b2buaSipUri = null,
                    transportType = transportType,
                )
                if (!created) {
                    registrationInProgress.postValue(false)
                }
                return@postOnCoreThread
            }

            Thread {
                val registerResult = DifuseApi.registerDevice(
                    deviceId = deviceId,
                    pushToken = pushToken,
                    upstreamHost = upstreamHostName,
                    upstreamPort = upstreamPort,
                    upstreamTransport = upstreamTransport.value ?: "tls",
                    upstreamUser = user,
                    upstreamPassword = passwordValue,
                    upstreamRealm = upstreamHostName,
                    displayName = sipDisplayName,
                )

                coreContext.postOnCoreThread {
                    if (registerResult == null || registerResult.statusCode !in 200..299 || registerResult.b2buaSipUri.isEmpty()) {
                        registrationInProgress.postValue(false)
                        val reason = if (registerResult == null) {
                            "Difuse register request failed"
                        } else {
                            "Difuse register failed (${registerResult.statusCode})"
                        }
                        Log.e("$TAG $reason")
                        accountLoginErrorEvent.postValue(
                            Event(
                                AppUtils.getFormattedString(
                                    R.string.assistant_account_login_error,
                                    reason
                                )
                            )
                        )
                        return@postOnCoreThread
                    }

                    val b2buaIdentity = Factory.instance().createAddress(registerResult.b2buaSipUri)
                    if (b2buaIdentity == null || b2buaIdentity.username.isNullOrEmpty() || b2buaIdentity.domain.isNullOrEmpty()) {
                        registrationInProgress.postValue(false)
                        Log.e("$TAG Can't parse [${registerResult.b2buaSipUri}] as B2BUA Address!")
                        showRedToast(R.string.assistant_login_cant_parse_address_toast, R.drawable.warning_circle)
                        return@postOnCoreThread
                    }

                    corePreferences.difuseB2buaSipUri = registerResult.b2buaSipUri
                    corePreferences.difusePushToken = pushToken
                    Log.i("$TAG Difuse registration succeeded, using B2BUA URI [${b2buaIdentity.asStringUriOnly()}]")

                    val created = createAccountsAndStartRegistration(
                        core = core,
                        directIdentity = directIdentity,
                        upstreamHostValue = upstreamHostValue,
                        user = user,
                        userId = userId,
                        passwordValue = passwordValue,
                        b2buaSipUri = registerResult.b2buaSipUri,
                        transportType = transportType,
                    )
                    if (!created) {
                        registrationInProgress.postValue(false)
                    }
                }
            }.start()
        }
    }

    @WorkerThread
    private fun createAccountsAndStartRegistration(
        core: Core,
        directIdentity: org.linphone.core.Address,
        upstreamHostValue: String,
        user: String,
        userId: String,
        passwordValue: String,
        b2buaSipUri: String?,
        transportType: TransportType,
    ): Boolean {
        newlyCreatedAuthInfo = Factory.instance().createAuthInfo(
            user,
            userId.ifEmpty { null },
            passwordValue,
            null,
            null,
            null
        )
        core.addAuthInfo(newlyCreatedAuthInfo)

        val directParams = core.createAccountParams()
        directParams.identityAddress = directIdentity
        val difusePairId = UUID.randomUUID().toString()
        directParams.addCustomParam(DIFUSE_PAIR_ID_PARAM, difusePairId)
        val directServer = Factory.instance().createAddress("sip:$upstreamHostValue")
        directServer?.transport = transportType
        directParams.serverAddress = directServer
        directParams.pushNotificationAllowed = false // B2BUA handles push wakeup

        val prefix = internationalPrefix.value.orEmpty().trim()
        val isoCountryCode = internationalPrefixIsoCountryCode.value.orEmpty()
        if (prefix.isNotEmpty()) {
            val prefixDigits = if (prefix.startsWith("+")) prefix.substring(1) else prefix
            if (prefixDigits.isNotEmpty()) {
                directParams.internationalPrefix = prefixDigits
                directParams.internationalPrefixIsoCountryCode = isoCountryCode
            }
        }

        configureDifuseNatPolicy(core, directParams, upstreamHostValue, user, passwordValue)

        newlyCreatedAccount = core.createAccount(directParams)
        Log.i("$TAG Created direct PBX account [${directIdentity.asStringUriOnly()}]")

        if (!b2buaSipUri.isNullOrEmpty()) {
            val b2buaIdentity = Factory.instance().createAddress(b2buaSipUri)
            if (b2buaIdentity == null || b2buaIdentity.username.isNullOrEmpty() || b2buaIdentity.domain.isNullOrEmpty()) {
                Log.e("$TAG Can't parse [$b2buaSipUri] as B2BUA Address!")
                showRedToast(R.string.assistant_login_cant_parse_address_toast, R.drawable.warning_circle)
                return false
            }

            val pushParams = core.createAccountParams()
            pushParams.identityAddress = b2buaIdentity
            val pushServer = Factory.instance().createAddress("sip:${b2buaIdentity.domain}")
            pushParams.serverAddress = pushServer
            pushParams.pushNotificationAllowed = true
            // This account should only REGISTER when the app is woken up by push.
            pushParams.isRegisterEnabled = false
            pushParams.addCustomParam(DIFUSE_PUSH_ONLY_PARAM, "true")
            pushParams.addCustomParam(DIFUSE_PAIR_ID_PARAM, difusePairId)

            newlyCreatedPushAccount = core.createAccount(pushParams)
            Log.i("$TAG Created B2BUA push account [${b2buaIdentity.asStringUriOnly()}] (hidden)")
            core.addAccount(newlyCreatedPushAccount)
        } else {
            Log.i("$TAG Push token pending, skipping B2BUA push-only account creation for now")
        }

        core.addListener(coreListener)
        core.addAccount(newlyCreatedAccount) // direct PBX account — triggers registration check
        return true
    }

    @UiThread
    fun toggleShowPassword() {
        showPassword.value = showPassword.value == false
    }

    @UiThread
    private fun isLoginButtonEnabled(): Boolean {
        return username.value.orEmpty().isNotEmpty() &&
            upstreamHost.value.orEmpty().isNotEmpty() &&
            password.value.orEmpty().isNotEmpty()
    }

    @UiThread
    fun toggleAdvancedSettingsExpand() {
        expandAdvancedSettings.value = expandAdvancedSettings.value == false
    }

    @UiThread
    fun populateFromQr(username: String, password: String, upstreamHost: String, displayName: String, upstreamTransport: String) {
        this.username.value = username
        this.password.value = password
        this.upstreamHost.value = upstreamHost
        this.displayName.value = displayName
        this.upstreamTransport.value = upstreamTransport
        Log.i("$TAG Form populated from QR: user=[$username] host=[$upstreamHost] transport=[$upstreamTransport]")
    }

    @WorkerThread
    private fun getOrCreateDifuseDeviceId(): String {
        val stored = corePreferences.difuseDeviceId
        if (stored.isNotEmpty()) return stored

        val generated = UUID.randomUUID().toString()
        corePreferences.difuseDeviceId = generated
        return generated
    }

    @WorkerThread
    private fun configureDifuseNatPolicy(
        core: Core,
        params: org.linphone.core.AccountParams,
        upstreamHostValue: String,
        user: String,
        passwordValue: String
    ) {
        val hostname = upstreamHostValue
            .removePrefix("sip:")
            .removePrefix("sips:")
            .substringBefore(":")
        if (!hostname.endsWith(".difusedns.com", ignoreCase = true)) return

        Log.i("$TAG Configuring NAT policy for difuse PBX at [$hostname]")
        val natPolicy = core.createNatPolicy()
        natPolicy.isStunEnabled = true
        natPolicy.isIceEnabled = true
        natPolicy.isTurnEnabled = true
        natPolicy.stunServer = "$hostname:3478"
        natPolicy.stunServerUsername = user
        params.natPolicy = natPolicy

        val turnAuthInfo = Factory.instance().createAuthInfo(
            user,
            null,
            passwordValue,
            null,
            hostname,
            null
        )
        core.addAuthInfo(turnAuthInfo)
        Log.i("$TAG TURN configured: server=[$hostname:3478] user=[$user] realm=[$hostname]")
    }

    private fun parseTransportType(transport: String): TransportType {
        return when (transport.lowercase()) {
            "tcp" -> TransportType.Tcp
            "udp" -> TransportType.Udp
            else -> TransportType.Tls
        }
    }

}
