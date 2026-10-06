package cn.adcalm.guard.core

import android.content.SharedPreferences

/** Actions are opt-in. An updated public safety policy requires renewed consent. */
class GuardSettings(private val prefs: SharedPreferences) {
    private var targetedCache: Set<String>? = null
    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_TARGETED) targetedCache = null
    }
    init {
        if (prefs.getInt(KEY_SAFETY_POLICY, 0) < SAFETY_POLICY_VERSION) {
            prefs.edit().putBoolean(KEY_DRY_RUN, true)
                .putBoolean(KEY_AUTO_FORCE_STOP, false).putBoolean(KEY_AUTO_ROLLBACK, false)
                .putBoolean(KEY_RETURN_TO_ORIGIN, false).putBoolean(KEY_AUTO_QUARANTINE, false)
                .putBoolean(KEY_RESTORE_ACCESSIBILITY, false).putBoolean(KEY_DEBUG_MODE, false)
                .putInt(KEY_SAFETY_POLICY, SAFETY_POLICY_VERSION).commit()
        }
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
    }
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    var dryRun: Boolean
        get() = prefs.getBoolean(KEY_DRY_RUN, true)
        set(value) = prefs.edit().putBoolean(KEY_DRY_RUN, value).apply()
    var targetedPackages: Set<String>
        get() = targetedCache ?: prefs.getStringSet(KEY_TARGETED, emptySet()).orEmpty().toSet().also { targetedCache = it }
        set(value) { targetedCache = value.toSet(); prefs.edit().putStringSet(KEY_TARGETED, value.toSet()).apply() }
    fun isTargeted(pkg: String): Boolean = pkg in targetedPackages
    fun addTarget(pkg: String) { targetedPackages = targetedPackages + pkg }
    fun removeTarget(pkg: String) { targetedPackages = targetedPackages - pkg }
    /** Compatibility only: public beta never deletes quarantined files on a timer. */
    var quarantineRetentionHours: Int
        get() = prefs.getInt(KEY_RETENTION_HOURS, 24)
        set(value) = prefs.edit().putInt(KEY_RETENTION_HOURS, value).apply()
    var autoRollback: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ROLLBACK, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ROLLBACK, value).apply()
    var autoForceStop: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FORCE_STOP, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FORCE_STOP, value).apply()
    /** Notification cancellation and reversible quarantine share one consent switch. */
    var autoQuarantine: Boolean
        get() = prefs.getBoolean(KEY_AUTO_QUARANTINE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_QUARANTINE, value).apply()
    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()
    var includeHiddenApps: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_HIDDEN, value).apply()
    var ocrEnabled: Boolean
        get() = prefs.getBoolean(KEY_OCR_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_OCR_ENABLED, value).apply()
    var restoreAccessibility: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_ACCESSIBILITY, false)
        set(value) = prefs.edit().putBoolean(KEY_RESTORE_ACCESSIBILITY, value).apply()
    var returnToOrigin: Boolean
        get() = prefs.getBoolean(KEY_RETURN_TO_ORIGIN, false)
        set(value) = prefs.edit().putBoolean(KEY_RETURN_TO_ORIGIN, value).apply()
    private companion object {
        const val KEY_ENABLED="enabled"
        const val KEY_DRY_RUN="dry_run"
        const val KEY_TARGETED="targeted_packages"
        const val KEY_RETENTION_HOURS="quarantine_retention_hours"
        const val KEY_AUTO_FORCE_STOP="auto_force_stop"
        const val KEY_AUTO_ROLLBACK="auto_rollback"
        const val KEY_AUTO_QUARANTINE="auto_quarantine"
        const val KEY_DEBUG_MODE="debug_mode"
        const val KEY_INCLUDE_HIDDEN="include_hidden_apps"
        const val KEY_OCR_ENABLED="ocr_enabled"
        const val KEY_RESTORE_ACCESSIBILITY="restore_accessibility"
        const val KEY_RETURN_TO_ORIGIN="return_to_origin"
        const val KEY_SAFETY_POLICY="public_safety_policy"
        const val SAFETY_POLICY_VERSION=1
    }
}
