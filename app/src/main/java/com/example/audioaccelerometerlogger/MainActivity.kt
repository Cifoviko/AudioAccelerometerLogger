package com.example.audioaccelerometerlogger

import android.annotation.SuppressLint
import android.content.res.AssetFileDescriptor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource


class MainActivity : ComponentActivity(), SensorEventListener {

    // +------------------+
    // | System utilities |
    // +------------------+
    private lateinit var sensorManager: SensorManager
    private val timeSource = TimeSource.Monotonic

    // +---------+
    // | UI vars |
    // +---------+
    private lateinit var hzTextView: TextView
    private lateinit var trackNameTextView: TextView
    private lateinit var trackIdTextView: TextView
    private lateinit var trackProgressBar: ProgressBar
    private lateinit var startTestButton: Button
    private lateinit var startEditTextNumber: EditText
    private lateinit var endEditTextNumber: EditText

    // +--------------+
    // | UI data vars |
    // +--------------+
    private var updatedTrack = false

    // +------------------+
    // | MediaPlayer vars |
    // +------------------+
    private var playingMediaPlayer: MediaPlayer? = null
    private lateinit var trackStartTimestamp: TimeMark
    private var startTrackId = 0
    private var endTrackId = 0
    private var playingTrackId = 0
    private lateinit var tracks: ArrayList<String>
    private lateinit var trackNames: ArrayList<String>

    // +--------------------+
    // | Accelerometer Data |
    // +--------------------+
    private lateinit var lastBatchPull: TimeMark
    private val hzCalculationMeasurementCount = 25
    private var measurementCount = 0

    // +-----------+
    // | Test Data |
    // +-----------+
    private lateinit var dataFile: File

    // +------------------------------------------------------------------------------------------+
    // | ================================= Main Activity ======================================== |
    // +------------------------------------------------------------------------------------------+
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set layout
        setContentView(R.layout.main_layout)

        // Keep screen on for long tests
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initializing UI elements
        hzTextView = findViewById(R.id.hzTextView)
        trackNameTextView = findViewById(R.id.trackNameTextView)
        trackIdTextView = findViewById(R.id.trackIdTextView)
        trackProgressBar = findViewById(R.id.trackProgressBar)
        startTestButton = findViewById(R.id.startTestButton)
        startEditTextNumber = findViewById(R.id.startEditTextNumber)
        endEditTextNumber = findViewById(R.id.endEditTextNumber)

        // Initialize Buttons
        startTestButton.setOnClickListener { startTest() }

        // Initialize media
        readTestTracks()

        // Initialize sensors
        setupSensors()

        // Initialize UI data
        trackIdTextView.text = "0/${tracks.size}"
        startEditTextNumber.hint = "0"
        endEditTextNumber.hint = tracks.size.toString()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        return
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            // Sensor Data
            val accelerometerX = event.values[0].toDouble()
            val accelerometerY = event.values[1].toDouble()
            val accelerometerZ = event.values[2].toDouble()
            logAccelerometerData(accelerometerX, accelerometerY, accelerometerZ)

            // Calculate Hz
            measurementCount++
            if (measurementCount == hzCalculationMeasurementCount) {
                updateHzView(hzCalculationMeasurementCount.seconds / lastBatchPull.elapsedNow())
                measurementCount = 0
                lastBatchPull = timeSource.markNow()
            }
        }
    }

    private fun setupSensors() {
        // +------------------------------+
        // | Setting up sensorManager and |
        // | Register all needed sensors  |
        // +------------------------------+

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(
                this, it, SensorManager.SENSOR_DELAY_FASTEST, SensorManager.SENSOR_DELAY_FASTEST
            )
        }

        lastBatchPull = timeSource.markNow()
    }

    @SuppressLint("SetTextI18n")
    private fun updateHzView(hz: Double) {
        hzTextView.text = "%.1fHz".format(hz)
    }

    @SuppressLint("SetTextI18n")
    private fun updateTrackInfo() {
        trackNameTextView.text = trackNames[playingTrackId]
        trackIdTextView.text = "${playingTrackId - startTrackId}/${endTrackId - startTrackId}"
    }

    private fun logAccelerometerData(x: Double, y: Double, z: Double) {
        val currentTimestamp = System.currentTimeMillis()

        if (playingMediaPlayer != null && playingMediaPlayer!!.isPlaying) {
            val mediaTimestamp = playingMediaPlayer!!.currentPosition
            val calculatedMediaTimestamp = trackStartTimestamp.elapsedNow().inWholeMilliseconds
            val trackName = trackNames[playingTrackId]

            dataFile.appendText("$currentTimestamp, $x, $y, $z, $mediaTimestamp, $calculatedMediaTimestamp, $trackName\n")
            // Log.d("DEVEL", "Writing log: $currentTimestamp, $x, $y, $z, $mediaTimestamp, $trackName")
        }
    }

    private fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            capitalize(model)
        } else {
            capitalize(manufacturer) + "_" + model
        }
    }

    private fun capitalize(s: String?): String {
        if (s.isNullOrEmpty()) {
            return ""
        }
        val first = s[0]
        return if (Character.isUpperCase(first)) {
            s
        } else {
            first.uppercaseChar().toString() + s.substring(1)
        }
    }

    @SuppressLint("SetTextI18n", "SimpleDateFormat")
    fun startTest() {
        // Update Relevant UI
        trackNameTextView.text = "Test"
        startTestButton.background.setTint(ContextCompat.getColor(this, R.color.red))

        // Initialize log file
        val time = Calendar.getInstance().time
        val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm")
        val currentDate = formatter.format(time)
        val filename = getDeviceName() + "_" + currentDate + ".txt"
        dataFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), filename)
        Log.d("DEVEL", "Started test, log file: $filename")
        dataFile.writeText("Timestamp, X, Y, Z, MediaTimestamp, CalculatedMediaTimestamp, TrackName\n")

        // Restart MediaPlayer
        startTrackId = 0
        if (startEditTextNumber.text.isNotEmpty()) {
            startTrackId = startEditTextNumber.text.toString().toInt()
        }

        endTrackId = tracks.size
        if (endEditTextNumber.text.isNotEmpty()) {
            endTrackId = endEditTextNumber.text.toString().toInt()
        }

        val mediaPlayer = createMediaPlayer(startTrackId)
        playingMediaPlayer = mediaPlayer
        playingTrackId = startTrackId

        updateTrackInfo()

        mediaPlayer.start()
        trackStartTimestamp = timeSource.markNow()
    }

    @SuppressLint("SetTextI18n")
    fun endTest()  {
        // Update Relevant UI
        trackNameTextView.text = "Standby"
        startTestButton.background.setTint(ContextCompat.getColor(this, R.color.yellow))

        playingMediaPlayer = null
    }

    private fun readTestTracks() {
        tracks = ArrayList<String>()
        trackNames = ArrayList<String>()

        val trackFolder = "tracks"
        val types = arrayOf("train", "test")

        types.forEach { type ->
            resources.assets.list("$trackFolder/$type")?.forEach { fileName ->
                val trackName = type + "_" + fileName.split(".wav")[0]
                tracks.add("$trackFolder/$type/$fileName")
                trackNames.add(trackName)

                Log.d("DEVEL", "Track added: $trackName")
            }
        }
    }

    private fun createMediaPlayer(id: Int): MediaPlayer {
        val player = MediaPlayer()

        val assetFd = assets.openFd(tracks[id])
        player.setDataSource(assetFd)
        player.prepare()

        player.setVolume(1f, 1f)

        player.setOnCompletionListener {
            player.stop()
            player.reset()
            player.release()

            assetFd.close()

            if (id + 1 < endTrackId) {
                val nextPlayer = createMediaPlayer(id + 1)
                playingMediaPlayer = nextPlayer
                playingTrackId = id + 1

                updateTrackInfo()

                nextPlayer.start()
                trackStartTimestamp = timeSource.markNow()
            } else {
                endTest()
            }
        }

        return player
    }
}
