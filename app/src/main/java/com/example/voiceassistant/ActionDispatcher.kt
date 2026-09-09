package com.example.voiceassistant

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

class ActionDispatcher(private val context: Context) {

    companion object { private const val TAG = "ActionDispatcher" }

    sealed class DispatchResult {
        abstract val responseText: String

        data class TextResponse(val message: String) : DispatchResult() {
            override val responseText get() = message
        }
        data class ActivityLaunch(val intent: Intent, val summary: String) : DispatchResult() {
            override val responseText get() = summary
        }
        data class Error(val reason: String) : DispatchResult() {
            override val responseText get() = "Error: $reason"
        }
    }

    fun dispatch(rawLlmOutput: String): DispatchResult {
        val json = extractJson(rawLlmOutput)
            ?: return DispatchResult.TextResponse(rawLlmOutput.trim())

        return try {
            val action = json.optString("action", "UNKNOWN").uppercase()
            val params = json.optJSONObject("params") ?: JSONObject()
            // Also support flat params at top level (e.g. {"action":"OPEN_APP","package":"..."})
            val merged = JSONObject(json.toString()).also { obj ->
                if (!obj.has("params")) {
                    val p = JSONObject()
                    obj.keys().forEach { k -> if (k != "action") p.put(k, obj.get(k)) }
                    obj.put("params", p)
                }
            }.getJSONObject("params")
            Log.d(TAG, "action=$action params=$merged")
            routeAction(action, merged)
        } catch (e: JSONException) {
            DispatchResult.TextResponse(rawLlmOutput.trim())
        }
    }

    private fun routeAction(action: String, params: JSONObject): DispatchResult = when (action) {
        "OPEN_APP", "OPENAPP"     -> handleOpenApp(params)
        "GET_BATTERY", "BATTERY_STATS" -> handleBatteryStats()
        "WIFI_ON"                 -> handleWifi(true)
        "WIFI_OFF"                -> handleWifi(false)
        "TORCH_ON"                -> DispatchResult.TextResponse("Torch on — use quick settings to toggle the flashlight.")
        "TORCH_OFF"               -> DispatchResult.TextResponse("Torch off.")
        "VOLUME_UP"               -> DispatchResult.TextResponse("Volume up — use media keys or quick settings.")
        "VOLUME_DOWN"             -> DispatchResult.TextResponse("Volume down.")
        "WEB_SEARCH"              -> handleWebSearch(params)
        "SET_ALARM"               -> handleSetAlarm(params)
        "OPEN_SETTINGS"           -> handleOpenSettings(params)
        "SPEAK", "REPLY"          -> DispatchResult.TextResponse(
            params.optString("reply", params.optString("text", "Done."))
        )
        else -> DispatchResult.TextResponse(
            "I understood your request but don't have an action for '$action' yet."
        )
    }

    private fun handleOpenApp(params: JSONObject): DispatchResult {
        val pkg = params.optString("package", "").trim()
        if (pkg.isEmpty()) return DispatchResult.Error("OPEN_APP requires a 'package' parameter.")
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        return if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            DispatchResult.ActivityLaunch(launch, "Opening $pkg…")
        } else {
            val play = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(play)
            DispatchResult.ActivityLaunch(play, "$pkg not installed. Opening Play Store…")
        }
    }

    private fun handleBatteryStats(): DispatchResult {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return DispatchResult.Error("Could not read battery.")
        val level    = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale    = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct      = if (scale > 0) (level * 100f / scale).toInt() else -1
        val status   = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        return DispatchResult.TextResponse(
            "Battery: $pct%${if (charging) " (charging)" else " (discharging)"}"
        )
    }

    @Suppress("DEPRECATION")
    private fun handleWifi(enable: Boolean): DispatchResult {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled = enable
            DispatchResult.TextResponse("Wi-Fi ${if (enable) "enabled" else "disabled"}.")
        } else {
            val intent = Intent(Settings.Panel.ACTION_WIFI).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            DispatchResult.ActivityLaunch(intent, "Opening Wi-Fi settings…")
        }
    }

    private fun handleWebSearch(params: JSONObject): DispatchResult {
        val query = params.optString("query", "").trim()
        val uri   = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        return DispatchResult.ActivityLaunch(intent, "Searching for: $query")
    }

    private fun handleSetAlarm(params: JSONObject): DispatchResult {
        val hour   = params.optInt("hour", 7)
        val minute = params.optInt("minute", 0)
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return DispatchResult.ActivityLaunch(intent, "Alarm set for %02d:%02d.".format(hour, minute))
    }

    private fun handleOpenSettings(params: JSONObject): DispatchResult {
        val type   = params.optString("type", "main").lowercase()
        val action = when (type) {
            "wifi"      -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display"   -> Settings.ACTION_DISPLAY_SETTINGS
            "sound"     -> Settings.ACTION_SOUND_SETTINGS
            else        -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        return DispatchResult.ActivityLaunch(intent, "Opening ${type.replaceFirstChar { it.uppercase() }} settings…")
    }

    private fun extractJson(text: String): JSONObject? {
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s == -1 || e == -1 || e < s) return null
        return try { JSONObject(text.substring(s, e + 1)) } catch (_: JSONException) { null }
    }
}
