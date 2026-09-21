package com.example.octovr

import android.content.Context
import android.content.SharedPreferences

object VrSettings {
    private const val PREFS = "octovr_prefs"

    private const val KEY_IPD_MM = "pref_ipd_mm"
    private const val KEY_CONFIDENCE = "pref_confidence"
    private const val KEY_SMOOTHING = "pref_smoothing"

    private fun p(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getIpdMm(context: Context): Float = p(context).getFloat(KEY_IPD_MM, 63.0f)
    fun setIpdMm(context: Context, value: Float) = p(context).edit().putFloat(KEY_IPD_MM, value).apply()

    fun getConfidence(context: Context): Float = p(context).getFloat(KEY_CONFIDENCE, 0.6f)
    fun setConfidence(context: Context, value: Float) = p(context).edit().putFloat(KEY_CONFIDENCE, value).apply()

    fun isSmoothing(context: Context): Boolean = p(context).getBoolean(KEY_SMOOTHING, true)
    fun setSmoothing(context: Context, value: Boolean) = p(context).edit().putBoolean(KEY_SMOOTHING, value).apply()
}
