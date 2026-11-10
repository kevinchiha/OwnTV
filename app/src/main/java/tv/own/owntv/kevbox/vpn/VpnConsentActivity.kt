// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import org.koin.core.context.GlobalContext

/**
 * Android's one-time VPN prompt (AC-26). [VpnController] starts it from the started MainActivity;
 * it launches VpnService.prepare's screen for a result and hands the answer to
 * [VpnController.onPermission]. Back and Cancel both come back as not OK.
 *
 * Translucent with no layout, so MainActivity only pauses and the channel keeps playing behind the
 * prompt. It has no android:noHistory on purpose: Android never delivers a result to a noHistory
 * activity. Destroyed before an answer came, it reports not OK once, so the rules never stay stuck
 * asking. It shows nothing of its own (AC-34).
 */
class VpnConsentActivity : Activity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated after a configuration change: the prompt is already up and answers here.
        if (savedInstanceState != null) return
        val prompt = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "vpn prompt unavailable: ${e.javaClass.simpleName}")
            answer(false)
            finish()
            return
        }
        if (prompt == null) {
            answer(true)
            finish()
            return
        }
        try {
            startActivityForResult(prompt, REQUEST_PROMPT)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "vpn prompt failed to open: ${e.javaClass.simpleName}")
            answer(false)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROMPT) return
        answer(resultCode == RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) answer(false)
        super.onDestroy()
    }

    /** Once per prompt: the first answer counts, a later one does nothing. */
    private fun answer(granted: Boolean) {
        if (answered) return
        answered = true
        try {
            GlobalContext.getOrNull()?.get<VpnController>()?.onPermission(granted)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "vpn prompt answer lost: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val LOG_TAG = "KevBoxVpn"
        const val REQUEST_PROMPT = 1
    }
}
