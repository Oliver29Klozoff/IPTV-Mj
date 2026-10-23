package com.iptvapp.ui.player

/** Local timings only; never includes URLs, names or credentials. Ready is not first video. */
class TuneTrace(private val clock: () -> Long, private val emit: (String) -> Unit) {
    private var serial = 0L
    private var start = 0L
    private var mediaId: String? = null
    private var active = false
    private var bufferingAt: Long? = null
    private var bufferedMs = 0L
    private val seen = mutableSetOf<String>()

    fun select(mode: String) {
        if (active && "video" !in seen) emit("tune=$serial outcome=superseded")
        serial++
        start = clock()
        active = true
        bufferingAt = null
        bufferedMs = 0
        mediaId = null
        seen.clear()
        emit("tune=$serial mode=$mode event=selection")
    }

    fun commit(id: String) { if (active) mediaId = id }

    fun event(id: String?, name: String) {
        if (!active || id == null || id != mediaId) return
        if (name == "buffering" && bufferingAt == null) bufferingAt = clock()
        if (name == "ready") {
            bufferingAt?.let { bufferedMs += clock() - it }
            bufferingAt = null
            emit("tune=$serial bufferingMs=$bufferedMs")
        }
        if (!seen.add(name)) return
        emit("tune=$serial event=$name elapsedMs=${clock() - start}")
    }

    fun cancel() {
        if (active && "video" !in seen) emit("tune=$serial outcome=cancelled")
        mediaId = null
        active = false
        bufferingAt = null
    }
}
