package com.parental.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.*
import okhttp3.*
import java.nio.ByteBuffer

class ProtectionService : LifecycleService() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var webSocket: WebSocket? = null
    private var locationCallback: LocationCallback? = null

    private val serverUrl = "ws://10.0.2.2:8080"

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        startForegroundServiceWithNotification()
        connectWebSocket()
        startGpsUpdates()
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "protection_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Child Protection Active",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Service Protection Running")
            .setContentText("Monitoring system health")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(1001, notification)
        }
    }

    private fun connectWebSocket() {
        val client = OkHttpClient()
        val request = Request.Builder().url(serverUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                when (text) {
                    "SNAP_FRONT" -> takePhoto(CameraSelector.DEFAULT_FRONT_CAMERA)
                    "SNAP_BACK" -> takePhoto(CameraSelector.DEFAULT_BACK_CAMERA)
                }
            }
        })
    }

    private fun startGpsUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setMinUpdateIntervalMillis(5000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val payload = """{"type":"gps","lat":${loc.latitude},"lng":${loc.longitude}}"""
                webSocket?.send(payload)
                Log.d("ProtectionService", "GPS Sent: $payload")
            }
        }

        try {
            locationCallback?.let {
                fusedLocationClient.requestLocationUpdates(request, it, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            Log.e("ProtectionService", "GPS Permission denied: ${e.message}")
        }
    }

    private fun takePhoto(cameraSelector: CameraSelector) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)

                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val buffer: ByteBuffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            webSocket?.send("""{"type":"camera","image":"$base64"}""")
                            image.close()
                        }

                        override fun onError(exc: ImageCaptureException) {
                            Log.e("ProtectionService", "Camera Error: ${exc.message}")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("ProtectionService", "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        webSocket?.close(1000, "Service destroyed")
    }
}
