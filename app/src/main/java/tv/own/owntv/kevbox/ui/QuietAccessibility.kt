// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat

/**
 * Stops Compose describing the screen to accessibility services that do not read it.
 *
 * Compose keeps an accessibility copy of the screen up to date whenever any accessibility service is
 * on. Kevin's TCL runs two (Projectivy Launcher and TV Quick Actions), which only watch key presses,
 * and a trace of OK and Back showed Compose spending 250-290 ms of the 800 ms after each key on that
 * copy, on the main thread, between the frames of the video's grow and shrink.
 *
 * With no screen reader on (touch exploration off, which is what TalkBack turns on), this empties
 * Compose's cached list of enabled services, its only test for "is accessibility on", so it skips the
 * work. A screen reader keeps everything: nothing changes while touch exploration is on. Compose
 * refills the list on each accessibility change or window attach; a layout listener empties it again.
 * The field is internal to Compose, kept by name in keepRules/kevbox.keep; if a Compose update renames
 * it, this does nothing and the app behaves as upstream's.
 */
object QuietAccessibility {
    private val field = runCatching {
        Class.forName("androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat")
            .getDeclaredField("_enabledServices")
            .apply { isAccessible = true }
    }.getOrNull()

    fun install(activity: Activity) {
        val f = field ?: return
        val am = activity.getSystemService(AccessibilityManager::class.java) ?: return
        val root = activity.window.decorView
        root.viewTreeObserver.addOnGlobalLayoutListener {
            if (!am.isTouchExplorationEnabled) quiet(root, f)
        }
    }

    private fun quiet(view: View, f: java.lang.reflect.Field) {
        val delegate = ViewCompat.getAccessibilityDelegate(view)
        if (delegate != null && delegate.javaClass === f.declaringClass) {
            runCatching { if (f.get(delegate) != emptyList<Any>()) f.set(delegate, emptyList<Any>()) }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) quiet(view.getChildAt(i), f)
    }
}
