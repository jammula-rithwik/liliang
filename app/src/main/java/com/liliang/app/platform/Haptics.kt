package com.liliang.app.platform

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptics through View.performHapticFeedback — no VIBRATE permission,
 * respects the system "touch feedback" setting automatically.
 *
 *  - [tick]: one detent of a drag (weight/reps stepping)
 *  - [confirm]: a control accepted (seal pressed, set completed)
 *  - [success]: a moment (PR, mastery, goal achieved, session sealed)
 */
object Haptics {
    /**
     * One drag detent. CLOCK_TICK is too faint to feel mid-set, so use the
     * stronger frequent-tick where the platform has it (API 34+), then
     * KEYBOARD_TAP, with a plain CLOCK_TICK floor.
     */
    fun tick(view: View) {
        val constant = when {
            Build.VERSION.SDK_INT >= 34 -> HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(constant)
    }

    fun confirm(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(constant)
    }

    fun success(view: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            view.postDelayed({
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }, 90)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
}
