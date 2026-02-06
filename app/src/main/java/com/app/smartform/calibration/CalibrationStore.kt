package com.app.smartform.calibration

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.app.smartform.reps.CalibrationProfile
import com.app.smartform.reps.RepThresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "calibration_store")

class CalibrationStore(private val context: Context) {

    private object Keys {
        val CURL_DOWN = doublePreferencesKey("curl_down")
        val CURL_UP = doublePreferencesKey("curl_up")

        val SQUAT_DOWN = doublePreferencesKey("squat_down")
        val SQUAT_UP = doublePreferencesKey("squat_up")

        val PUSHUP_DOWN = doublePreferencesKey("pushup_down")
        val PUSHUP_UP = doublePreferencesKey("pushup_up")
    }

    val profileFlow: Flow<CalibrationProfile> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { p ->
                val defaults = CalibrationProfile()

                val curlDown = p[Keys.CURL_DOWN] ?: defaults.curl.downThresh
                val curlUp = p[Keys.CURL_UP] ?: defaults.curl.upThresh

                val squatDown = p[Keys.SQUAT_DOWN] ?: defaults.squat.downThresh
                val squatUp = p[Keys.SQUAT_UP] ?: defaults.squat.upThresh

                val pushDown = p[Keys.PUSHUP_DOWN] ?: defaults.pushup.downThresh
                val pushUp = p[Keys.PUSHUP_UP] ?: defaults.pushup.upThresh

                CalibrationProfile(
                    curl = RepThresholds(downThresh = curlDown, upThresh = curlUp),
                    squat = RepThresholds(downThresh = squatDown, upThresh = squatUp),
                    pushup = RepThresholds(downThresh = pushDown, upThresh = pushUp)
                )
            }

    suspend fun saveProfile(profile: CalibrationProfile) {
        context.dataStore.edit { p ->
            p[Keys.CURL_DOWN] = profile.curl.downThresh
            p[Keys.CURL_UP] = profile.curl.upThresh

            p[Keys.SQUAT_DOWN] = profile.squat.downThresh
            p[Keys.SQUAT_UP] = profile.squat.upThresh

            p[Keys.PUSHUP_DOWN] = profile.pushup.downThresh
            p[Keys.PUSHUP_UP] = profile.pushup.upThresh
        }
    }

    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }
}