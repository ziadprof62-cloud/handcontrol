package com.handcontrol.phone

import kotlin.math.min

/**
 * Turns the model's per-frame output into stable gestures and fires
 * actions with a hold time (dwell). "Once" gestures fire one time per
 * hold and must be released; "repeat" gestures keep firing.
 */
class GestureEngine(private val onAction: (Action) -> Unit) {

    enum class Action { PLAY, PAUSE, VOLUME_UP, VOLUME_DOWN }

    class Def(
        val title: String,
        val hint: String,
        val action: Action,
        val repeat: Boolean,
        val dwellMs: Long
    )

    class State(
        val handPresent: Boolean,
        val label: String,
        val def: Def?,
        val progress: Float
    )

    companion object {
        val DEFS = mapOf(
            "Open_Palm" to Def("تشغيل", "ثبّت كفك مفتوحاً", Action.PLAY, false, 600),
            "Closed_Fist" to Def("إيقاف", "ثبّت القبضة", Action.PAUSE, false, 600),
            "Thumb_Up" to Def("رفع الصوت", "ابقِ الإبهام لأعلى", Action.VOLUME_UP, true, 250),
            "Thumb_Down" to Def("خفض الصوت", "ابقِ الإبهام لأسفل", Action.VOLUME_DOWN, true, 250)
        )

        const val MIN_SCORE = 0.60f
        const val HISTORY = 5
        const val MIN_MATCHES = 3
        const val NO_HAND_FRAMES = 6
        const val REPEAT_MS = 250L
    }

    private val history = ArrayDeque<String>()
    private var stable = "None"
    private var noHand = 0

    private var current = "None"
    private var since = 0L
    private var fired = false
    private var lastRepeat = 0L

    @Synchronized
    fun reset() {
        history.clear()
        stable = "None"
        current = "None"
        noHand = 0
        fired = false
        lastRepeat = 0L
    }

    @Synchronized
    fun update(name: String?, score: Float, hand: Boolean, now: Long): State {

        if (hand) {

            noHand = 0

            val label =
                if (name != null && DEFS.containsKey(name) && score >= MIN_SCORE) name
                else "Other"

            history.addLast(label)
            if (history.size > HISTORY) history.removeFirst()

            val best = history.groupingBy { it }.eachCount().maxByOrNull { it.value }

            if (best != null && best.value >= MIN_MATCHES) {
                stable = best.key
            }

        } else {

            noHand++

            if (noHand >= NO_HAND_FRAMES) {
                history.clear()
                stable = "None"
            }
        }

        if (stable != current) {
            current = stable
            since = now
            fired = false
            lastRepeat = 0L
        }

        val def = DEFS[stable]
        var progress = 0f

        if (def != null && hand) {

            val elapsed = now - since

            progress = min(1f, elapsed / def.dwellMs.toFloat())

            if (!def.repeat) {

                if (fired) {
                    progress = 1f
                } else if (elapsed >= def.dwellMs) {
                    fired = true
                    progress = 1f
                    onAction(def.action)
                }

            } else if (elapsed >= def.dwellMs && now - lastRepeat >= REPEAT_MS) {
                lastRepeat = now
                onAction(def.action)
            }

        } else {
            since = now
        }

        return State(hand && stable != "None", stable, def, progress)
    }
}
