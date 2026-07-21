package com.sekusarisu.yanami.ui.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sekusarisu.yanami.R
import com.sekusarisu.yanami.data.local.preferences.UserPreferencesRepository
import com.sekusarisu.yanami.domain.model.aggregateNodeMetrics
import com.sekusarisu.yanami.domain.repository.NodeRepository
import com.sekusarisu.yanami.domain.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

private val widgetStateMutex = Mutex()

class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val serverRepository: ServerRepository by inject()
    private val nodeRepository: NodeRepository by inject()
    private val userPreferencesRepository: UserPreferencesRepository by inject()

    override suspend fun doWork(): Result = performUpdate()

    private suspend fun performUpdate(): Result {
        return try {
            val preferences = userPreferencesRepository.preferencesFlow.first()
            if (preferences.biometricLockRequired) {
                updateAllWidgets(
                        WidgetState(
                                isLoading = false,
                                error = applicationContext.getString(R.string.widget_locked)
                        )
                )
                return Result.success()
            }

            val server = serverRepository.getActive()
            if (server == null) {
                updateAllWidgets(
                        WidgetState(
                                isLoading = false,
                                error = applicationContext.getString(R.string.widget_no_server)
                        )
                )
                return Result.success()
            }

            updateAllWidgets(WidgetState(isLoading = true, serverName = server.name))

            val sessionToken = serverRepository.ensureSessionToken(server)
            val nodes =
                nodeRepository.getNodeInfos(
                    baseUrl = server.baseUrl,
                    sessionToken = sessionToken,
                    authType = server.authType,
                    customHeaders = server.customHeaders.toList()
                )
            val metrics = aggregateNodeMetrics(nodes)

            updateAllWidgets(
                WidgetState(
                    isLoading = false,
                    serverName = server.name,
                    totalCount = nodes.size,
                    onlineCount = metrics.onlineCount,
                    offlineCount = metrics.offlineCount,
                    totalTrafficUp = metrics.trafficUsageUp,
                    totalTrafficDown = metrics.trafficUsageDown,
                    netSpeedUp = metrics.netOut,
                    netSpeedDown = metrics.netIn,
                    lastUpdated = System.currentTimeMillis()
                )
            )
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Do not expose server URLs, response bodies, or transport details on the launcher.
            updateAllWidgets(
                    WidgetState(
                            isLoading = false,
                            error = applicationContext.getString(R.string.widget_error)
                    )
            )
            Result.failure()
        }
    }

    private suspend fun updateAllWidgets(state: WidgetState) {
        // Re-read immediately before every Glance write. The in-process mutex also serializes this
        // with app-lock transitions; the re-read keeps process-restored workers fail-closed.
        widgetStateMutex.withLock {
            val preferences = userPreferencesRepository.preferencesFlow.first()
            val safeState =
                    if (preferences.biometricLockRequired) lockedWidgetState(applicationContext)
                    else state
            writeAllWidgets(applicationContext, safeState)
        }
    }

    companion object {
        private const val WORK_NAME = "yanami_widget_update_periodic"
        private const val IMMEDIATE_WORK_NAME = "yanami_widget_update_immediate"

        fun enqueue(context: Context, intervalMinutes: Int = 30) {
            val wm = WorkManager.getInstance(context)
            val compliantIntervalMinutes = intervalMinutes.coerceAtLeast(15)
            val periodic = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                compliantIntervalMinutes.toLong(), TimeUnit.MINUTES
            ).build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        }

        /** Refreshes (or privacy-clears) widgets without changing their configured interval. */
        fun enqueueImmediate(context: Context) {
            WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                            IMMEDIATE_WORK_NAME,
                            ExistingWorkPolicy.REPLACE,
                            OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
                    )
        }

        /**
         * Removes widget data written by an older app version before any post-upgrade refresh.
         *
         * This deliberately clears every widget, even when the current profile is unlocked. The
         * package-replacement receiver schedules a fresh update immediately afterwards; clearing
         * first ensures an old launcher snapshot cannot retain server names or metrics while the
         * new process is restoring app-lock preferences.
         */
        suspend fun clearStateAfterPackageReplacement(context: Context) {
            widgetStateMutex.withLock {
                clearAllWidgets(context.applicationContext)
            }
        }

        /**
         * Serializes the persistent lock transition with every widget write.
         *
         * Enabling writes the privacy state first, then persists the lock. A crash can therefore
         * leave a conservatively locked widget, never freshly exposed metrics.
         */
        suspend fun transitionLockState(
                context: Context,
                locked: Boolean,
                persist: suspend () -> Unit
        ) {
            widgetStateMutex.withLock {
                if (locked) writeAllWidgets(context, lockedWidgetState(context))
                persist()
            }
            if (!locked) enqueueImmediate(context)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(WORK_NAME)
                cancelUniqueWork(IMMEDIATE_WORK_NAME)
            }
        }
    }
}

private fun lockedWidgetState(context: Context): WidgetState =
        WidgetState(
                isLoading = false,
                error = context.getString(R.string.widget_locked)
        )

private suspend fun writeAllWidgets(context: Context, state: WidgetState) {
        val stateJson = Json.encodeToString(WidgetState.serializer(), state)
        val glanceIds = GlanceAppWidgetManager(context)
            .getGlanceIds(OverviewWidget::class.java)
        for (glanceId in glanceIds) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[WIDGET_STATE_KEY] = stateJson
                }
            }
            OverviewWidget().update(context, glanceId)
        }
}

private suspend fun clearAllWidgets(context: Context) {
        val glanceIds = GlanceAppWidgetManager(context)
            .getGlanceIds(OverviewWidget::class.java)
        for (glanceId in glanceIds) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                clearPersistedWidgetState(prefs)
            }
            OverviewWidget().update(context, glanceId)
        }
}

internal fun clearPersistedWidgetState(preferences: Preferences): Preferences =
        preferences.toMutablePreferences().apply {
            remove(WIDGET_STATE_KEY)
        }
