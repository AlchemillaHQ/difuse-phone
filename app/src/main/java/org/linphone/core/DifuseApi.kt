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

import androidx.annotation.WorkerThread
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import org.json.JSONObject
import org.linphone.core.tools.Log

object DifuseApi {
    private const val TAG = "[Difuse API]"
    private const val BASE_URL = "https://headscale.alchemilla.io"
    private const val JSON_CONTENT_TYPE = "application/json"

    data class RegisterResult(
        val statusCode: Int,
        val b2buaSipUri: String,
        val responseBodySnippet: String,
    )

    data class DeviceStatusResult(
        val statusCode: Int,
        val upstreamRegistered: Boolean,
        val dbExpired: Boolean,
        val expiresAt: String,
        val lastSeen: String,
        val responseBodySnippet: String,
    )

    data class OperationResult(
        val statusCode: Int,
        val success: Boolean,
        val responseBodySnippet: String,
    )

    @WorkerThread
    fun registerDevice(
        deviceId: String,
        pushToken: String,
        upstreamHost: String,
        upstreamPort: Int,
        upstreamTransport: String,
        upstreamUser: String,
        upstreamPassword: String,
        upstreamRealm: String,
        displayName: String,
    ): RegisterResult? {
        val normalizedPushToken = pushToken.trim()
        if (normalizedPushToken.isEmpty()) {
            Log.e("$TAG Skipping register call because push token is empty")
            return null
        }

        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("platform", "android")
            put("push_token", normalizedPushToken)
            put("upstream_host", upstreamHost)
            put("upstream_port", upstreamPort)
            put("upstream_transport", upstreamTransport)
            put("upstream_user", upstreamUser)
            put("upstream_password", upstreamPassword)
            put("upstream_realm", upstreamRealm)
            put("display_name", displayName)
        }

        return try {
            val response = executeJsonRequest(
                endpoint = "$BASE_URL/v1/devices/register",
                method = "POST",
                payload = payload,
            )
            val body = response.responseBody
            val b2buaSipUri = JSONObject(body).optString("b2bua_sip_uri", "").trim()
            RegisterResult(response.statusCode, b2buaSipUri, body.take(300))
        } catch (e: Exception) {
            Log.e("$TAG Failed to register device on Difuse: $e")
            null
        }
    }

    fun refreshDeviceAsync(deviceId: String, pushToken: String, onComplete: ((Boolean) -> Unit)? = null) {
        Thread {
            val encodedDeviceId = encodePathSegment(deviceId)
            val payload = JSONObject().apply {
                put("push_token", pushToken)
            }

            val success = try {
                val response = executeJsonRequest(
                    endpoint = "$BASE_URL/v1/devices/$encodedDeviceId/refresh",
                    method = "PUT",
                    payload = payload,
                )
                val ok = response.statusCode in 200..299
                if (!ok) {
                    Log.w("$TAG Device refresh failed with status [${response.statusCode}] body [${response.responseBody.take(300)}]")
                }
                ok
            } catch (e: Exception) {
                Log.e("$TAG Failed to refresh device on Difuse: $e")
                false
            }

            onComplete?.invoke(success)
        }.start()
    }

    fun getDeviceStatusAsync(deviceId: String, onComplete: ((DeviceStatusResult?) -> Unit)? = null) {
        Thread {
            val encodedDeviceId = encodePathSegment(deviceId)
            val result = try {
                val response = executeJsonRequest(
                    endpoint = "$BASE_URL/v1/devices/$encodedDeviceId/status",
                    method = "GET",
                    payload = null,
                )
                val body = response.responseBody
                val json = if (body.isEmpty()) JSONObject() else JSONObject(body)
                DeviceStatusResult(
                    statusCode = response.statusCode,
                    upstreamRegistered = json.optBoolean("upstream_registered", false),
                    dbExpired = json.optBoolean("db_expired", false),
                    expiresAt = json.optString("expires_at", ""),
                    lastSeen = json.optString("last_seen", ""),
                    responseBodySnippet = body.take(300),
                )
            } catch (e: Exception) {
                Log.e("$TAG Failed to fetch device status from Difuse: $e")
                null
            }

            onComplete?.invoke(result)
        }.start()
    }

    fun reregisterDeviceAsync(deviceId: String, onComplete: ((Boolean) -> Unit)? = null) {
        reregisterDeviceDetailedAsync(deviceId) { result ->
            onComplete?.invoke(result?.success == true)
        }
    }

    fun reregisterDeviceDetailedAsync(deviceId: String, onComplete: ((OperationResult?) -> Unit)? = null) {
        Thread {
            val encodedDeviceId = encodePathSegment(deviceId)
            val result = try {
                val response = executeJsonRequest(
                    endpoint = "$BASE_URL/v1/devices/$encodedDeviceId/reregister",
                    method = "POST",
                    payload = null,
                )
                val ok = response.statusCode in 200..299
                if (!ok) {
                    Log.w("$TAG Device reregister failed with status [${response.statusCode}] body [${response.responseBody.take(300)}]")
                }
                OperationResult(
                    statusCode = response.statusCode,
                    success = ok,
                    responseBodySnippet = response.responseBody.take(300),
                )
            } catch (e: Exception) {
                Log.e("$TAG Failed to force reregister on Difuse: $e")
                null
            }

            onComplete?.invoke(result)
        }.start()
    }

    fun unregisterDeviceAsync(deviceId: String, onComplete: ((Boolean) -> Unit)? = null) {
        Thread {
            val encodedDeviceId = encodePathSegment(deviceId)
            val success = try {
                val response = executeJsonRequest(
                    endpoint = "$BASE_URL/v1/devices/$encodedDeviceId",
                    method = "DELETE",
                    payload = null,
                )
                val ok = response.statusCode in 200..299
                if (!ok) {
                    Log.w("$TAG Device delete failed with status [${response.statusCode}] body [${response.responseBody.take(300)}]")
                }
                ok
            } catch (e: Exception) {
                Log.e("$TAG Failed to unregister device on Difuse: $e")
                false
            }

            onComplete?.invoke(success)
        }.start()
    }

    @WorkerThread
    private fun executeJsonRequest(endpoint: String, method: String, payload: JSONObject?): HttpResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", JSON_CONTENT_TYPE)
                doOutput = payload != null
            }

            if (payload != null) {
                connection.outputStream.use { out ->
                    out.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(statusCode = statusCode, responseBody = body)
        } finally {
            connection?.disconnect()
        }
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private data class HttpResult(
        val statusCode: Int,
        val responseBody: String,
    )
}
