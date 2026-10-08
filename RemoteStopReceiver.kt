package id.arunika.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RemoteStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            RemoteConfig.setEnabled(context, false)
            RemoteAccessibilityService.instance?.applySettings()
        }
    }
    companion object { const val ACTION_STOP = "id.arunika.remote.STOP" }
}
