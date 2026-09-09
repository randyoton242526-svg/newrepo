package com.example.voiceassistant

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * ActionDispatcher
 * ----------------
 * Parses structured JSON commands emitted by [LocalModelExecutor] and maps
 * them to real Android system calls or intents.
 *
 * Expected JSON shape from the LLM:
 * {
 *   "action": "<ACTION_NAME>",
 *   "params": { ... optional key-value pairs ... }
 * }
 *
 * Supported actions (case-insensitive):
 *  - OPEN_APP       {"package": "com.example.app"}
 *  - BATTERY_STATS  {}
 *  - WIFI_ON        {}
 *  - WIFI_OFF       {}
 *  - OPEN_SETTINGS  {"type": "wifi"|"bluetooth"|"main"}
 *  - SPEAK          {"text": "<message to speak back>"}
 *  - UNKNOWN        fallback
 *
 * Threading: All public methods are safe to call from a coroutine / background
 * thread, but any Intent that starts an Activity must be dispatched to the
 * main thread. Callers should switch to Dispatchers.Main for UI-touching ops.
 */
class ActionDispatcher(private val context: Context) {

    companion object {
        private const val TAG = "ActionDispatcher"
    }

    // ---- Result model -------------------------------------------------------

    sealed class DispatchResult {
        data class TextResponse(val message: String) : DispatchResult()
        data class ActivityLaunch(val intent: Intent, val summary: String) : DispatchResult()
        data class Error(val reason: String) : DispatchResult()
    }

    // ---- Public API ---------------------------------------------------------

    /**
     * Parses [rawLlmOutput] and executes the corresponding action.
     *
     * @param rawLlmOutput  Raw text from the LLM, expected to contain valid JSON.
     *                      We search for the first '{' to be tolerant of any
     *                      preamble text the model might prepend.
     * @return A [DispatchResult] describing what happened.
     */
    fun dispatch(rawLlmOutput: String): DispatchResult {
        val json = extractJson(rawLlmOutput)
            ?: return DispatchResult.TextResponse(rawLlmOutput.trim())

        return try {
            val action = json.optString("action", "UNKNOWN").uppercase()
            val params = json.optJSONObject("params") ?: JSONObject()
            Log.d(TAG, "Dispatching action=$action params=$params")
            routeAction(action, params)
        } catch (e: JSONException) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            DispatchResult.TextResponse(rawLlmOutput.trim())
        }
    }

    // ---- Routing ------------------------------------------------------------

    private fun routeAction(action: String, params: JSONObject): DispatchResult {
        return when (action) {
            "OPEN_APP"      -> handleOpenApp(params)
            "BATTERY_STATS" -> handleBatteryStats()
            "WIFI_ON"       -> handleWifi(enable = true)
            "WIFI_OFF"      -> handleWifi(enable = false)
            "OPEN_SETTINGS" -> handleOpenSettings(params)
            "SPEAK", "REPLY" -> DispatchResult.TextResponse(
                params.optString("text", "Done.")
            )
            else -> DispatchResult.TextResponse(
                "I understood your request but don't have an action for '$action' yet."
            )
        }
    }

    // ---- Action handlers ----------------------------------------------------

    /**
     * Launches a third-party app by package name.
     * Falls back to the Play Store page if the app is not installed.
     */
    private fun handleOpenApp(params: JSONObject): DispatchResult {
        val pkg = params.optString("package", "").trim()
        if (pkg.isEmpty()) {
            return DispatchResult.Error("OPEN_APP action requires a 'package' parameter.")
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            DispatchResult.ActivityLaunch(launchIntent, "Opening $pkg…")
        } else {
            // App not installed — open Play Store
            val playIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$pkg")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            DispatchResult.ActivityLaunch(playIntent, "$pkg not found. Opening Play Store…")
        }
    }

    /**
     * Reads live battery statistics and returns them as a text response.
     * No special permission required.
     */
    private fun handleBatteryStats(): DispatchResult {
        val filter  = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val battery = context.registerReceiver(null, filter)
            ?: return DispatchResult.Error("Could not read battery information.")

        val level   = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale   = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct     = if (scale > 0) (level * 100f / scale).toInt() else -1

        val statusInt = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging  = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
                        statusInt == BatteryManager.BATTERY_STATUS_FULL

        val health = when (battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD          -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT      -> "Overheating"
            BatteryManager.BATTERY_HEALTH_DEAD          -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE  -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD          -> "Cold"
            else                                        -> "Unknown"
        }

        val msg = buildString {
            append("Battery: $pct%")
            append(if (charging) " (charging)" else " (discharging)")
            append(" — Health: $health")
        }
        return DispatchResult.TextResponse(msg)
    }

    /**
     * Toggles Wi-Fi on API 28 and below. On API 29+ system apps own this
     * toggle; for user apps we open the Wi-Fi settings panel instead.
     */
    @Suppress("DEPRECATION")
    private fun handleWifi(enable: Boolean): DispatchResult {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wm.isWifiEnabled = enable
            DispatchResult.TextResponse("Wi-Fi ${if (enable) "enabled" else "disabled"}.")
        } else {
            // API 29+ — open the Wi-Fi settings panel (user must toggle manually)
            val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            DispatchResult.ActivityLaunch(
                intent,
                "Opening Wi-Fi settings (Android 10+ restricts programmatic toggling)…"
            )
        }
    }

    /**
     * Opens the appropriate Settings screen.
     * Supported 'type' values: wifi, bluetooth, main (default).
     */
    private fun handleOpenSettings(params: JSONObject): DispatchResult {
        val type   = params.optString("type", "main").lowercase()
        val action = when (type) {
            "wifi"      -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display"   -> Settings.ACTION_DISPLAY_SETTINGS
            "sound"     -> Settings.ACTION_SOUND_SETTINGS
            "apps"      -> Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
            else        -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return DispatchResult.ActivityLaunch(intent, "Opening ${type.replaceFirstChar { it.uppercase() }} settings…")
    }

    // ---- Utilities ----------------------------------------------------------

    /**
     * Finds the first valid JSON object in [text].
     * Tolerant of LLM preamble such as “Sure! Here is the command: {\u2026}”.
     */
    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end   = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        return try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: JSONException) {
            null
        }
    }
}
