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

    // +--------------+
    // | UI data vars |
    // +--------------+
    private var updatedTrack = false

    // +------------------+
    // | MediaPlayer vars |
    // +------------------+
    private lateinit var mediaPlayer: MediaPlayer
    private var playingMediaPlayer: MediaPlayer? = null
    private var nextMediaPlayer: MediaPlayer? = null
    private var playingResourceFd: AssetFileDescriptor? = null
    private var nextResourceFd: AssetFileDescriptor? = null
    private var isNextPrepared = false
    private lateinit var trackStartTimestamp: TimeMark
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

        // Initialize Buttons
        startTestButton.setOnClickListener { startTest() }

        // Initialize media
        readTestTracks()
        mediaPlayer = createMediaPlayer(0)

        // Initialize sensors
        setupSensors()
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
        playingMediaPlayer = mediaPlayer
        playingTrackId = 0

        trackNameTextView.text = trackNames[playingTrackId]
        trackIdTextView.text = "$playingTrackId/${tracks.size}"

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

        player.setDataSource(assets.openFd(tracks[id]))
        player.prepare()

        player.setVolume(1f, 1f)

        player.setOnCompletionListener {
            player.stop()
            player.reset()
            player.release()

            if (id + 1 < tracks.size) {
                val nextPlayer = createMediaPlayer(id + 1)
                playingMediaPlayer = nextPlayer
                playingTrackId = id + 1

                trackNameTextView.text = trackNames[playingTrackId]
                trackIdTextView.text = "$playingTrackId/${tracks.size}"

                nextPlayer.start()
                trackStartTimestamp = timeSource.markNow()
            } else {
                endTest()
            }
        }

        return player
    }
}
