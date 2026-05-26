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
package org.linphone.ui.assistant.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.QrCodeScannerDialogFragmentBinding
import org.linphone.ui.GenericActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import android.hardware.camera2.CaptureRequest
import org.json.JSONObject

@UiThread
class QrCodeScannerDialogFragment : DialogFragment() {
    companion object {
        private const val TAG = "[Qr Code Scanner Dialog]"
        const val RESULT_KEY = "qr_code_result"
        private const val ANALYSIS_COOLDOWN_MS = 400L

        fun newInstance() = QrCodeScannerDialogFragment()
    }

    private var _binding: QrCodeScannerDialogFragmentBinding? = null
    private val binding get() = _binding!!

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastAnalysisTime = 0L
    private var frameCount = 0

    @Volatile
    private var qrFound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppSplashScreenTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = QrCodeScannerDialogFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener {
            dismiss()
        }

        if (isCameraPermissionGranted()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        qrFound = true
        analysisExecutor.shutdownNow()
        _binding = null
    }

    private fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.CAMERA),
            0
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.i("$TAG CAMERA permission granted")
            startCamera()
        } else {
            Log.w("$TAG CAMERA permission denied, closing scanner")
            showToastOnMain(getString(R.string.assistant_qr_code_invalid_toast))
            dismiss()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            val preview = previewBuilder
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(options)

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(1920, 1080))
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                if (qrFound) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val now = System.currentTimeMillis()
                if (now - lastAnalysisTime < ANALYSIS_COOLDOWN_MS) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                lastAnalysisTime = now
                frameCount++

                @Suppress("DEPRECATION")
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val inputImage = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )
                    scanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            if (barcodes.isNotEmpty()) {
                                if (frameCount % 10 == 0) {
                                    Log.i("$TAG Frame [$frameCount] found [${barcodes.size}] barcode(s)")
                                }
                                for (barcode in barcodes) {
                                    val rawValue = barcode.rawValue
                                    if (rawValue != null) {
                                        Log.i("$TAG QR scanned on frame [$frameCount]: [$rawValue]")
                                        if (!qrFound) {
                                            qrFound = true
                                            handleScanResult(rawValue)
                                        }
                                        break
                                    }
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("$TAG Barcode error on frame [$frameCount]: $e")
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    if (frameCount <= 1) {
                        Log.e("$TAG imageProxy.image is null, trying bitmap fallback")
                    }
                    val bitmap = imageProxy.toBitmap()
                    if (bitmap != null) {
                        val inputImage = InputImage.fromBitmap(
                            bitmap,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        scanner.process(inputImage)
                            .addOnSuccessListener { barcodes ->
                                if (barcodes.isNotEmpty() && !qrFound) {
                                    Log.i("$TAG QR scanned via bitmap on frame [$frameCount]: [${barcodes[0].rawValue}]")
                                    qrFound = true
                                    handleScanResult(barcodes[0].rawValue ?: return@addOnSuccessListener)
                                }
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } else {
                        Log.e("$TAG Frame [$frameCount]: both image and bitmap are null")
                        imageProxy.close()
                    }
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                Log.i("$TAG CameraX bound at 1920x1080 with autofocus, scanning for QR codes")
            } catch (e: Exception) {
                Log.e("$TAG Failed to bind camera: $e")
                showToastOnMain(getString(R.string.assistant_qr_code_invalid_toast))
                dismiss()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun handleScanResult(rawValue: String) {
        try {
            val json = JSONObject(rawValue)
            val username = json.optString("username", null).takeIf { !it.isNullOrEmpty() }
            val password = json.optString("password", null).takeIf { !it.isNullOrEmpty() }
            val upstreamHost = json.optString("upstreamHost", null).takeIf { !it.isNullOrEmpty() }
            val displayName = json.optString("displayName", null).takeIf { !it.isNullOrEmpty() }
            val upstreamTransport = json.optString("upstreamTransport", "tls").takeIf { !it.isNullOrEmpty() } ?: "tls"

            if (username == null || password == null || upstreamHost == null || displayName == null) {
                Log.w("$TAG QR JSON missing required fields")
                qrFound = false
                showToastOnMain(getString(R.string.assistant_qr_code_missing_fields_toast))
                return
            }

            Log.i("$TAG QR parsed: user=[$username] host=[$upstreamHost] transport=[$upstreamTransport]")
            val result = bundleOf(
                "username" to username,
                "password" to password,
                "upstreamHost" to upstreamHost,
                "displayName" to displayName,
                "upstreamTransport" to upstreamTransport
            )
            setFragmentResult(RESULT_KEY, result)
            dismissAllowingStateLoss()
        } catch (e: Exception) {
            Log.e("$TAG Failed to parse QR JSON: $e")
            qrFound = false
            showToastOnMain(getString(R.string.assistant_qr_code_invalid_json_toast))
        }
    }

    private fun showToastOnMain(message: String) {
        if (!isAdded) return
        try {
            (requireActivity() as GenericActivity).showRedToast(message, R.drawable.warning_circle)
        } catch (_: Exception) { }
    }
}
