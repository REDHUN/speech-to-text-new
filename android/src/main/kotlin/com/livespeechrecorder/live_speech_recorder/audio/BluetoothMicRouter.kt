package com.livespeechrecorder.live_speech_recorder.audio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Routes microphone capture to a connected Bluetooth headset.
 *
 * A Bluetooth headset microphone only carries audio while the headset's voice link (classic SCO,
 * or LE Audio on Android 12+) is up, and Android never brings that link up for an ordinary
 * AudioRecord. Without this router, capture uses the built-in microphone even while a headset is
 * connected.
 *
 * Android 12+ requests the link with [AudioManager.setCommunicationDevice]. Older versions use
 * [AudioManager.startBluetoothSco]. Headsets that connect or disconnect during capture are
 * followed, and [listener] is told whenever [activeInput] changes.
 */
class BluetoothMicRouter(context: Context) {

    fun interface Listener {
        /** [activeInput] changed. Called on the main thread. */
        fun onBluetoothInputChanged()
    }

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val handlerExecutor = Executor { handler.post(it) }
    private val lock = Any()

    @Volatile var listener: Listener? = null

    private var started = false
    /** This router holds a link request that it must release. */
    private var linkRequested = false
    private var linkUp = false
    private var input: AudioDeviceInfo? = null
    private var pendingConnect: CountDownLatch? = null

    // Pre-Android 12 only.
    private var scoReceiverRegistered = false
    private var scoInput: AudioDeviceInfo? = null

    // Android 12+ only. Held as Any because the listener type does not exist on older versions.
    private var communicationListener: Any? = null
    private var requestedDeviceId: Int? = null

    /**
     * The Bluetooth microphone that capture should prefer, or null when no headset voice link is up.
     */
    val activeInput: AudioDeviceInfo?
        get() = synchronized(lock) { if (linkUp) input else null }

    /** The headset's voice link is up, so capture is routed to it even if [activeInput] is null. */
    val isActive: Boolean
        get() = synchronized(lock) { linkUp }

    /**
     * Brings up the voice link of a connected Bluetooth headset and follows headset changes until
     * [stop]. Blocking: waits up to [timeoutMs] for the link, so call it from a background thread.
     *
     * @return true if capture will use a Bluetooth headset microphone.
     */
    fun start(timeoutMs: Long = CONNECT_TIMEOUT_MS): Boolean {
        val latch = CountDownLatch(1)
        synchronized(lock) {
            if (started) return linkUp
            started = true
            pendingConnect = latch
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerCommunicationListenerLocked()
            if (!requestLinkLocked() || linkUp) {
                pendingConnect = null
                latch.countDown()
            }
        }
        // Reports devices that appear later. It also replays current devices, which is harmless.
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Bluetooth headset microphone did not connect within $timeoutMs ms.")
        }
        synchronized(lock) {
            pendingConnect = null
            return linkUp
        }
    }

    /** Releases the voice link so the headset returns to its normal (media) mode. */
    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        synchronized(lock) {
            if (!started) return
            started = false
            releaseLinkLocked()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) unregisterCommunicationListenerLocked()
            unregisterScoReceiverLocked()
            linkUp = false
            input = null
            pendingConnect?.countDown()
            pendingConnect = null
        }
    }

    /** A Bluetooth headset microphone is connected, whether or not its voice link is up. */
    fun hasBluetoothInput(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type in bluetoothVoiceTypes() }

    /** @return true if a link request is pending or already satisfied. */
    private fun requestLinkLocked(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestCommunicationDeviceLocked()
        } else {
            requestScoLocked()
        }
    } catch (e: RuntimeException) {
        // Some OEM builds throw instead of returning false when the headset is busy.
        Log.w(TAG, "Could not request the Bluetooth headset microphone.", e)
        false
    }

    private fun releaseLinkLocked() {
        if (!linkRequested) return
        linkRequested = false
        requestedDeviceId = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not release the Bluetooth headset microphone.", e)
        }
    }

    private fun setLinkLocked(up: Boolean, newInput: AudioDeviceInfo?) {
        if (up) pendingConnect?.countDown()
        val changed = up != linkUp || newInput?.id != input?.id
        linkUp = up
        input = if (up) newInput else null
        if (changed && started) handler.post { listener?.onBluetoothInputChanged() }
    }

    // region Android 12+

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestCommunicationDeviceLocked(): Boolean {
        val target = audioManager.availableCommunicationDevices
            .filter { it.type in bluetoothVoiceTypes() }
            .minByOrNull { if (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) 0 else 1 }
            ?: return false

        if (!linkRequested || requestedDeviceId != target.id) {
            if (!audioManager.setCommunicationDevice(target)) {
                Log.w(TAG, "Android refused to route communication audio to ${target.productName}.")
                return false
            }
            linkRequested = true
            requestedDeviceId = target.id
        }
        // The link may already be up, for example when the headset is in use by a call app.
        onCommunicationDeviceLocked(audioManager.communicationDevice)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun onCommunicationDeviceLocked(device: AudioDeviceInfo?) {
        if (device == null || device.type !in bluetoothVoiceTypes()) {
            setLinkLocked(false, null)
            return
        }
        setLinkLocked(true, matchingInput(device))
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerCommunicationListenerLocked() {
        if (communicationListener != null) return
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            synchronized(lock) {
                if (started) onCommunicationDeviceLocked(device)
            }
        }
        audioManager.addOnCommunicationDeviceChangedListener(handlerExecutor, listener)
        communicationListener = listener
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregisterCommunicationListenerLocked() {
        val listener = communicationListener as AudioManager.OnCommunicationDeviceChangedListener? ?: return
        communicationListener = null
        audioManager.removeOnCommunicationDeviceChangedListener(listener)
    }

    /** Communication devices are outputs. Finds the input half of the same headset. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun matchingInput(output: AudioDeviceInfo): AudioDeviceInfo? {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type == output.type }
        return inputs.firstOrNull { it.address == output.address } ?: inputs.firstOrNull()
    }

    // endregion

    // region Before Android 12

    private fun requestScoLocked(): Boolean {
        @Suppress("DEPRECATION")
        if (!audioManager.isBluetoothScoAvailableOffCall) return false
        val headsetInput = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return false

        scoInput = headsetInput
        if (linkRequested) return true

        registerScoReceiverLocked()
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        linkRequested = true
        return true
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
            synchronized(lock) {
                if (!started) return
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        // Routes recording to SCO on versions that do not do it automatically.
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = true
                        setLinkLocked(true, scoInput)
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        // The sticky replay describes the state before this router asked for SCO.
                        if (isInitialStickyBroadcast) return
                        setLinkLocked(false, null)
                        pendingConnect?.countDown()
                    }
                }
            }
        }
    }

    // Only runs before Android 12, and SCO state is a protected system broadcast, so no export flag.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScoReceiverLocked() {
        if (scoReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        appContext.registerReceiver(scoReceiver, filter, null, handler)
        scoReceiverRegistered = true
    }

    private fun unregisterScoReceiverLocked() {
        if (!scoReceiverRegistered) return
        scoReceiverRegistered = false
        try {
            appContext.unregisterReceiver(scoReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
    }

    // endregion

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.none { it.type in bluetoothVoiceTypes() }) return
            synchronized(lock) {
                if (started && !linkUp) requestLinkLocked()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            synchronized(lock) {
                if (!started) return
                val current = input ?: scoInput
                if (current == null || removedDevices.none { it.id == current.id }) return
                // The headset went away. Drop the request so a reconnect starts a fresh one.
                releaseLinkLocked()
                scoInput = null
                setLinkLocked(false, null)
            }
        }
    }

    private companion object {
        const val TAG = "LiveSpeechBtMic"
        const val CONNECT_TIMEOUT_MS = 3_000L

        fun bluetoothVoiceTypes(): Set<Int> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
            } else {
                setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            }
    }
}
