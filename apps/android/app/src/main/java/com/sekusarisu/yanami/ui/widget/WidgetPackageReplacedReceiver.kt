package com.sekusarisu.yanami.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Clears potentially sensitive widget state left by an older installed app version. */
class WidgetPackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!isOwnPackageReplacement(intent?.action)) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeout(CLEAR_TIMEOUT_MILLIS) {
                    WidgetUpdateWorker.clearStateAfterPackageReplacement(appContext)
                }
            } catch (error: TimeoutCancellationException) {
                Log.w(TAG, "Timed out clearing widget state after package replacement", error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Do not crash the newly upgraded process. The fail-closed worker below will retry
                // the same widget update and re-check the persisted app-lock state.
                Log.w(TAG, "Unable to clear widget state after package replacement", error)
            } finally {
                // Re-evaluate the current lock state in the worker. This unique one-off refresh
                // intentionally leaves the user's existing periodic interval untouched.
                try {
                    WidgetUpdateWorker.enqueueImmediate(appContext)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Unable to enqueue post-upgrade widget refresh", error)
                }
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val CLEAR_TIMEOUT_MILLIS = 8_000L
        const val TAG = "WidgetUpgradeReceiver"
    }
}

internal fun isOwnPackageReplacement(action: String?): Boolean =
        action == Intent.ACTION_MY_PACKAGE_REPLACED
