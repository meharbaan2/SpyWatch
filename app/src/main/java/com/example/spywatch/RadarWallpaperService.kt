package com.example.spywatch

import android.content.*
import android.graphics.*
import android.os.*
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import kotlin.math.*
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import java.util.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import android.net.wifi.WifiManager
import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import androidx.core.graphics.withRotation
import androidx.core.net.toUri
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo


class RadarWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine {
        return RadarEngine()
    }

    inner class RadarEngine : Engine(), SensorEventListener {
        private var taskUpdateReceiver: BroadcastReceiver? = null
        private var cachedWallpaperData: JSONObject? = null
        private var lastDataLoadTime = 0L
        private val dataRefreshInterval = 60 * 60 * 1000L // 1 HOUR in milliseconds
        // Add compass sensor variables
        private var sensorManager: SensorManager? = null
        private var accelerometerSensor: Sensor? = null
        private var magnetometerSensor: Sensor? = null

        private val accelerometerReading = FloatArray(3)
        private val magnetometerReading = FloatArray(3)
        private val rotationMatrix = FloatArray(9)
        private val orientationAngles = FloatArray(3)

        private var currentHeading = 0f
        private var lastCompassUpdate = 0L
        private val compassUpdateInterval = 100L // ms between updates

        // Add variables to track last visibility time
        private var lastVisibleTime = 0L
        private val minInactiveTime = 30 * 60 * 1000L // 30 minutes in milliseconds

        // Weather Variables
        private var weatherData: WeatherInfo? = null
        private var lastWeatherUpdate: Long = 0
        private val weatherUpdateInterval = 30 * 60 * 1000L // 30 minutes
        private var isWeatherUpdating = false
        private var isNetworkConnected = true
        private var weatherUpdateError: String? = null
        private var networkReceiver: BroadcastReceiver? = null

        private var currentCityIndex = 0
        private val availableCities = listOf(
            "Brampton" to Pair(43.7315, -79.7624),
            "Amritsar" to Pair(31.6340, 74.8723)
        )

        // Enhanced color scheme - PRE-COMPUTE ALL COLORS
        private val backgroundColor = "#0a0e12".toColorInt()
        private val neonGreen = "#00ff88".toColorInt()
        private val neonTeal = "#00d4ff".toColorInt()
        private val hudText = "#BDEEEA".toColorInt()
        private val panelDark = "#0d1117".toColorInt()
        private val panelLight = "#1a2028".toColorInt()
        private val redAlert = "#ff4444".toColorInt()
        private val screenDark = "#000a08".toColorInt()
        private val screenLight = "#00100c".toColorInt()

        // Drawing globals - REUSE OBJECTS
        private var width = 1
        private var height = 1
        private var centerX = 0f
        private var centerY = 0f
        private var radarRadius = 0f

        // PRE-ALLOCATE PAINT OBJECTS
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color = hudText
            textSize = 36f
            typeface = Typeface.MONOSPACE
            isSubpixelText = true
        }

        // PRE-ALLOCATE OBJECTS TO AVOID GC
        private val rectF = RectF()
        private val now = Calendar.getInstance()

        // SHADER CACHE - CREATE ONCE, REUSE
        private var backgroundGradient: LinearGradient? = null
        private var screenGradient: RadialGradient? = null
        private var sweepGradient: SweepGradient? = null
        private var scanLineGradient: LinearGradient? = null
        private var topBarGradient: LinearGradient? = null
        private var bottomBarGradient: LinearGradient? = null

        // Render loop - OPTIMIZED
        private val frameDelay = (1000 / 60).toLong() // 60 fps for smooth movement
        private val drawHandler = Handler(Looper.getMainLooper())
        private val drawRunnable = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) drawHandler.postDelayed(this, frameDelay)
            }
        }

        // Animation states - TIME-BASED ONLY
        private var bootStartTime = 0L
        private var isBooting = true
        private val bootDuration = 3000L

        // Telemetry values
        private var batteryPct = 100

        // Visibility
        private var visible = false

        private var broadcastReceiver: BroadcastReceiver? = null

        // Topographic map variables
        private var topoBitmap: Bitmap? = null
        private var topoBitmapWidth = 0f
        private var topoBitmapHeight = 0f

        // For parallax/movement effect
        private var topoOffsetX = 0f
        private var topoOffsetY = 0f
        private var topoLastUpdate = 0L

        // Network signal variables
        private var wifiSignalStrength = 0
        private var mobileSignalStrength = 0
        private var isWifiConnected = false
        private var isMobileConnected = false
        private var currentNetworkType = "DISCONNECTED"

        // Add network connectivity receiver
        private var connectivityReceiver: BroadcastReceiver? = null

        // Bg zoom
        private var parallaxLayer1Offset = 0f
        private var parallaxLayer2Offset = 0f
        private var parallaxLayer3Offset = 0f

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true) // Enable touch for city switching
            initializeReceivers()
            initializeSensors()

            // Initialize network state
            updateNetworkStatus()

            // Start weather update
            if (isNetworkConnected) {
                updateWeatherIfNeeded()
            }
        }

        private fun initializeSensors() {
            try {
                sensorManager = applicationContext.getSystemService(SENSOR_SERVICE) as SensorManager
                accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                magnetometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

                // Check if sensors are available
                if (accelerometerSensor != null && magnetometerSensor != null) {
                    Log.d("RadarEngine", "Compass sensors available")
                } else {
                    Log.w("RadarEngine", "Compass sensors not available")
                }
            } catch (e: Exception) {
                Log.e("RadarEngine", "Error initializing sensors: ${e.message}")
            }
        }

        private fun initializeReceivers() {
            broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_BATTERY_CHANGED -> {
                            try {
                                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                                batteryPct = if (scale > 0) (level * 100 / scale) else level
                            } catch (_: Exception) {
                                batteryPct = 100
                            }
                        }
                    }
                }
            }

            taskUpdateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.d("RadarWallpaper", "Received task update broadcast")
                    refreshTaskData()
                    // Force a redraw to show updated tasks
                    if (visible) {
                        drawHandler.post(drawRunnable)
                    }
                }
            }

            connectivityReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateNetworkStatus()
                    // Force redraw to show updated network status
                    if (visible) {
                        drawHandler.post(drawRunnable)
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                //addAction(ConnectivityManager.CONNECTIVITY_ACTION) //deprecated
                // For Android 7.0+ we need to listen to CONNECTIVITY_CHANGE
                addAction("android.net.conn.CONNECTIVITY_CHANGE")
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    applicationContext.registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
                    applicationContext.registerReceiver(connectivityReceiver, filter, RECEIVER_NOT_EXPORTED)
                } else {
                    applicationContext.registerReceiver(broadcastReceiver, filter)
                    applicationContext.registerReceiver(connectivityReceiver, filter)
                }
            } catch (e: Exception) {
                Log.e("RadarEngine", "Error registering receiver: ${e.message}")
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            width = w
            height = h
            centerX = w / 2f
            centerY = h / 2f
            radarRadius = min(w, h) * 0.25f

            createShaders()
            loadTopoMap()
            super.onSurfaceChanged(holder, format, w, h)
        }

        private fun createShaders() {
            backgroundGradient = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                "#0a0e12".toColorInt(), "#0d1117".toColorInt(), Shader.TileMode.CLAMP
            )

            screenGradient = RadialGradient(
                centerX, centerY, radarRadius,
                screenDark, screenLight, Shader.TileMode.CLAMP
            )

            sweepGradient = SweepGradient(
                centerX, centerY,
                intArrayOf(Color.TRANSPARENT, neonGreen, Color.TRANSPARENT),
                floatArrayOf(0f, 0.05f, 0.1f)
            )

            scanLineGradient = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.TRANSPARENT, neonGreen, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )

            topBarGradient = LinearGradient(
                0f, 0f, 0f, 120f,
                "#0d1117CC".toColorInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )

            bottomBarGradient = LinearGradient(
                0f, height - 120f, 0f, height.toFloat(),
                Color.TRANSPARENT, "#0d1117CC".toColorInt(), Shader.TileMode.CLAMP
            )
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                val currentTime = System.currentTimeMillis()
                // Only show boot sequence if it's been a while since last visible
                if (currentTime - lastVisibleTime > minInactiveTime) {
                    startBoot()
                } else {
                    isBooting = false
                }
                lastVisibleTime = currentTime
                registerSensors()
                drawHandler.post(drawRunnable)
            } else {
                unregisterSensors()
                drawHandler.removeCallbacks(drawRunnable)
            }
        }

        private fun registerSensors() {
            try {
                sensorManager?.registerListener(
                    this,
                    accelerometerSensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
                sensorManager?.registerListener(
                    this,
                    magnetometerSensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
            } catch (e: Exception) {
                Log.e("RadarEngine", "Error registering sensors: ${e.message}")
            }
        }

        private fun unregisterSensors() {
            try {
                sensorManager?.unregisterListener(this)
            } catch (e: Exception) {
                Log.e("RadarEngine", "Error unregistering sensors: ${e.message}")
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            if (visible) {
                drawHandler.post(drawRunnable)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            drawHandler.removeCallbacks(drawRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            super.onDestroy()
            drawHandler.removeCallbacks(drawRunnable)
            unregisterSensors() // Clean up sensors

            // Unregister all receivers
            listOf(broadcastReceiver, taskUpdateReceiver, networkReceiver, connectivityReceiver).forEach { receiver ->
                receiver?.let {
                    try {
                        applicationContext.unregisterReceiver(it)
                    } catch (e: Exception) {
                        Log.e("RadarEngine", "Error unregistering receiver: ${e.message}")
                    }
                }
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCompassUpdate < compassUpdateInterval) {
                return // Throttle updates
            }

            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
                    updateCompassHeading()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
                    updateCompassHeading()
                }
            }
            lastCompassUpdate = currentTime
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Handle accuracy changes if needed
        }

        private fun updateCompassHeading() {
            // Check if we have both sensor readings
            if (accelerometerReading.all { it == 0f } || magnetometerReading.all { it == 0f }) return


            // Get rotation matrix
            val success = SensorManager.getRotationMatrix(
                rotationMatrix,
                null,
                accelerometerReading,
                magnetometerReading
            )

            if (success) {
                // Get orientation angles
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                // Convert radians to degrees and adjust for compass (0° = North)
                var heading = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

                // Convert from -180° to 180° to 0° to 360°
                if (heading < 0) {
                    heading += 360f
                }

                // Smooth the heading change
                val smoothFactor = 0.2f
                currentHeading = currentHeading * (1 - smoothFactor) + heading * smoothFactor

                // Log for debugging (remove in production)
                if (System.currentTimeMillis() % 2000 < 16) {
                    Log.d("Compass", "Heading: ${currentHeading.toInt()}°")
                }
            }
        }

        private fun startBoot() {
            isBooting = true
            bootStartTime = System.currentTimeMillis()
        }

        private fun drawFrame() {
            if (!visible) return

            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                canvas?.withSave {
                    drawScene(this)
                }
            } catch (e: Exception) {
                Log.e("RadarEngine", "Draw error: ${e.message}")
            } finally {
                try {
                    if (canvas != null) {
                        holder.unlockCanvasAndPost(canvas)
                    }
                } catch (e: Exception) {
                    Log.e("RadarEngine", "Unlock canvas error: ${e.message}")
                }
            }
        }

        private fun drawScene(c: Canvas) {
            val currentTime = System.currentTimeMillis()

            // Draw solid background
            paint.style = Paint.Style.FILL
            paint.color = backgroundColor
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

            now.timeInMillis = currentTime
            val sec = now.get(Calendar.SECOND)
            val min = now.get(Calendar.MINUTE)
            val hr = now.get(Calendar.HOUR_OF_DAY)
            val millis = now.get(Calendar.MILLISECOND)

            val elapsedBoot = currentTime - bootStartTime
            if (isBooting && elapsedBoot < bootDuration) {
                drawBootSequence(c, elapsedBoot.toFloat() / bootDuration)
                return
            } else {
                isBooting = false
            }

            // Check for weather updates
            updateWeatherIfNeeded()

            drawTopoBackground(c, currentTime)
            drawTopographicMap(c, currentTime)
            drawGridOverlay(c, currentTime)
            drawTopBar(c, hr, min, sec)
            drawRadarScanner(c, sec, min, hr, millis, currentTime)
            drawWeatherPanel(c)
            drawCompass(c)
            drawActiveMissions(c)
            drawMissionHUD(c)
            drawBottomStatusBar(c)
            drawCornerBrackets(c)
            drawScanLines(c, currentTime)
        }

        private fun drawBootSequence(canvas: Canvas, progress: Float) {
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            paint.alpha = (200 * (1f - progress)).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

            textPaint.color = neonGreen
            textPaint.textSize = 42f
            val lines = listOf(
                "INITIALIZING FOURTH ECHELON SYSTEMS...",
                "ESTABLISHING SECURE UPLINK...",
                "CALIBRATING SENSORS...",
                "TACTICAL OVERVIEW ONLINE..."
            )
            val shown = (lines.size * progress).toInt().coerceAtLeast(1)
            val yStart = centerY - 80f

            for (i in 0 until shown) {
                val alpha = (255 * (progress - i * 0.25f).coerceIn(0f, 1f)).toInt()
                textPaint.alpha = alpha
                val y = yStart + i * 60f
                canvas.drawText(lines[i], centerX - 380f, y, textPaint)
            }
        }

        private fun drawTopoBackground(c: Canvas, currentTime: Long) {
            // VERY FAST parallax - cinematic surveillance panning
            parallaxLayer1Offset = (sin(currentTime / 5000.0) * 80).toFloat()     // 5-second cycle
            parallaxLayer2Offset = (cos(currentTime / 3500.0) * 120).toFloat()    // 3.5-second cycle
            parallaxLayer3Offset = (sin(currentTime / 2000.0 + 2.0) * 150).toFloat() // 2-second cycle

            // Main background with slow pan
            paint.shader = backgroundGradient
            paint.style = Paint.Style.FILL
            c.withTranslation(
                parallaxLayer1Offset * 0.3f,
                parallaxLayer2Offset * 0.2f
            ) {
                drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }

            // Add dynamic texture layers
            val dynamicGradient1 = LinearGradient(
                parallaxLayer2Offset, parallaxLayer3Offset,
                width.toFloat() + parallaxLayer2Offset, height.toFloat() + parallaxLayer3Offset,
                intArrayOf(
                    "#1000ff88".toColorInt(),
                    Color.TRANSPARENT,
                    "#1000d4ff".toColorInt()
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.MIRROR
            )

            paint.shader = dynamicGradient1
            paint.alpha = 40
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

            paint.shader = null
            paint.alpha = 255

            // Keep your existing pulsing circles (they add nice depth)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = neonGreen

            for (i in 1..3) {
                val radius = radarRadius * 1.5f + i * 80f
                val pulse = (sin(currentTime / 3000.0 + i * 0.8) * 0.3 + 0.7).toFloat()
                paint.alpha = (30 * pulse).toInt()
                c.drawCircle(centerX, centerY, radius, paint)
            }
        }

        private fun drawGridOverlay(c: Canvas, currentTime: Long) {
            val gridOffset = (currentTime % 30000L) / 30000f * 60f

            // Create a 20-second cycle: 10 seconds sweep, 10 seconds pause
            val cycleTime = currentTime % 20000L  // 20 second total cycle

            val scanLineY = if (cycleTime < 10000L) {
                // First 10 seconds: sweep from top to bottom
                (cycleTime / 10000f) * height
            } else {
                // Next 10 seconds: pause (don't draw scan line)
                -100f  // Position it off-screen
            }

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = neonGreen
            paint.alpha = 15  // Very faint grid (reduced from 20)

            val gridSize = 100f
            val startX = -gridOffset % gridSize
            val startY = -gridOffset % gridSize

            var x = startX
            while (x < width) {
                c.drawLine(x, 0f, x, height.toFloat(), paint)
                x += gridSize
            }

            var y = startY
            while (y < height) {
                c.drawLine(0f, y, width.toFloat(), y, paint)
                y += gridSize
            }

            // Only draw the scan line when it's in sweep phase (on screen)
            if (scanLineY >= 0 && scanLineY <= height) {
                paint.shader = scanLineGradient  // Keep your gradient shader
                paint.strokeWidth = 1.5f  // Thinner line
                paint.alpha = 40  // MUCH lower alpha (was 80) - consistently faint!
                c.drawLine(0f, scanLineY, width.toFloat(), scanLineY, paint)
                paint.shader = null
            }
        }

        @SuppressLint("DefaultLocale")
        private fun drawTopBar(c: Canvas, hour: Int, minute: Int, second: Int) {
            val panelHeight = 120f
            val topMargin = 60f //move it down (approx 1cm)

            paint.shader = topBarGradient
            paint.style = Paint.Style.FILL
            c.drawRect(0f, 0f, width.toFloat(), panelHeight, paint)
            paint.shader = null

            drawPanel(c, 24f, 24f + topMargin, 320f, 72f, true)
            textPaint.color = neonGreen
            textPaint.textSize = 18f
            c.drawText("● FOURTH ECHELON", 50f, 50f + topMargin, textPaint)
            textPaint.color = neonTeal
            textPaint.textSize = 14f
            c.drawText("STRATEGIC MISSION INTERFACE", 50f, 70f + topMargin, textPaint)

            // Right panel - Time and Date
            val rightPanelWidth = 280f // Slightly narrower to utilize space better
            val rightPanelX = width - rightPanelWidth - 24f

            drawPanel(c, rightPanelX, 24f + topMargin, rightPanelWidth, 72f, true)

            // Convert to 12-hour format with AM/PM
            val (twelveHour, amPm) = convertTo12Hour(hour)
            val timeText = String.format("%02d:%02d:%02d %s", twelveHour, minute, second, amPm)

            // Right-align the time text
            textPaint.color = neonGreen
            textPaint.textSize = 20f
            textPaint.textAlign = Paint.Align.RIGHT
            c.drawText(timeText, width - 40f, 55f + topMargin, textPaint)

            val dateText = String.format("%02d/%02d/%d",
                now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.MONTH) + 1,
                now.get(Calendar.YEAR))
            textPaint.color = neonTeal
            textPaint.textSize = 14f
            textPaint.textAlign = Paint.Align.RIGHT
            c.drawText(dateText, width - 40f, 75f + topMargin, textPaint)

            // Reset text alignment for other drawing operations
            textPaint.textAlign = Paint.Align.LEFT
        }

        // Helper function to convert 24-hour to 12-hour format
        private fun convertTo12Hour(hour24: Int): Pair<Int, String> {
            return when {
                hour24 == 0 -> Pair(12, "AM")
                hour24 < 12 -> Pair(hour24, "AM")
                hour24 == 12 -> Pair(12, "PM")
                else -> Pair(hour24 - 12, "PM")
            }
        }

        private fun drawRadarScanner(c: Canvas, second: Int, minute: Int, hour: Int, millisecond: Int, currentTime: Long) {
            // SMOOTH AUTOMATIC WATCH MOVEMENT
            val smoothSeconds = second + millisecond / 1000f
            val radarSweepAngle = (smoothSeconds * 6f)

            val minuteAngle = (minute * 6f)
            val hourAngle = ((hour % 12) * 30f + minute * 0.5f)

            // Mounting Base with Shadow
            paint.style = Paint.Style.FILL
            val shadowRadius = radarRadius + 48f
            val shadowGradient = RadialGradient(
                centerX, centerY, shadowRadius,
                "#60000000".toColorInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            paint.shader = shadowGradient
            paint.maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(centerX, centerY, shadowRadius, paint)
            paint.maskFilter = null
            paint.shader = null

            // Main Radar Unit - Protruding 3D effect
            c.save()

            // Outer Metallic Bezel - Multiple Layers
            val bezelRadius = radarRadius + 24f
            val bezelGradient = RadialGradient(
                centerX * 0.7f, centerY * 0.7f, bezelRadius,
                "#1a2028".toColorInt(), "#000000".toColorInt(), Shader.TileMode.CLAMP
            )
            paint.shader = bezelGradient
            paint.style = Paint.Style.FILL
            c.drawCircle(centerX, centerY, bezelRadius, paint)
            paint.shader = null

            // Bezel shadow effects
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = "#1a2028".toColorInt()
            c.drawCircle(centerX, centerY, bezelRadius, paint)

            // Middle Rim with Engraved Marks
            val rimRadius = radarRadius + 16f
            val rimGradient = RadialGradient(
                centerX * 0.8f, centerY * 0.8f, rimRadius,
                "#252d38".toColorInt(), "#0f1419".toColorInt(), Shader.TileMode.CLAMP
            )
            paint.shader = rimGradient
            paint.style = Paint.Style.FILL
            c.drawCircle(centerX, centerY, rimRadius, paint)
            paint.shader = null

            // Tick Marks
            paint.style = Paint.Style.FILL
            paint.color = "#00ff88".toColorInt()
            paint.alpha = 80
            for (i in 0 until 12) {
                val angle = i * 30f
                val angleRad = Math.toRadians(angle.toDouble()).toFloat()
                val tickX = centerX + cos(angleRad) * (radarRadius + 12f)
                val tickY = centerY + sin(angleRad) * (radarRadius + 12f)
                val tickLength = 12f

                c.withRotation(angle, tickX, tickY) {
                    drawRect(
                        tickX - 0.5f, tickY - tickLength / 2,
                        tickX + 0.5f, tickY + tickLength / 2,
                        paint
                    )
                }
            }

            // Screen Recess
            val recessRadius = radarRadius + 8f
            val recessGradient = LinearGradient(
                centerX - recessRadius, centerY - recessRadius,
                centerX + recessRadius, centerY + recessRadius,
                "#0a0e12".toColorInt(), Color.BLACK, Shader.TileMode.CLAMP
            )
            paint.shader = recessGradient
            paint.style = Paint.Style.FILL
            c.drawCircle(centerX, centerY, recessRadius, paint)
            paint.shader = null

            // Inner shadow for recess
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = "#00ff88".toColorInt()
            paint.alpha = 20
            c.drawCircle(centerX, centerY, recessRadius, paint)

            // Glass Screen Surface
            val screenGradient = RadialGradient(
                centerX * 0.9f, centerY * 0.9f, radarRadius,
                "#2000ff88".toColorInt(), "#000a08".toColorInt(), Shader.TileMode.CLAMP
            )
            paint.shader = screenGradient
            paint.style = Paint.Style.FILL
            c.drawCircle(centerX, centerY, radarRadius, paint)
            paint.shader = null

            // Radar Circles
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = "#00ff88".toColorInt()
            paint.alpha = 60

            for (i in 1..3) {
                val circleRadius = radarRadius * i / 4f
                c.drawCircle(centerX, centerY, circleRadius, paint)
            }

            // Cross Hair with gradient
            paint.strokeWidth = 1f
            paint.alpha = 80

            // Vertical line with gradient
            val verticalGradient = LinearGradient(
                centerX, centerY - radarRadius,
                centerX, centerY + radarRadius,
                intArrayOf(Color.TRANSPARENT, "#00ff88".toColorInt(), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.shader = verticalGradient
            c.drawLine(centerX, centerY - radarRadius, centerX, centerY + radarRadius, paint)
            paint.shader = null

            // Horizontal line with gradient
            val horizontalGradient = LinearGradient(
                centerX - radarRadius, centerY,
                centerX + radarRadius, centerY,
                intArrayOf(Color.TRANSPARENT, "#00ff88".toColorInt(), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.shader = horizontalGradient
            c.drawLine(centerX - radarRadius, centerY, centerX + radarRadius, centerY, paint)
            paint.shader = null

            // Rotating Sweep
            c.save()
            c.rotate(radarSweepAngle, centerX, centerY)
            val sweepShader = SweepGradient(
                centerX, centerY,
                intArrayOf(Color.TRANSPARENT, "#00ff88".toColorInt(), Color.TRANSPARENT),
                floatArrayOf(0f, 0.1f, 0.2f)
            )
            paint.shader = sweepShader
            paint.style = Paint.Style.FILL
            paint.alpha = 150
            c.drawCircle(centerX, centerY, radarRadius, paint)
            c.restore()
            paint.shader = null

            // Pulsing Center
            val pulseAnimation = (sin(currentTime / 500.0) * 0.5 + 0.5).toFloat()
            paint.style = Paint.Style.FILL

            // Outer pulse
            paint.color = "#00ff88".toColorInt()
            paint.alpha = (100 * pulseAnimation).toInt()
            c.drawCircle(centerX, centerY, 16f * pulseAnimation, paint)

            // Inner solid center
            paint.color = "#00ff88".toColorInt()
            paint.alpha = 200
            c.drawCircle(centerX, centerY, 8f, paint)

            // HOUR HAND BLIP - RED dot at 50% radius
            val hourBlipLength = radarRadius * 0.5f
            val hourAngleRad = Math.toRadians((hourAngle - 90).toDouble()).toFloat()
            val hourBlipX = centerX + cos(hourAngleRad) * hourBlipLength
            val hourBlipY = centerY + sin(hourAngleRad) * hourBlipLength

            paint.style = Paint.Style.FILL
            paint.color = redAlert
            paint.alpha = (200 * pulseAnimation).toInt()
            c.drawCircle(hourBlipX, hourBlipY, 4f * (0.5f + pulseAnimation * 0.5f), paint)

            // Glow effect for hour blip
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.alpha = (100 * pulseAnimation).toInt()
            c.drawCircle(hourBlipX, hourBlipY, 12f * pulseAnimation, paint)

            // MINUTE HAND BLIP - Green/Teal dot at 75% radius
            val minuteBlipLength = radarRadius * 0.75f
            val minuteAngleRad = Math.toRadians((minuteAngle - 90).toDouble()).toFloat()
            val minuteBlipX = centerX + cos(minuteAngleRad) * minuteBlipLength
            val minuteBlipY = centerY + sin(minuteAngleRad) * minuteBlipLength
            drawAnimatedBlip(c, minuteBlipX, minuteBlipY, pulseAnimation)

            // Screen Glare
            val glareGradient = RadialGradient(
                centerX * 0.7f, centerY * 0.7f, radarRadius,
                "#10FFFFFF".toColorInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            paint.shader = glareGradient
            paint.style = Paint.Style.FILL
            paint.alpha = 80
            c.drawCircle(centerX, centerY, radarRadius, paint)
            paint.shader = null

            // Mounting Screws
            paint.style = Paint.Style.FILL
            for (angle in 0..3) {
                val angleRad = Math.toRadians(angle * 90.0).toFloat()
                val screwX = centerX + cos(angleRad) * (radarRadius + 20f)
                val screwY = centerY + sin(angleRad) * (radarRadius + 20f)
                drawEnhancedScrew(c, screwX, screwY)
            }

            // Mounting Brackets
            paint.style = Paint.Style.FILL
            for (angle in 0..3) {
                val bracketAngle = 45 + angle * 90
                val angleRad = Math.toRadians(bracketAngle.toDouble()).toFloat()
                val bracketX = centerX + cos(angleRad) * (radarRadius + 22f)
                val bracketY = centerY + sin(angleRad) * (radarRadius + 22f)
                drawMountingBracket(c, bracketX, bracketY, bracketAngle.toFloat())
            }

            c.restore() // Restore from 3D transformation

            // Center Label with Hardware Mount
            val labelY = centerY + radarRadius + 80f
            drawLabelPanel(c, centerX, labelY, "TACTICAL OVERVIEW")
        }

        private fun drawAnimatedBlip(c: Canvas, x: Float, y: Float, pulse: Float) {
            // Main blip
            paint.style = Paint.Style.FILL
            paint.color = "#00d4ff".toColorInt()
            paint.alpha = (200 * pulse).toInt()
            c.drawCircle(x, y, 4f * (0.5f + pulse * 0.5f), paint)

            // Glow effect
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.alpha = (100 * pulse).toInt()
            c.drawCircle(x, y, 12f * pulse, paint)

            // Outer ripple
            paint.strokeWidth = 1f
            paint.alpha = (50 * pulse).toInt()
            c.drawCircle(x, y, 20f * pulse, paint)
        }

        private fun drawEnhancedScrew(c: Canvas, x: Float, y: Float) {
            val screwGradient = RadialGradient(
                x, y, 8f,
                "#2a3542".toColorInt(), "#0a0e12".toColorInt(), Shader.TileMode.CLAMP
            )
            paint.shader = screwGradient
            paint.style = Paint.Style.FILL
            c.drawCircle(x, y, 8f, paint)
            paint.shader = null

            // Screw slot
            paint.color = Color.BLACK
            paint.strokeWidth = 1.5f
            c.withRotation(45f, x, y) {
                drawLine(x - 4f, y, x + 4f, y, paint)
            }

            // Border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.5f
            paint.color = "#151a21".toColorInt()
            c.drawCircle(x, y, 8f, paint)
        }

        private fun drawMountingBracket(c: Canvas, x: Float, y: Float, angle: Float) {
            c.withRotation(angle, x, y) {

                val bracketGradient = LinearGradient(
                    x - 16f, y, x + 16f, y,
                    "#1a2028".toColorInt(),
                    "#0d1117".toColorInt(),
                    Shader.TileMode.CLAMP
                )

                paint.shader = bracketGradient
                paint.style = Paint.Style.FILL

                drawRect(x - 16f, y - 2f, x + 16f, y + 2f, paint)

                // reset shader
                paint.shader = null

                // Bracket border
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.5f
                paint.color = "#0a0e12".toColorInt()

                drawRect(x - 16f, y - 2f, x + 16f, y + 2f, paint)
            }
        }

        private fun drawLabelPanel(c: Canvas, x: Float, y: Float, text: String) {
            val panelWidth = 200f
            val panelHeight = 40f

            // Panel background
            val panelGradient = LinearGradient(
                x - panelWidth / 2, y - panelHeight / 2,
                x + panelWidth / 2, y + panelHeight / 2,
                "#1a2028".toColorInt(), "#0d1117".toColorInt(), Shader.TileMode.CLAMP
            )
            paint.shader = panelGradient
            paint.style = Paint.Style.FILL
            rectF.set(x - panelWidth / 2, y - panelHeight / 2, x + panelWidth / 2, y + panelHeight / 2)
            c.drawRoundRect(rectF, 8f, 8f, paint)
            paint.shader = null

            // Panel border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = "#00ff88".toColorInt()
            paint.alpha = 50
            c.drawRoundRect(rectF, 8f, 8f, paint)

            // Text
            textPaint.color = "#00ff88".toColorInt()
            textPaint.textSize = 14f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.alpha = 200
            c.drawText(text, x, y + 5f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }

        private fun drawCompass(c: Canvas) {
            val compassRotation = currentHeading
            val compassWidth = width * 0.8f
            val compassHeight = 80f
            val compassY = 120f + 60f + 20f // top bar height + margin + spacing

            drawPanel(c, centerX - compassWidth / 2, compassY, compassWidth, compassHeight, false)

            paint.style = Paint.Style.FILL
            paint.color = "#000a08CC".toColorInt()
            c.drawRect(centerX - compassWidth / 2 + 10f, compassY + 10f,
                centerX + compassWidth / 2 - 10f, compassY + compassHeight - 10f, paint)

            c.save()
            c.clipRect(centerX - compassWidth / 2 + 10f, compassY + 10f,
                centerX + compassWidth / 2 - 10f, compassY + compassHeight - 10f)

            // WRAP-AROUND COMPASS: Draw multiple copies of the directions to create seamless loop
            val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val directionAngles = floatArrayOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)

            val visibleWidth = compassWidth - 20f
            val pixelsPerDegree = visibleWidth / 360f

            // Draw multiple sets of directions to create wrap-around effect
            val numRepeats = 3 // Draw 3 copies to ensure continuous coverage

            for (repeat in -1 until numRepeats - 1) {
                directions.forEachIndexed { index, dir ->
                    val baseAngle = directionAngles[index] + (repeat * 360f)
                    val relativeAngle = baseAngle - compassRotation

                    val xPos = centerX + (relativeAngle * pixelsPerDegree)

                    // Only draw if within visible area (with some padding)
                    if (xPos in (centerX - compassWidth / 2 - 60f)..(centerX + compassWidth / 2 + 60f)) {

                        // Set colors based on direction
                        when (dir) {
                            "N" -> {
                                textPaint.color = redAlert
                                paint.color = redAlert
                            }
                            "E", "S", "W" -> {
                                textPaint.color = neonGreen
                                paint.color = neonGreen
                            }
                            else -> {
                                textPaint.color = neonTeal
                                paint.color = neonTeal
                            }
                        }

                        // Draw the marker
                        paint.style = Paint.Style.FILL
                        val markerHeight = if (dir in listOf("N", "E", "S", "W")) 20f else 12f
                        c.drawRect(xPos - 1f, compassY + 15f, xPos + 1f, compassY + 15f + markerHeight, paint)

                        // Draw the text
                        textPaint.alpha = 200
                        textPaint.textAlign = Paint.Align.CENTER
                        c.drawText(dir, xPos, compassY + 55f, textPaint)
                    }
                }
            }

            c.restore()
            textPaint.textAlign = Paint.Align.LEFT

            // Center reference line
            paint.color = redAlert
            paint.strokeWidth = 2f
            c.drawLine(centerX, compassY + 10f, centerX, compassY + compassHeight - 10f, paint)

            // Digital readout
            drawPanel(c, centerX + compassWidth / 2 + 20f, compassY + 20f, 80f, 40f, true)
            textPaint.color = neonGreen
            textPaint.textSize = 16f
            textPaint.textAlign = Paint.Align.CENTER

            val cardinalDirection = getCardinalDirection(compassRotation)
            c.drawText("${compassRotation.toInt()}°", centerX + compassWidth / 2 + 60f, compassY + 35f, textPaint)
            textPaint.textSize = 12f
            c.drawText(cardinalDirection, centerX + compassWidth / 2 + 60f, compassY + 52f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }

        private fun getCardinalDirection(heading: Float): String {
            return when (heading) {
                in 337.5..360.0, in 0.0..<22.5 -> "N"
                in 22.5..<67.5 -> "NE"
                in 67.5..<112.5 -> "E"
                in 112.5..<157.5 -> "SE"
                in 157.5..<202.5 -> "S"
                in 202.5..<247.5 -> "SW"
                in 247.5..<292.5 -> "W"
                in 292.5..<337.5 -> "NW"
                else -> "N"
            }
        }


        private fun drawMissionHUD(c: Canvas) {
            updateNetworkStatus()

            val stats = listOf(
                Pair("DEFENSE", "${wifiSignalStrength}%"),  // Green dot, green text
                Pair("SIGNAL", getSignalQualityText(wifiSignalStrength))  // Blue dot, blue text
            )

            val panelWidth = 200f
            val panelHeight = 70f
            val startX = width - panelWidth - 24f
            val startY = centerY - stats.size * panelHeight / 2f

            stats.forEachIndexed { index, (label, value) ->
                val y = startY + index * (panelHeight + 12f)
                drawHUDPanel(c, startX, y, panelWidth, panelHeight, label, value, index)
            }
        }

        private fun drawHUDPanel(c: Canvas, x: Float, y: Float, width: Float, height: Float, label: String, value: String, index: Int) {
            drawPanel(c, x, y, width, height, true)

            paint.style = Paint.Style.FILL

            // Use the existing neonTeal color for all blue elements
            val blueColor = neonTeal  // This is #00d4ff - the blue used elsewhere in your app
            val greenColor = neonGreen  // This is #00ff88 - the green used elsewhere

            if (index == 0) {
                // DEFENSE: Blue label, Green dot, Green percentage

                // Draw green background circles
                paint.color = Color.argb(50, Color.red(greenColor), Color.green(greenColor), Color.blue(greenColor))
                c.drawCircle(x + 30f, y + height / 2, 20f, paint)

                paint.color = Color.argb(100, Color.red(greenColor), Color.green(greenColor), Color.blue(greenColor))
                c.drawCircle(x + 30f, y + height / 2, 16f, paint)

                // Green center dot
                paint.color = greenColor
                c.drawCircle(x + 30f, y + height / 2, 8f, paint)

                // BLUE label for DEFENSE (using your app's blue color)
                textPaint.color = blueColor
                textPaint.textSize = 12f
                c.drawText(label, x + 50f, y + 25f, textPaint)

                // GREEN percentage for DEFENSE
                textPaint.color = greenColor
                textPaint.textSize = 16f
                c.drawText(value, x + 50f, y + 50f, textPaint)

            } else {
                // SIGNAL: Everything blue (label, circles, text) - all using your app's blue color

                // Draw blue background circles
                paint.color = Color.argb(50, Color.red(blueColor), Color.green(blueColor), Color.blue(blueColor))
                c.drawCircle(x + 30f, y + height / 2, 20f, paint)

                paint.color = Color.argb(100, Color.red(blueColor), Color.green(blueColor), Color.blue(blueColor))
                c.drawCircle(x + 30f, y + height / 2, 16f, paint)

                // Draw concentric blue circles (no solid center dot)
                paint.color = blueColor
                paint.strokeWidth = 2f

                // Draw 2-3 concentric circles for the signal icon
                for (i in 1..3) {
                    val radius = 4f + i * 2f
                    paint.style = Paint.Style.STROKE
                    c.drawCircle(x + 30f, y + height / 2, radius, paint)
                }
                paint.style = Paint.Style.FILL

                // BLUE label for SIGNAL
                textPaint.color = blueColor
                textPaint.textSize = 12f
                c.drawText(label, x + 50f, y + 25f, textPaint)

                // BLUE text for SIGNAL
                textPaint.textSize = 16f
                c.drawText(value, x + 50f, y + 50f, textPaint)
            }
        }

        private fun drawBottomStatusBar(c: Canvas) {
            val panelHeight = 120f

            paint.shader = bottomBarGradient
            paint.style = Paint.Style.FILL
            c.drawRect(0f, height - panelHeight, width.toFloat(), height.toFloat(), paint)
            paint.shader = null

            drawPanel(c, 24f, height - 96f, width - 48f, 72f, true)

            textPaint.color = neonGreen
            textPaint.textSize = 14f
            c.drawText("● SYSTEM OPERATIONAL", 50f, height - 70f, textPaint)

            textPaint.color = neonTeal
            textPaint.textSize = 12f
            c.drawText("LAT 38.8977° N / LONG 77.0365° W", 50f, height - 50f, textPaint)

            textPaint.color = neonTeal
            textPaint.textSize = 12f
            textPaint.textAlign = Paint.Align.RIGHT
            c.drawText("SECURITY CLEARANCE", width - 50f, height - 70f, textPaint)

            textPaint.color = neonGreen
            textPaint.textSize = 14f
            c.drawText("LEVEL 5 - CLASSIFIED", width - 50f, height - 50f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }

        private fun drawCornerBrackets(c: Canvas) {
            val bracketSize = 64f
            val strokeWidth = 2f

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = Color.argb(100, Color.red(neonGreen), Color.green(neonGreen), Color.blue(neonGreen))

            c.drawLine(20f, 20f, 20f, 20f + bracketSize, paint)
            c.drawLine(20f, 20f, 20f + bracketSize, 20f, paint)

            c.drawLine(width - 20f, 20f, width - 20f - bracketSize, 20f, paint)
            c.drawLine(width - 20f, 20f, width - 20f, 20f + bracketSize, paint)

            c.drawLine(20f, height - 20f, 20f, height - 20f - bracketSize, paint)
            c.drawLine(20f, height - 20f, 20f + bracketSize, height - 20f, paint)

            c.drawLine(width - 20f, height - 20f, width - 20f - bracketSize, height - 20f, paint)
            c.drawLine(width - 20f, height - 20f, width - 20f, height - 20f - bracketSize, paint)

            paint.style = Paint.Style.FILL
            val rivetPositions = arrayOf(
                floatArrayOf(16f, 16f),
                floatArrayOf(width - 16f, 16f),
                floatArrayOf(16f, height - 16f),
                floatArrayOf(width - 16f, height - 16f)
            )

            rivetPositions.forEach { pos ->
                val rivetGradient = RadialGradient(
                    pos[0], pos[1], 4f,
                    "#2a3542".toColorInt(), "#0a0e12".toColorInt(), Shader.TileMode.CLAMP
                )
                paint.shader = rivetGradient
                c.drawCircle(pos[0], pos[1], 4f, paint)
            }
            paint.shader = null
        }

        private fun drawScanLines(c: Canvas, currentTime: Long) {
            paint.style = Paint.Style.FILL
            paint.color = neonGreen
            paint.alpha = 2

            val lineHeight = 2f
            val lineSpacing = 40f

            var y = (currentTime / 200 % height).toFloat()

            while (y < height) {
                c.drawRect(0f, y, width.toFloat(), y + lineHeight, paint)
                y += lineSpacing
            }

            y = (currentTime / 200 % height).toFloat() - height
            while (y < 0) {
                c.drawRect(0f, y, width.toFloat(), y + lineHeight, paint)
                y += lineSpacing
            }
        }

        private fun drawPanel(c: Canvas, x: Float, y: Float, width: Float, height: Float, withRivets: Boolean) {
            val panelGradient = LinearGradient(
                x, y, x, y + height,
                panelLight, panelDark, Shader.TileMode.CLAMP
            )
            paint.shader = panelGradient
            paint.style = Paint.Style.FILL

            rectF.set(x, y, x + width, y + height)
            c.drawRoundRect(rectF, 8f, 8f, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = Color.argb(50, Color.red(neonGreen), Color.green(neonGreen), Color.blue(neonGreen))
            c.drawRoundRect(rectF, 8f, 8f, paint)

            if (withRivets) {
                paint.style = Paint.Style.FILL
                val rivetRadius = 2f
                val rivetPositions = arrayOf(
                    floatArrayOf(x + rivetRadius + 4, y + rivetRadius + 4),
                    floatArrayOf(x + width - rivetRadius - 4, y + rivetRadius + 4),
                    floatArrayOf(x + rivetRadius + 4, y + height - rivetRadius - 4),
                    floatArrayOf(x + width - rivetRadius - 4, y + height - rivetRadius - 4)
                )

                rivetPositions.forEach { pos ->
                    val rivetGradient = RadialGradient(
                        pos[0], pos[1], rivetRadius,
                        "#2a3542".toColorInt(), "#0a0e12".toColorInt(), Shader.TileMode.CLAMP
                    )
                    paint.shader = rivetGradient
                    c.drawCircle(pos[0], pos[1], rivetRadius, paint)
                }
                paint.shader = null
            }
        }

        private fun drawActiveMissions(c: Canvas) {
            try {
                val wallpaperData = readWallpaperData()

                if (wallpaperData == null) {
                    drawNoTasksMessage(c, "Active Missions")
                    return
                }

                // Parse the data
                val listName = wallpaperData.getString("list_name")
                val timestamp = wallpaperData.getLong("timestamp")
                val tasksArray = wallpaperData.getJSONArray("tasks")

                // Check if data is stale (older than 1 day)
                val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                if (timestamp < oneDayAgo) {
                    drawNoTasksMessage(c, "Active Missions (data outdated)")
                    return
                }

                if (tasksArray.length() == 0) {
                    drawNoTasksMessage(c, listName)
                    return
                }

                // Calculate positions - make panel bigger and stretch to edges
                val compassY = 120f + 60f + 20f // Below top bar
                val compassHeight = 100f
                val panelStartY = compassY + compassHeight + 20f // Start below compass
                val radarTopY = centerY - radarRadius - 60f // Top of radar with margin

                val missionsHeight = radarTopY - panelStartY - 20f // Fill space between compass and radar
                val missionsWidth = width * 0.85f // Wider panel (85% of screen width)
                val missionsX = centerX - missionsWidth / 2

                // Draw missions panel
                drawEnhancedMissionsPanel(c, missionsX, panelStartY, missionsWidth, missionsHeight, listName, tasksArray)

            } catch (e: Exception) {
                Log.e("RadarWallpaper", "Error in drawActiveMissions: ${e.message}")
                e.printStackTrace()
                drawNoTasksMessage(c, "Active Missions")
            }
        }

        private fun readWallpaperData(): JSONObject? {
            val currentTime = System.currentTimeMillis()

            // Return cached data if still valid
            if (cachedWallpaperData != null && currentTime - lastDataLoadTime < dataRefreshInterval) {
                return cachedWallpaperData
            }

            try {
                // Method 1: Try ContentProvider
                try {
                    val uri = "content://com.example.taskwidget.provider/tasks".toUri()
                    val cursor = applicationContext.contentResolver.query(uri, null, null, null, null)

                    cursor?.use {
                        if (it.moveToFirst()) {
                            val jsonColumn = it.getColumnIndex("tasks_json")
                            if (jsonColumn >= 0) {
                                val jsonString = it.getString(jsonColumn)
                                if (jsonString != null && jsonString.isNotEmpty()) {
                                    cachedWallpaperData = JSONObject(jsonString)
                                    lastDataLoadTime = currentTime
                                    return cachedWallpaperData
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d("RadarWallpaper", "ContentProvider not available: ${e.message}")
                }

                // Method 2: Try reading TaskApp's external files directly
                try {
                    val taskAppDir = File("/storage/emulated/0/Android/data/com.example.taskwidget/files")
                    val taskFile = File(taskAppDir, "wallpaper_tasks.json")

                    if (taskFile.exists() && taskFile.canRead()) {
                        val jsonString = taskFile.readText()
                        cachedWallpaperData = JSONObject(jsonString)
                        lastDataLoadTime = currentTime
                        return cachedWallpaperData
                    } else {
                        Log.d("RadarWallpaper", "File doesn't exist or can't read")
                    }
                } catch (e: Exception) {
                    Log.w("RadarWallpaper", "Can't read TaskApp files: ${e.message}")
                }

                // Method 3: Try public Documents directory (Android 9 and below)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    try {
                        val publicFile = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                            "taskapp_wallpaper_data.json"
                        )

                        if (publicFile.exists()) {
                            val jsonString = publicFile.readText()
                            cachedWallpaperData = JSONObject(jsonString)
                            lastDataLoadTime = currentTime
                            return cachedWallpaperData
                        }
                    } catch (e: Exception) {
                        Log.w("RadarWallpaper", "Can't read public Documents: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e("RadarWallpaper", "Error reading wallpaper data: ${e.message}")
                e.printStackTrace()
            }

            Log.d("RadarWallpaper", "No wallpaper data found")
            cachedWallpaperData = null
            lastDataLoadTime = currentTime
            return null
        }

        // Force refresh when broadcast received (override the 1 hour cache):
        private fun refreshTaskData() {
            lastDataLoadTime = 0L // Invalidate cache
            cachedWallpaperData = null
        }

        private fun drawEnhancedMissionsPanel(c: Canvas, x: Float, y: Float, width: Float, height: Float, title: String, tasks: JSONArray) {
            // Draw panel background
            drawPanel(c, x, y, width, height, true)

            // Draw title
            textPaint.color = neonGreen
            textPaint.textSize = 32f // Slightly larger title
            textPaint.textAlign = Paint.Align.LEFT
            c.drawText("● ACTIVE MISSIONS", x + 30f, y + 50f, textPaint)

            // Calculate how many tasks can fit
            val taskStartY = y + 95f // Space after title
            val taskSpacing = 65f // Space between tasks
            val taskTextSize = 30f // Larger text
            val availableHeight = height - 125f // Space for tasks (excluding title and bottom margin)
            val maxTasks = (availableHeight / taskSpacing).toInt().coerceAtLeast(1)

            textPaint.textSize = taskTextSize

            // Draw tasks - as many as fit in the panel
            val tasksToShow = min(tasks.length(), maxTasks)

            for (i in 0 until tasksToShow) {
                try {
                    val task = tasks.getJSONObject(i)
                    val taskText = task.getString("text")
                    val dueDate = if (!task.isNull("dueDate")) task.getLong("dueDate") else null
                    val hasSubtasks = task.getBoolean("hasSubtasks")

                    val taskY = taskStartY + i * taskSpacing

                    // Draw task bullet
                    textPaint.color = neonTeal
                    textPaint.textSize = taskTextSize
                    c.drawText("▪", x + 30f, taskY, textPaint)

                    // Draw task text (truncated to fit width)
                    textPaint.color = hudText
                    val maxTextWidth = width - 180f // Leave space for due date
                    val displayText = truncateText(taskText, maxTextWidth, textPaint)
                    c.drawText(displayText, x + 65f, taskY, textPaint)

                    // Draw due date indicator if exists
                    if (dueDate != null) {
                        val timeLeft = dueDate - System.currentTimeMillis()
                        val daysLeft = timeLeft / (1000 * 60 * 60 * 24)
                        val hoursLeft = timeLeft / (1000 * 60 * 60)

                        val dueText = when {
                            daysLeft >= 7 -> "${daysLeft / 7}w"
                            daysLeft >= 1 -> "${daysLeft}d"
                            hoursLeft >= 1 -> "${hoursLeft}h"
                            else -> "Soon"
                        }

                        textPaint.color = redAlert
                        textPaint.textSize = 22f
                        textPaint.textAlign = Paint.Align.RIGHT
                        c.drawText(dueText, x + width - 30f, taskY - 8f, textPaint)
                        textPaint.textAlign = Paint.Align.LEFT
                        textPaint.textSize = taskTextSize
                    }

                    // Draw subtask indicator if exists
                    if (hasSubtasks) {
                        val completedSubtasks = task.getInt("completedSubtasks")
                        val totalSubtasks = task.getInt("totalSubtasks")

                        textPaint.color = neonTeal
                        textPaint.textSize = 11f
                        c.drawText("($completedSubtasks/$totalSubtasks)", x + 65f, taskY + 24f, textPaint)
                        textPaint.textSize = taskTextSize
                    }

                } catch (e: Exception) {
                    Log.e("RadarEngine", "Error drawing task $i: ${e.message}")
                }
            }

            // Show count if there are more tasks
            if (tasks.length() > tasksToShow) {
                val remaining = tasks.length() - tasksToShow
                textPaint.color = neonTeal
                textPaint.textSize = 22f
                textPaint.textAlign = Paint.Align.RIGHT
                c.drawText("+$remaining more missions...", x + width - 20f, y + height - 20f, textPaint)
                textPaint.textAlign = Paint.Align.LEFT
            }
        }

        // Helper function to truncate text to fit width
        private fun truncateText(text: String, maxWidth: Float, paint: Paint): String {
            if (paint.measureText(text) <= maxWidth) {
                return text
            }

            var truncated = text
            while (paint.measureText("$truncated...") > maxWidth && truncated.isNotEmpty()) {
                truncated = truncated.dropLast(1)
            }
            return "$truncated..."
        }

        private fun drawNoTasksMessage(c: Canvas, title: String) {
            val compassY = 120f + 60f + 20f
            val compassHeight = 100f
            val panelStartY = compassY + compassHeight + 20f
            val radarTopY = centerY - radarRadius - 60f

            val missionsHeight = radarTopY - panelStartY - 20f
            val missionsWidth = width * 0.85f
            val missionsX = centerX - missionsWidth / 2

            drawPanel(c, missionsX, panelStartY, missionsWidth, missionsHeight, true)

            textPaint.color = neonGreen
            textPaint.textSize = 32f
            textPaint.textAlign = Paint.Align.LEFT
            c.drawText("● ACTIVE MISSIONS", missionsX + 20f, panelStartY + 35f, textPaint)

            textPaint.color = hudText
            textPaint.textSize = 26f
            textPaint.textAlign = Paint.Align.CENTER
            c.drawText("No active missions", missionsX + missionsWidth / 2, panelStartY + missionsHeight / 2, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }

        // Weather paint objects
        private val weatherTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudText
            textSize = 24f
            typeface = Typeface.MONOSPACE
            isSubpixelText = true
        }

        private val weatherValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = neonGreen
            textSize = 22f
            typeface = Typeface.MONOSPACE
            isSubpixelText = true
        }

        private val weatherLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = neonTeal
            textSize = 20f
            typeface = Typeface.MONOSPACE
            isSubpixelText = true
        }

        // Weather Methods
        private fun updateWeatherIfNeeded() {
            if (!isWeatherUpdating && shouldUpdateWeather()) {
                CoroutineScope(Dispatchers.IO).launch {
                    fetchRealWeatherData()
                }
            }
        }

        private fun shouldUpdateWeather(): Boolean {
            return (weatherData == null ||
                    System.currentTimeMillis() - lastWeatherUpdate > weatherUpdateInterval) &&
                    !isWeatherUpdating
        }

        private suspend fun fetchRealWeatherData() {
            if (!isNetworkAvailable()) {
                weatherUpdateError = "WEATHER SATELLITE UPLINK OFFLINE"
                isNetworkConnected = false
                isWeatherUpdating = false
                return
            }

            isWeatherUpdating = true
            isNetworkConnected = true

            try {
                // Using OpenWeatherMap API
                val apiKey = BuildConfig.WEATHER_API_KEY

                val currentCity = availableCities[currentCityIndex]
                val lat = currentCity.second.first.toString()
                val lon = currentCity.second.second.toString()

                val weatherJson = fetchJsonFromUrl(
                    "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lon&units=metric&appid=$apiKey"
                )
                val aqiJson = fetchJsonFromUrl(
                    "https://api.openweathermap.org/data/2.5/air_pollution?lat=$lat&lon=$lon&appid=$apiKey"
                )

                val weatherData = parseWeatherData(weatherJson, aqiJson, currentCity.first)
                this.weatherData = weatherData
                lastWeatherUpdate = System.currentTimeMillis()
                weatherUpdateError = null

            } catch (e: Exception) {
                Log.e("Weather", "Error fetching weather: ${e.message}")
                weatherUpdateError = "WEATHER SATELLITE UPLINK OFFLINE"
            } finally {
                isWeatherUpdating = false
            }
        }

        private suspend fun fetchJsonFromUrl(urlString: String): String {
            return withContext(Dispatchers.IO) {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpsURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                try {
                    val inputStream = connection.inputStream
                    val reader = inputStream.bufferedReader()
                    reader.use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }
        }

        private fun parseWeatherData(weatherJson: String, aqiJson: String, location: String): WeatherInfo {
            val weatherObj = JSONObject(weatherJson)
            val main = weatherObj.getJSONObject("main")
            val weatherArray = weatherObj.getJSONArray("weather")
            val weather = weatherArray.getJSONObject(0)
            val wind = weatherObj.getJSONObject("wind")

            // Parse AQI
            val aqiObj = JSONObject(aqiJson)
            val aqiList = aqiObj.getJSONArray("list")
            val aqiData = aqiList.getJSONObject(0)
            val aqiMain = aqiData.getJSONObject("main")

            // Wind direction from degrees
            val windDeg = wind.optDouble("deg", 0.0)
            val windDirection = getWindDirection(windDeg)

            // Create UV index (OpenWeatherMap doesn't provide in free tier)
            //val uvIndex = Random.nextInt(0, 11) // 0-10 scale

            return WeatherInfo(
                temperature = main.getDouble("temp").toInt(),
                condition = weather.getString("main"),
                feelsLike = main.getDouble("feels_like").toInt(),
                tempLow = main.getDouble("temp_min").toInt(),
                tempHigh = main.getDouble("temp_max").toInt(),
                windSpeed = wind.getDouble("speed").toInt(),
                windDirection = windDirection,
                //precipitation = 0.0, // Would need forecast API
                humidity = main.getInt("humidity"),
                pressure = main.getDouble("pressure"),
                aqi = aqiMain.getInt("aqi"),
                //uvIndex = uvIndex,
                lastUpdateTime = System.currentTimeMillis(),
                location = location
            )
        }

        private fun getWindDirection(degrees: Double): String {
            val d = (degrees % 360 + 360) % 360  // normalize to 0–360
            return when (d) {
                in 337.5..360.0, in 0.0..<22.5 -> "N"
                in 22.5..<67.5 -> "NE"
                in 67.5..<112.5 -> "E"
                in 112.5..<157.5 -> "SE"
                in 157.5..<202.5 -> "S"
                in 202.5..<247.5 -> "SW"
                in 247.5..<292.5 -> "W"
                in 292.5..<337.5 -> "NW"
                else -> "N"  // fallback (should never be reached)
            }
        }

        private fun isNetworkAvailable(): Boolean {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }


        // Draw Weather Panel
        private fun drawWeatherPanel(c: Canvas) {
            val panelY = calculateWeatherPanelY()
            val panelWidth = width * 0.75f
            val panelHeight = 500f// Fixed height for 2 rows
            val panelX = centerX - panelWidth / 2

            // Draw main panel
            drawPanel(c, panelX, panelY, panelWidth, panelHeight, true)

            val textOffsetY = 25f // Offset to move all text down

            // Draw title with city name
            weatherTextPaint.color = neonGreen
            weatherTextPaint.textSize = 32f
            val cityName = weatherData?.location ?: "LOCAL"

            c.drawText("● WEATHER CONDITIONS: $cityName", panelX + 20f, panelY + 30f + textOffsetY, weatherTextPaint)

            // Draw toggle button on FAR RIGHT side of panel
            // Position it 20px from the right edge of the panel
            val buttonWidth = 120f
            val buttonX = panelX + panelWidth - buttonWidth - 20f  // 20px from right edge

            // Vertical position - align with title
            val buttonY = panelY + textOffsetY

            // Draw toggle button (right side of title)
            drawCityToggleButton(c, buttonX, buttonY)

            // Check network
            if (!isNetworkConnected) {
                drawOfflineMessage(c, panelX, panelY, panelWidth, panelHeight)
                return
            }

            // Show offline message if there was a network/API problem
            if (weatherUpdateError != null) {
                drawOfflineMessage(c, panelX, panelY, panelWidth, panelHeight)
                return
            }

            // Check if we have weather data
            val weather = weatherData
            if (weather == null) {
                drawLoadingMessage(c, panelX, panelY, panelWidth, panelHeight)
                return
            }

            // Draw source and update time
            weatherLabelPaint.color = neonTeal
            weatherLabelPaint.textSize = 22f
            weatherLabelPaint.textAlign = Paint.Align.RIGHT

            val minutesAgo = if (weather.lastUpdateTime > 0) {
                val minutes = ((System.currentTimeMillis() - weather.lastUpdateTime) / (60 * 1000)).toInt()
                "${minutes}m ago"
            } else {
                "Just now"
            }

            val bottomRightY = panelY + panelHeight - 25f // 50px from bottom
            c.drawText("Src: OPWR | Upd: $minutesAgo",
                panelX + panelWidth - 30f, bottomRightY, weatherLabelPaint)
            weatherLabelPaint.textAlign = Paint.Align.LEFT

            // Format the weather values
            val temperature = "${weather.temperature}°C"
            val feelsLike = "${weather.feelsLike}°C"
            val tempRange = "${weather.tempLow}° / ${weather.tempHigh}°"
            val wind = "${weather.windDirection} ${weather.windSpeed} km/h"
            //val precipitation = "${weather.precipitation}mm"
            val humidity = "${weather.humidity}%"
            val pressure = "${weather.pressure}mb"

            // AQI text
            val aqiText = when (weather.aqi) {
                1 -> "Good"
                2 -> "Fair"
                3 -> "Moderate"
                4 -> "Poor"
                5 -> "Very Poor"
                else -> "Unknown"
            }
            val aqiDisplay = "$aqiText, ${weather.aqi}"

            // UV Index text
            //val uvText = when (weather.uvIndex) {
            //    in 0..2 -> "Low"
            //    in 3..5 -> "Moderate"
            //    in 6..7 -> "High"
            //    in 8..10 -> "Very High"
            //    else -> "Unknown"
            //}

            // Calculate positions for 2 columns
            val columnWidth = panelWidth / 2 - 60f // Two columns with some padding
            val column1X = panelX + 40f
            val column2X = panelX + panelWidth / 2 + 40f

            val availableHeight = panelHeight - 120f // Subtract title area
            val lineSpacing = availableHeight / 6f // Divide by number of lines (5 lines per column)
            val startY = panelY + 100f + textOffsetY // Start well below title

            // COLUMN 1 : Left side
            var currentY = startY

            drawWeatherField(c, column1X, currentY, columnWidth,
                "Temp:", temperature, 1)
            currentY += lineSpacing

            drawWeatherField(c, column1X, currentY, columnWidth,
                "Sky:", weather.condition, 1)
            currentY += lineSpacing

            drawWeatherField(c, column1X, currentY, columnWidth,
                "Feel:", feelsLike, 1)
            currentY += lineSpacing

            drawWeatherField(c, column1X, currentY, columnWidth,
                "Range:", tempRange, 1)
            currentY += lineSpacing

            drawWeatherField(c, column1X, currentY, columnWidth,
                "Wind:", wind, 1)

            // COLUMN 2 : Right side
            currentY = startY

            //drawWeatherField(c, column2X, currentY, columnWidth,
            //    "Precip:", precipitation, 1)
            //currentY += lineSpacing

            drawWeatherField(c, column2X, currentY, columnWidth,
                "Humidity:", humidity, 1)
            currentY += lineSpacing

            drawWeatherField(c, column2X, currentY, columnWidth,
                "Pressure:", pressure, 1)
            currentY += lineSpacing

            // AQI with color coding
            weatherValuePaint.color = when (weather.aqi) {
                4, 5 -> redAlert // Poor/Very Poor = red
                3 -> "#FFAA00".toColorInt() // Moderate = orange
                else -> neonGreen // Good/Fair = green
            }

            drawWeatherField(c, column2X, currentY, columnWidth,
                "AQI:", aqiDisplay, 1)
            currentY += lineSpacing

            // Reset color for UV Index
            weatherValuePaint.color = neonGreen

            //drawWeatherField(c, column2X, currentY, columnWidth,
            //    "UV Index:", uvText, 1)
        }

        private fun drawOfflineMessage(c: Canvas, x: Float, y: Float, width: Float, height: Float) {
            weatherTextPaint.color = redAlert
            weatherTextPaint.textSize = 20f
            weatherTextPaint.textAlign = Paint.Align.CENTER
            c.drawText("WEATHER SATELLITE UPLINK OFFLINE",
                x + width / 2,
                y + height / 2,
                weatherTextPaint)

            weatherTextPaint.textSize = 16f
            weatherTextPaint.color = hudText
            c.drawText("No internet connection",
                x + width / 2,
                y + height / 2 + 40f,
                weatherTextPaint)

            weatherTextPaint.textAlign = Paint.Align.LEFT
        }

        private fun drawLoadingMessage(c: Canvas, x: Float, y: Float, width: Float, height: Float) {
            weatherTextPaint.color = hudText
            weatherTextPaint.textSize = 18f
            weatherTextPaint.textAlign = Paint.Align.CENTER
            c.drawText("Loading weather data...",
                x + width / 2,
                y + height / 2,
                weatherTextPaint)
            weatherTextPaint.textAlign = Paint.Align.LEFT
        }

        private fun drawWeatherField(c: Canvas, x: Float, y: Float, width: Float,
                                     label: String, value: String, sizeType: Int = 1) {
            // sizeType: 1 = normal large, 2 = slightly smaller for longer text

            val labelSize = when (sizeType) {
                1 -> 32f // Normal large
                2 -> 28f // Slightly smaller for longer text
                else -> 32f
            }

            val valueSize = when (sizeType) {
                1 -> 28f // Normal large
                2 -> 24f // Slightly smaller for longer text
                else -> 28f
            }

            weatherLabelPaint.textSize = labelSize
            weatherValuePaint.textSize = valueSize

            // Draw label
            c.drawText(label, x, y, weatherLabelPaint)

            // Draw value
            val labelWidth = weatherLabelPaint.measureText(label)
            val valueX = x + labelWidth + 8f

            // Truncate long text to fit column
            val maxValueWidth = width - labelWidth - 16f
            val displayValue = if (weatherValuePaint.measureText(value) > maxValueWidth) {
                truncateText(value, maxValueWidth, weatherValuePaint)
            } else {
                value
            }

            c.drawText(displayValue, valueX, y, weatherValuePaint)
        }

        //Calculate weather panel position (goes full height)
        private fun calculateWeatherPanelY(): Float {
            // Get the position after the radar (with label)
            val radarBottomY = centerY + radarRadius + 80f // Bottom of radar + label


            // Position weather panel between radar and compass
            // Stretch from below radar to above compass
            return radarBottomY + 40f // Add some spacing
        }

        private fun drawCityToggleButton(c: Canvas, x: Float, y: Float) {
            val buttonWidth = 120f
            val buttonHeight = 40f

            // Button background with gradient
            val buttonGradient = LinearGradient(
                x, y, x, y + buttonHeight,
                "#1a2028CC".toColorInt(), "#0d1117CC".toColorInt(), Shader.TileMode.CLAMP
            )
            paint.shader = buttonGradient
            paint.style = Paint.Style.FILL
            rectF.set(x, y, x + buttonWidth, y + buttonHeight)
            c.drawRoundRect(rectF, 10f, 10f, paint)
            paint.shader = null

            // Button border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = neonTeal
            paint.alpha = 180

            // Outer glow effect
            paint.maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(rectF, 10f, 10f, paint)
            paint.maskFilter = null

            // Inner crisp border
            paint.alpha = 220
            c.drawRoundRect(rectF, 10f, 10f, paint)

            // Get city codes
            val city1Code = "BRM"  // Brampton
            val city2Code = "ASR"  // Amritsar

            // Draw active city background highlight
            paint.style = Paint.Style.FILL
            if (currentCityIndex == 0) {
                // Highlight BRM side with green glow
                paint.color = Color.argb(40, Color.red(neonGreen), Color.green(neonGreen), Color.blue(neonGreen))
                val highlightRect = RectF(x + 3f, y + 3f, x + buttonWidth/2 - 3f, y + buttonHeight - 3f)
                c.drawRoundRect(highlightRect, 8f, 8f, paint)
            } else {
                // Highlight ASR side with green glow
                paint.color = Color.argb(40, Color.red(neonGreen), Color.green(neonGreen), Color.blue(neonGreen))
                val highlightRect = RectF(x + buttonWidth/2 + 3f, y + 3f, x + buttonWidth - 3f, y + buttonHeight - 3f)
                c.drawRoundRect(highlightRect, 8f, 8f, paint)
            }

            // Draw city codes with enhanced styling
            textPaint.textSize = 16f
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textPaint.textAlign = Paint.Align.CENTER

            // BRM (left side)
            if (currentCityIndex == 0) {
                textPaint.color = neonGreen  // Active - bright green
                textPaint.setShadowLayer(3f, 0f, 0f, Color.argb(100, Color.red(neonGreen), Color.green(neonGreen), Color.blue(neonGreen)))
            } else {
                textPaint.color = Color.argb(150, Color.red(neonTeal), Color.green(neonTeal), Color.blue(neonTeal))  // Inactive - dim blue
                textPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
            c.drawText(city1Code, x + buttonWidth/4, y + buttonHeight/2 + 6f, textPaint)

            // ASR (right side)
            if (currentCityIndex == 1) {
                textPaint.color = neonGreen  // Active - bright green
                textPaint.setShadowLayer(3f, 0f, 0f, Color.argb(100, Color.red(neonGreen), Color.green(neonGreen), Color.blue(neonGreen)))
            } else {
                textPaint.color = Color.argb(150, Color.red(neonTeal), Color.green(neonTeal), Color.blue(neonTeal))  // Inactive - dim blue
                textPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
            c.drawText(city2Code, x + 3*buttonWidth/4, y + buttonHeight/2 + 6f, textPaint)

            // Draw center divider line
            paint.style = Paint.Style.FILL
            paint.color = neonTeal
            paint.alpha = 100
            val dividerWidth = 1.5f
            c.drawRect(
                x + buttonWidth/2 - dividerWidth/2,
                y + 8f,
                x + buttonWidth/2 + dividerWidth/2,
                y + buttonHeight - 8f,
                paint
            )

            // Draw active indicator (small triangle below active city)
            textPaint.textSize = 10f
            textPaint.color = neonGreen
            textPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)

            val indicatorY = y + buttonHeight - 2f
            if (currentCityIndex == 0) {
                c.drawText("▲", x + buttonWidth/4, indicatorY, textPaint)
            } else {
                c.drawText("▲", x + 3*buttonWidth/4, indicatorY, textPaint)
            }

            // Reset
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = Typeface.MONOSPACE
            textPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            paint.style = Paint.Style.FILL
        }

        //Handle touch events for city switching
        override fun onTouchEvent(event: MotionEvent) {
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.x
                val y = event.y

                // Calculate weather panel position - MUST MATCH drawWeatherPanel()
                val panelY = calculateWeatherPanelY()
                val panelWidth = width * 0.75f
                val panelX = centerX - panelWidth / 2

                // EXACTLY the same calculation as in drawCityToggleButton()
                val buttonWidth = 120f
                val buttonX = panelX + panelWidth - buttonWidth - 20f  // 20px from right edge
                val textOffsetY = 25f  // Must match drawWeatherPanel()
                val buttonY = panelY + textOffsetY  // Align with title
                val buttonHeight = 40f  // Must match drawCityToggleButton()

                // Check if tap is on toggle button
                if (x >= buttonX && x <= buttonX + buttonWidth &&
                    y >= buttonY && y <= buttonY + buttonHeight) {

                    // Determine which side was tapped
                    val tapRelativeX = x - buttonX

                    if (tapRelativeX < buttonWidth / 2) {
                        // Tapped left side (BRM)
                        if (currentCityIndex != 0) {
                            currentCityIndex = 0
                            triggerCitySwitch()
                        }
                    } else {
                        // Tapped right side (ASR)
                        if (currentCityIndex != 1) {
                            currentCityIndex = 1
                            triggerCitySwitch()
                        }
                    }
                    // In WallpaperService.Engine, we can't return true to consume
                    // but we've handled it, so that's fine
                }
            }
            // Still call super to allow wallpaper engine to handle other touches
            super.onTouchEvent(event)
        }

        private fun triggerCitySwitch() {
            // Force weather update for selected city
            CoroutineScope(Dispatchers.IO).launch {
                fetchRealWeatherData()
            }

            // Haptic feedback
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+ (API 31+)
                    val vm = applicationContext.getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    // Older devices
                    @Suppress("DEPRECATION")
                    applicationContext.getSystemService(VIBRATOR_SERVICE) as Vibrator
                }

                if (vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(30)
                    }
                }
            } catch (_: Exception) {
                Log.d("RadarEngine", "Haptic feedback not available")
            }


            // Redraw immediately
            drawHandler.post(drawRunnable)
        }

        private fun loadTopoMap() {
            try {
                // Load the topographic map
                val resources = applicationContext.resources
                val options = BitmapFactory.Options()
                options.inScaled = true  // Allow scaling
                options.inPreferredConfig = Bitmap.Config.ARGB_8888

                // Load from drawable
                topoBitmap = BitmapFactory.decodeResource(resources, R.drawable.topo_map, options)

                topoBitmap?.let {
                    // Scale bitmap to fit screen while maintaining aspect ratio
                    val scale = max(width.toFloat() / it.width, height.toFloat() / it.height) * 1.5f
                    topoBitmapWidth = it.width * scale
                    topoBitmapHeight = it.height * scale
                }

                Log.d("RadarEngine", "Topographic map loaded: ${topoBitmap?.width} x ${topoBitmap?.height}")
            } catch (e: Exception) {
                Log.e("RadarEngine", "Failed to load topographic map: ${e.message}")
            }
        }

        private fun drawTopographicMap(c: Canvas, currentTime: Long) {
            val topo = topoBitmap

            topo ?: return

            // Calculate slow parallax movement (optional)
            if (currentTime - topoLastUpdate > 100) {
                // Move very slowly for subtle effect
                topoOffsetX = (sin(currentTime / 15000.0) * 20).toFloat()
                topoOffsetY = (cos(currentTime / 17000.0) * 15).toFloat()
                topoLastUpdate = currentTime
            }

            // Calculate position to center the map
            val drawX = (width - topoBitmapWidth) / 2f + topoOffsetX
            val drawY = (height - topoBitmapHeight) / 2f + topoOffsetY

            // Draw the topographic map
            c.save()

            // Apply subtle tint to match your color scheme
            val tintPaint = Paint().apply {
                colorFilter = PorterDuffColorFilter(
                    "#2000ff88".toColorInt(),  // Green tint with low alpha
                    PorterDuff.Mode.SRC_ATOP
                )
                alpha = 30  // Very subtle
            }

            // Draw the bitmap
            c.drawBitmap(topo, null,
                RectF(drawX, drawY, drawX + topoBitmapWidth, drawY + topoBitmapHeight),
                tintPaint)

            // Draw a second copy for seamless scrolling (optional)
            if (topoBitmapWidth < width * 1.5f) {
                c.drawBitmap(topo, null,
                    RectF(drawX + topoBitmapWidth, drawY,
                        drawX + topoBitmapWidth * 2, drawY + topoBitmapHeight),
                    tintPaint)
            }

            c.restore()
        }

        private fun updateNetworkStatus() {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            if (capabilities != null) {
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        isWifiConnected = true
                        isMobileConnected = false
                        currentNetworkType = "WIFI"
                        wifiSignalStrength = getWifiSignalStrength()
                    }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        isWifiConnected = false
                        isMobileConnected = true
                        currentNetworkType = "MOBILE"
                        // mobileSignalStrength = getMobileSignalStrength()
                    }
                    else -> {
                        isWifiConnected = false
                        isMobileConnected = false
                        currentNetworkType = "OTHER"
                    }
                }
            } else {
                isWifiConnected = false
                isMobileConnected = false
                currentNetworkType = "DISCONNECTED"
                wifiSignalStrength = 0
                mobileSignalStrength = 0
            }
        }

        private fun getWifiSignalStrength(): Int {
            return try {
                val connectivityManager =
                    applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                    val wifiInfo: WifiInfo? = wifiManager.connectionInfo  // still works, but deprecated in API 33
                    if (wifiInfo != null) {
                        // Calculate signal level (0-100)
                        WifiManager.calculateSignalLevel(wifiInfo.rssi, 100)
                    } else {
                        0
                    }
                } else {
                    0
                }
            } catch (e: Exception) {
                Log.e("RadarEngine", "Error getting WiFi signal: ${e.message}")
                0
            }
        }

        private fun getSignalQualityText(signalStrength: Int): String {
            return when {
                signalStrength >= 80 -> "EXCELLENT"
                signalStrength >= 60 -> "STRONG"
                signalStrength >= 40 -> "GOOD"
                signalStrength >= 20 -> "WEAK"
                else -> "OFFLINE"
            }
        }

    }

    private data class WeatherInfo(
        val temperature: Int,
        val condition: String,
        val feelsLike: Int,
        val tempLow: Int,
        val tempHigh: Int,
        val windSpeed: Int,
        val windDirection: String,
        //val precipitation: Double,
        val humidity: Int,
        val pressure: Double,
        val aqi: Int,
        //val uvIndex: Int,
        val lastUpdateTime: Long,
        val location: String = "LOCAL"
    )
}