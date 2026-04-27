package com.wabiz.contactsaver

import android.content.Context
import android.content.SharedPreferences

/**
 * Wraps SharedPreferences for storing the user's naming settings
 * and the list of numbers already discovered/saved.
 */
object PrefsHelper {

    private const val PREF_FILE = "wa_contact_saver_prefs"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    // ── Naming settings ──────────────────────────────────────────────────

    var prefix: String
        get() = _prefix
        set(v) { _prefix = v }
    private var _prefix = "WA Contact "

    var startNumber: Int
        get() = _start
        set(v) { _start = v }
    private var _start = 1

    var padding: Int
        get() = _padding
        set(v) { _padding = v }
    private var _padding = 3

    var countryCode: String
        get() = _cc
        set(v) { _cc = v }
    private var _cc = "91"

    fun load(ctx: Context) {
        val p = prefs(ctx)
        _prefix  = p.getString("prefix", "WA Contact ") ?: "WA Contact "
        _start   = p.getInt("start", 1)
        _padding = p.getInt("padding", 3)
        _cc      = p.getString("cc", "91") ?: "91"
    }

    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putString("prefix", _prefix)
            .putInt("start", _start)
            .putInt("padding", _padding)
            .putString("cc", _cc)
            .apply()
    }

    // ── Running counter so names stay sequential across sessions ─────────

    fun currentIndex(ctx: Context): Int = prefs(ctx).getInt("current_index", _start)

    fun setCurrentIndex(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt("current_index", value).apply()
    }

    fun resetIndex(ctx: Context) {
        prefs(ctx).edit().putInt("current_index", _start).apply()
    }

    // ── Contact name builder ─────────────────────────────────────────────

    fun buildName(index: Int): String = "$_prefix${index.toString().padStart(_padding, '0')}"

    fun formatNumber(rawDigits: String): String {
        val cc = _cc.trimStart('+')
        val n  = rawDigits.filter { it.isDigit() }
        return if (n.startsWith(cc)) "+$n" else "+$cc$n"
    }
}
