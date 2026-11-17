package dev.oddhealth.app.ring

import dev.oddhealth.app.ui.model.RingEvent
import dev.oddhealth.app.ui.model.RingStatus
import dev.oddhealth.app.ui.model.WearState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalTime

/** Live ring state from the Bluetooth service, for the UI. Not persisted (except firmware, in settings). */
class RingState(firmware: String?) {
    private val _status = MutableStateFlow(
        RingStatus(name = "Even R1", connected = false, batteryPercent = null, charging = false, wear = WearState.UNKNOWN,
            firmware = firmware, lastData = null),
    )
    val status: StateFlow<RingStatus> = _status.asStateFlow()

    private val _events = MutableStateFlow<List<RingEvent>>(emptyList())
    val events: StateFlow<List<RingEvent>> = _events.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun update(change: (RingStatus) -> RingStatus) = _status.update(change)

    fun setRunning(on: Boolean) {
        _running.value = on
        if (!on) update { it.copy(connected = false) }
    }

    fun dataReceived() = update { it.copy(lastData = Instant.now()) }

    /** A short human-readable line for the Ring screen; the newest first, 50 kept. */
    fun event(text: String) = _events.update { (listOf(RingEvent(LocalTime.now().withSecond(0).withNano(0), text)) + it).take(50) }
}
