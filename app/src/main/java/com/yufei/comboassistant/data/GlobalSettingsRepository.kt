package com.yufei.comboassistant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.comboSettingsDataStore by preferencesDataStore("combo_settings")

data class GlobalSettings(
    val disclosureAccepted: Boolean = false,
    val floatingBallEnabled: Boolean = true,
    val buttonsHidden: Boolean = false,
    val ballX: Float = 0.04f,
    val ballY: Float = 0.42f,
)

@Singleton
class GlobalSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val disclosureAccepted = booleanPreferencesKey("disclosure_accepted")
        val floatingBallEnabled = booleanPreferencesKey("floating_ball_enabled")
        val buttonsHidden = booleanPreferencesKey("buttons_hidden")
        val ballX = floatPreferencesKey("ball_x")
        val ballY = floatPreferencesKey("ball_y")
    }

    val settings: Flow<GlobalSettings> = context.comboSettingsDataStore.data.map { prefs ->
        GlobalSettings(
            disclosureAccepted = prefs[Keys.disclosureAccepted] ?: false,
            floatingBallEnabled = prefs[Keys.floatingBallEnabled] ?: true,
            buttonsHidden = prefs[Keys.buttonsHidden] ?: false,
            ballX = prefs[Keys.ballX] ?: 0.04f,
            ballY = prefs[Keys.ballY] ?: 0.42f,
        )
    }

    suspend fun setDisclosureAccepted(value: Boolean) = update(Keys.disclosureAccepted, value)
    suspend fun setFloatingBallEnabled(value: Boolean) = update(Keys.floatingBallEnabled, value)
    suspend fun setButtonsHidden(value: Boolean) = update(Keys.buttonsHidden, value)

    suspend fun setBallPosition(x: Float, y: Float) {
        context.comboSettingsDataStore.edit {
            it[Keys.ballX] = x.coerceIn(0f, 1f)
            it[Keys.ballY] = y.coerceIn(0f, 1f)
        }
    }

    private suspend fun update(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.comboSettingsDataStore.edit { it[key] = value }
    }
}
