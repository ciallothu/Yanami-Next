package com.sekusarisu.yanami.ui.screen.settings

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import cafe.adriel.voyager.core.model.screenModelScope
import com.sekusarisu.yanami.R
import com.sekusarisu.yanami.data.backup.ConfigBackupManager
import com.sekusarisu.yanami.data.local.crypto.BiometricLockManager
import com.sekusarisu.yanami.data.local.preferences.UserPreferencesRepository
import com.sekusarisu.yanami.mvi.MviViewModel
import com.sekusarisu.yanami.mvi.UiEffect
import com.sekusarisu.yanami.mvi.UiEvent
import com.sekusarisu.yanami.mvi.UiState
import com.sekusarisu.yanami.ui.theme.ThemeColor
import com.sekusarisu.yanami.ui.widget.WidgetUpdateWorker
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** 设置页面 State */
data class SettingsState(
        val themeColor: ThemeColor = ThemeColor.DYNAMIC,
        val darkMode: String = "system",
        val language: String = "system",
        val fontScale: Float = 1.0f,
        val autoEnterNodeList: Boolean = false,
        val chartAnimationEnabled: Boolean = true,
        val biometricEnabled: Boolean = false,
        val biometricEnvelope: String? = null,
        val maskIpEnabled: Boolean = false,
        val isBiometricMutationInProgress: Boolean = false,
        val isBackupInProgress: Boolean = false
) : UiState

/** 设置页面 Events */
sealed interface SettingsEvent : UiEvent {
    data class SetThemeColor(val color: ThemeColor) : SettingsEvent
    data class SetDarkMode(val mode: String) : SettingsEvent
    data class SetLanguage(val lang: String) : SettingsEvent
    data class SetFontScale(val scale: Float) : SettingsEvent
    data class SetAutoEnterNodeList(val enabled: Boolean) : SettingsEvent
    data class SetChartAnimation(val enabled: Boolean) : SettingsEvent
    data class SetBiometricEnabled(
            val enabled: Boolean,
            val previousEnvelope: String?,
            val verifiedEnvelope: String
    ) : SettingsEvent
    data class SetMaskIpEnabled(val enabled: Boolean) : SettingsEvent
}

/** 设置页面 Effects */
sealed interface SettingsEffect : UiEffect {
    data class ShowToast(val message: String) : SettingsEffect
}

/** 设置 ViewModel */
class SettingsViewModel(
        private val prefsRepo: UserPreferencesRepository,
        private val configBackupManager: ConfigBackupManager,
        private val biometricLockManager: BiometricLockManager,
        private val context: Context
) :
        MviViewModel<SettingsState, SettingsEvent, SettingsEffect>(SettingsState()) {

    init {
        prefsRepo
                .preferencesFlow
                .onEach { prefs ->
                    setState {
                        copy(
                                themeColor = ThemeColor.fromKey(prefs.themeColorKey),
                                darkMode = prefs.darkModeKey,
                                language = prefs.languageKey,
                                fontScale = prefs.fontScale,
                                autoEnterNodeList = prefs.autoEnterNodeList,
                                chartAnimationEnabled = prefs.chartAnimationEnabled,
                                biometricEnabled = prefs.biometricEnabled,
                                biometricEnvelope = prefs.biometricEnvelope,
                                maskIpEnabled = prefs.maskIpEnabled
                        )
                    }
                }
                .launchIn(screenModelScope)
    }

    override fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SetThemeColor -> {
                screenModelScope.launch { prefsRepo.setThemeColor(event.color.key) }
            }
            is SettingsEvent.SetDarkMode -> {
                screenModelScope.launch { prefsRepo.setDarkMode(event.mode) }
            }
            is SettingsEvent.SetLanguage -> {
                screenModelScope.launch {
                    prefsRepo.setLanguage(event.lang)
                    applyLocale(event.lang)
                }
            }
            is SettingsEvent.SetFontScale -> {
                screenModelScope.launch { prefsRepo.setFontScale(event.scale) }
            }
            is SettingsEvent.SetAutoEnterNodeList -> {
                screenModelScope.launch { prefsRepo.setAutoEnterNodeList(event.enabled) }
            }
            is SettingsEvent.SetChartAnimation -> {
                screenModelScope.launch { prefsRepo.setChartAnimation(event.enabled) }
            }
            is SettingsEvent.SetBiometricEnabled -> {
                requestBiometricLockChange(
                        event.enabled,
                        event.previousEnvelope,
                        event.verifiedEnvelope
                )
            }
            is SettingsEvent.SetMaskIpEnabled -> {
                screenModelScope.launch { prefsRepo.setMaskIpEnabled(event.enabled) }
            }
        }
    }

    /**
     * Starts a serialized lock mutation and reports whether the authenticated session was accepted.
     * The previous envelope binds the result to the state for which the prompt was opened.
     */
    fun requestBiometricLockChange(
            enabled: Boolean,
            previousEnvelope: String?,
            verifiedEnvelope: String
    ): Boolean {
        if (currentState.isBiometricMutationInProgress ||
                        currentState.biometricEnvelope != previousEnvelope
        ) {
            return false
        }

        setState { copy(isBiometricMutationInProgress = true) }
        screenModelScope.launch {
            try {
                WidgetUpdateWorker.transitionLockState(context, enabled) {
                    prefsRepo.setBiometricLock(
                            enabled,
                            if (enabled) verifiedEnvelope else null
                    )
                    if (enabled) {
                        // Obsolete aliases are removed only after the new envelope is durably
                        // stored, so an interrupted migration remains recoverable.
                        biometricLockManager.retainKeyForEnvelope(verifiedEnvelope)
                    } else {
                        biometricLockManager.deleteKeys()
                    }
                }
            } catch (_: Exception) {
                if (verifiedEnvelope != previousEnvelope) {
                    biometricLockManager.discardUnpersistedEnvelope(verifiedEnvelope)
                }
                sendEffect(
                        SettingsEffect.ShowToast(
                                context.getString(R.string.biometric_verification_failed)
                        )
                )
            } finally {
                setState { copy(isBiometricMutationInProgress = false) }
            }
        }
        return true
    }

    fun exportConfig(uri: Uri) {
        if (currentState.isBackupInProgress) return
        setState { copy(isBackupInProgress = true) }
        screenModelScope.launch {
            try {
                val summary = configBackupManager.exportToUri(uri)
                sendEffect(
                        SettingsEffect.ShowToast(
                                context.getString(
                                        R.string.settings_export_success,
                                        summary.serverCount,
                                        summary.snippetCount
                                )
                        )
                )
            } catch (e: Exception) {
                sendEffect(
                        SettingsEffect.ShowToast(
                                context.getString(
                                        R.string.settings_export_failed,
                                        e.message ?: context.getString(R.string.settings_backup_unknown_error)
                                )
                        )
                )
            } finally {
                setState { copy(isBackupInProgress = false) }
            }
        }
    }

    fun importConfig(uri: Uri) {
        if (currentState.isBackupInProgress) return
        setState { copy(isBackupInProgress = true) }
        screenModelScope.launch {
            try {
                val summary = configBackupManager.importFromUri(uri)
                sendEffect(
                        SettingsEffect.ShowToast(
                                context.getString(
                                        R.string.settings_import_success,
                                        summary.addedServerCount,
                                        summary.updatedServerCount,
                                        summary.addedSnippetCount,
                                        summary.updatedSnippetCount
                                )
                        )
                )
                if (summary.skippedServerCount > 0 || summary.skippedSnippetCount > 0) {
                    sendEffect(
                            SettingsEffect.ShowToast(
                                    context.getString(
                                            R.string.settings_import_skipped,
                                            summary.skippedServerCount,
                                            summary.skippedSnippetCount
                                    )
                            )
                    )
                }
            } catch (e: Exception) {
                sendEffect(
                        SettingsEffect.ShowToast(
                                context.getString(
                                        R.string.settings_import_failed,
                                        e.message ?: context.getString(R.string.settings_backup_unknown_error)
                                )
                        )
                )
            } finally {
                setState { copy(isBackupInProgress = false) }
            }
        }
    }

    /** 通过 AppCompatDelegate 应用 locale 切换 */
    private fun applyLocale(langKey: String) {
        val localeList =
                if (langKey == "system") {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(langKey)
                }
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}
