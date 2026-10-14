// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Sets off one of the three events Sentry reports, from the adb shell only, on release builds too:
 *
 *   adb shell am broadcast -a tv.kevbox.live.SENTRY_TEST --es kind crash|native|freeze \
 *     -n tv.kevbox.live/tv.own.owntv.kevbox.errors.SentryTestReceiver
 *
 * The manifest guards it with android.permission.DUMP, which only the shell and the system hold,
 * so no other app can send it and the member never sees it. It acts with a blank key too: Core's
 * CrashRecorder still writes its crash file then.
 */
class SentryTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val main = Handler(Looper.getMainLooper())
        when (intent.getStringExtra(EXTRA_KIND)) {
            // Posted, so the broadcast finishes first and the crash reads as an ordinary main-thread one.
            KIND_CRASH -> main.post { throw RuntimeException(CRASH_MESSAGE) }
            // SIGSEGV, caught by the NDK handler; needs no native code of our own.
            KIND_NATIVE -> Process.sendSignal(Process.myPid(), SIGSEGV)
            // A main thread blocked well past Android's 5 s input limit. Below Android 11 the SDK's
            // watchdog reports it; from 11 on, the system records an ANR once input waits on it
            // (press a key), and the SDK reports that on the next start.
            KIND_FREEZE -> main.post { Thread.sleep(FREEZE_MS) }
        }
    }

    private companion object {
        const val ACTION = "tv.kevbox.live.SENTRY_TEST"
        const val EXTRA_KIND = "kind"
        const val KIND_CRASH = "crash"
        const val KIND_NATIVE = "native"
        const val KIND_FREEZE = "freeze"
        const val CRASH_MESSAGE = "KevBox Sentry test crash"
        const val SIGSEGV = 11
        const val FREEZE_MS = 30_000L
    }
}
