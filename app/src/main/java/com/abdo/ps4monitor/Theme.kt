package com.abdo.ps4monitor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Theme engine. One representation ([AppTheme], ARGB ints) is shared by built-in presets, user profiles and the
 * palettes generated from a game's cover, so every component only ever reads tokens and never hardcodes colours.
 */
@Immutable
data class AppTheme(
    val id: String, val name: String, val builtin: Boolean,
    val bg: Int, val bg2: Int, val surface: Int,
    val accent: Int, val accent2: Int,
    val text: Int, val text2: Int, val border: Int, val progress: Int,
    /** custom/preset profiles only: keep the profile's background but take the current game's accents */
    val gameAccents: Boolean = false,
    /** the preset this profile was copied from (for "restore original values") */
    val basePreset: String? = null,
)

/** The tokens the UI needs beyond what Material has a slot for. */
@Immutable
data class Tokens(
    val bg: Color, val bg2: Color, val surface: Color, val accent: Color, val accent2: Color,
    val text: Color, val text2: Color, val muted: Color, val border: Color, val progress: Color,
    val success: Color, val warning: Color, val error: Color, val onAccent: Color,
)
val LocalTokens = compositionLocalOf { ThemeEngine.tokens(ThemeEngine.DEFAULT) }

enum class ThemeMode { DEFAULT, PRESET, CUSTOM, DYNAMIC }

object ThemeEngine {
    private fun c(h: Long) = (0xFF000000L or h).toInt()
    private fun p(id: String, n: String, bg: Long, bg2: Long, sf: Long, a: Long, a2: Long, tx: Long, tx2: Long, bd: Long, pr: Long = a) =
        AppTheme(id, n, true, c(bg), c(bg2), c(sf), c(a), c(a2), c(tx), c(tx2), c(bd), c(pr))

    /** App default: the deep navy / indigo look of the redesign. */
    val DEFAULT = p("default", "PS4 Monitor", 0x0A1022, 0x0E1730, 0x141E3D, 0x7C6BFF, 0x4FC3F7, 0xF1F3FF, 0xA9B2D6, 0x2A3769)
    val PRESETS: List<AppTheme> = listOf(
        DEFAULT,
        p("midnight", "Midnight", 0x0D0E10, 0x131519, 0x1B1E24, 0x9AA7B8, 0x6C7A8C, 0xEDEFF2, 0xA2A9B3, 0x2B2F37),
        p("aurora", "Aurora", 0x06141C, 0x0A1E29, 0x0F2B3A, 0x2DD4BF, 0x38BDF8, 0xE8F8FA, 0x9CC7CF, 0x1C4152),
        p("violet", "Violet Pulse", 0x0F0B1A, 0x161026, 0x201737, 0xA78BFA, 0xE879F9, 0xF3EFFF, 0xB5A9D6, 0x3A2B63),
        p("emerald", "Emerald Abyss", 0x050B08, 0x0A1510, 0x10201A, 0x34D399, 0x84CC16, 0xE9FBF2, 0x9CC4B0, 0x1D4032),
        p("crimson", "Crimson Night", 0x0E0A0B, 0x161012, 0x20171A, 0xE5484D, 0xF59E0B, 0xF8EEEE, 0xC2A6A8, 0x44262B),
        p("arctic", "Arctic Blue", 0x07111F, 0x0B1A30, 0x112645, 0x7DD3FC, 0x60A5FA, 0xEEF7FF, 0xA3BDD6, 0x214470),
        p("gold", "Solar Gold", 0x0B0A07, 0x14120C, 0x1E1B12, 0xE0B341, 0xD97706, 0xFBF6E8, 0xC4B896, 0x453D22),
        p("rose", "Rose Nebula", 0x120A14, 0x1B101F, 0x271629, 0xF472B6, 0xC084FC, 0xFDEEF7, 0xCDA6BF, 0x4A2849),
    )
    fun preset(id: String?) = PRESETS.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ state (observable)
    var mode by mutableStateOf(ThemeMode.DEFAULT); private set
    var presetId by mutableStateOf("default"); private set
    var activeProfileId by mutableStateOf<String?>(null); private set
    var profiles by mutableStateOf<List<AppTheme>>(emptyList()); private set
    /** optional: keep the chosen global theme but take the current game's accent colours */
    var applyGameAccents by mutableStateOf(false); private set

    /** The saved choice, without any game-specific adaptation. */
    val base: AppTheme get() = when (mode) {
        ThemeMode.PRESET -> preset(presetId) ?: DEFAULT
        ThemeMode.CUSTOM -> profiles.firstOrNull { it.id == activeProfileId } ?: DEFAULT
        else -> DEFAULT
    }

    fun init(sp: android.content.SharedPreferences) {
        mode = runCatching { ThemeMode.valueOf(sp.getString("th_mode", "DEFAULT")!!) }.getOrDefault(ThemeMode.DEFAULT)
        presetId = sp.getString("th_preset", "default") ?: "default"
        activeProfileId = sp.getString("th_profile", null)
        applyGameAccents = sp.getBoolean("th_gameaccent", false)
        profiles = runCatching { parseList(sp.getString("th_profiles", "[]")!!) }.getOrDefault(emptyList())
        if (mode == ThemeMode.CUSTOM && profiles.none { it.id == activeProfileId }) mode = ThemeMode.DEFAULT
    }
    private fun sp() = Store.sp
    fun chooseMode(m: ThemeMode) { mode = m; sp().edit().putString("th_mode", m.name).apply() }
    fun selectPreset(id: String) { if (preset(id) == null) return; presetId = id; chooseMode(ThemeMode.PRESET); sp().edit().putString("th_preset", id).apply() }
    fun selectProfile(id: String) { if (profiles.none { it.id == id }) return; activeProfileId = id; chooseMode(ThemeMode.CUSTOM); sp().edit().putString("th_profile", id).apply() }
    fun setGameAccents(b: Boolean) { applyGameAccents = b; sp().edit().putBoolean("th_gameaccent", b).apply() }
    fun resetToDefault() { presetId = "default"; chooseMode(ThemeMode.DEFAULT); sp().edit().putString("th_preset", "default").apply() }

    // ------------------------------------------------------------------ profiles
    private fun persist() = sp().edit().putString("th_profiles", toJson(profiles).toString()).apply()
    fun newId() = "u" + java.lang.Long.toString(System.nanoTime(), 36)
    /** Saves a user profile (a built-in preset is never overwritten: it is copied under a new id by the caller). */
    fun saveProfile(t: AppTheme, activate: Boolean) {
        val s = sanitize(t.copy(builtin = false, name = t.name.trim().ifBlank { "My theme" }.take(32)))
        profiles = if (profiles.any { it.id == s.id }) profiles.map { if (it.id == s.id) s else it } else profiles + s
        persist(); if (activate) selectProfile(s.id)
    }
    fun duplicate(t: AppTheme): AppTheme { val d = t.copy(id = newId(), name = t.name.take(26) + " copy", builtin = false, basePreset = if (t.builtin) t.id else t.basePreset); saveProfile(d, false); return d }
    fun deleteProfile(id: String) {
        profiles = profiles.filter { it.id != id }; persist()
        if (activeProfileId == id) { activeProfileId = null; sp().edit().remove("th_profile").apply(); if (mode == ThemeMode.CUSTOM) chooseMode(ThemeMode.DEFAULT) }
    }
    fun editableCopy(t: AppTheme) = if (t.builtin) t.copy(id = newId(), name = t.name + " (mine)", builtin = false, basePreset = t.id) else t

    // ------------------------------------------------------------------ validation / contrast
    /** Parses #RGB / #RRGGBB / RRGGBB. Anything else is rejected (null). */
    fun parseHex(s: String): Int? {
        var h = s.trim().removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length != 6 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return (0xFF000000L or h.toLong(16)).toInt()
    }
    fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    /** Makes a user theme safe: text and accents are pushed until they are readable on the surfaces they sit on. */
    fun sanitize(t: AppTheme): AppTheme {
        fun opaque(x: Int) = x or (0xFF shl 24)
        val bg = opaque(t.bg); val bg2 = opaque(t.bg2); val sf = opaque(t.surface)
        val worst = if (ColorMath.luminance(sf) > ColorMath.luminance(bg)) sf else bg
        val lightUi = ColorMath.luminance(bg) > 0.5
        fun fix(fg: Int, min: Double): Int {
            var x = opaque(fg); var n = 0
            while (ColorMath.contrast(x, worst) < min && n++ < 40) x = ColorMath.blend(x, if (lightUi) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.08f)
            return x
        }
        return t.copy(bg = bg, bg2 = bg2, surface = sf, text = fix(t.text, 7.0), text2 = fix(t.text2, 4.5),
            accent = fix(t.accent, 3.0), accent2 = fix(t.accent2, 3.0), progress = fix(t.progress, 3.0), border = opaque(t.border))
    }

    // ------------------------------------------------------------------ tokens -> Material
    fun tokens(t: AppTheme): Tokens {
        val onAcc = if (ColorMath.contrast(t.accent, 0xFF0B0B10.toInt()) >= ColorMath.contrast(t.accent, 0xFFFFFFFF.toInt())) 0xFF0B0B10.toInt() else 0xFFFFFFFF.toInt()
        return Tokens(Color(t.bg), Color(t.bg2), Color(t.surface), Color(t.accent), Color(t.accent2), Color(t.text), Color(t.text2),
            Color(ColorMath.blend(t.text2, t.bg, 0.35f)), Color(t.border), Color(t.progress),
            Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFFF6B7A), Color(onAcc))
    }
    fun scheme(t: AppTheme): ColorScheme {
        val k = tokens(t)
        val hi = Color(ColorMath.blend(t.surface, t.text, 0.08f)); val lo = Color(ColorMath.blend(t.bg, t.surface, 0.5f))
        return darkColorScheme(
            primary = k.accent, onPrimary = k.onAccent, primaryContainer = Color(ColorMath.blend(t.surface, t.accent, 0.28f)), onPrimaryContainer = k.text,
            secondary = k.accent2, onSecondary = k.onAccent, secondaryContainer = Color(ColorMath.blend(t.surface, t.accent2, 0.18f)), onSecondaryContainer = k.text,
            tertiary = k.accent2, onTertiary = k.onAccent, tertiaryContainer = Color(ColorMath.blend(t.surface, t.accent2, 0.22f)), onTertiaryContainer = k.text,
            background = k.bg, onBackground = k.text, surface = k.bg, onSurface = k.text, onSurfaceVariant = k.text2,
            surfaceVariant = k.surface, surfaceTint = k.accent,
            surfaceContainerLowest = k.bg, surfaceContainerLow = lo, surfaceContainer = k.bg2, surfaceContainerHigh = k.surface, surfaceContainerHighest = hi,
            outline = Color(ColorMath.blend(t.border, t.text2, 0.35f)), outlineVariant = k.border,
            error = k.error, onError = Color(0xFF2B0A0E), errorContainer = Color(0xFF4A1A22), onErrorContainer = Color(0xFFFFD9DD),
        )
    }

    // ------------------------------------------------------------------ game themes
    private val gameCache = ConcurrentHashMap<String, AppTheme>()
    fun cachedGame(titleId: String): AppTheme? = gameCache[titleId]

    /**
     * Builds a coordinated theme from a cover analysis (see [CoverPalette.analyze]): background and surfaces are very dark
     * tints of the dominant hue, the accent is the dominant hue lifted to a readable lightness, the second accent is the
     * cover's second hue (or a shifted hue when the cover has only one). Computed once per game and cached.
     */
    fun fromCover(titleId: String, a: CoverAnalysis): AppTheme = gameCache.getOrPut(titleId) {
        val hsl = ColorMath.toHsl(a.primary); val h = hsl[0]
        val sat = (hsl[1] * (0.2f + 0.8f * minOf(1f, a.colorfulness / 0.25f))).coerceIn(0.12f, 0.75f)
        val h2 = a.secondary?.let { ColorMath.toHsl(it)[0] } ?: (h + 38f)
        val s2 = a.secondary?.let { ColorMath.toHsl(it)[1].coerceIn(0.35f, 0.8f) } ?: sat
        sanitize(AppTheme("game:$titleId", "Game", true,
            bg = ColorMath.fromHsl(h, (sat * 0.55f).coerceIn(0.08f, 0.45f), 0.045f),
            bg2 = ColorMath.fromHsl(h, (sat * 0.55f).coerceIn(0.08f, 0.45f), 0.075f),
            surface = ColorMath.fromHsl(h, (sat * 0.50f).coerceIn(0.08f, 0.42f), 0.115f),
            accent = ColorMath.fromHsl(h, (sat + 0.15f).coerceIn(0.3f, 0.9f), 0.64f),
            accent2 = ColorMath.fromHsl(h2, s2, 0.66f),
            text = ColorMath.fromHsl(h, 0.25f, 0.96f), text2 = ColorMath.fromHsl(h, 0.18f, 0.74f),
            border = ColorMath.fromHsl(h, (sat * 0.5f).coerceIn(0.1f, 0.4f), 0.24f),
            progress = ColorMath.fromHsl(h, (sat + 0.15f).coerceIn(0.3f, 0.9f), 0.64f)))
    }

    /** The theme the game pages use: the game's own in Dynamic mode, the saved one otherwise (optionally with the game's accents). */
    fun forGame(game: AppTheme?): AppTheme {
        val b = base
        if (game == null) return b
        return when {
            mode == ThemeMode.DYNAMIC -> game
            (mode == ThemeMode.PRESET || mode == ThemeMode.CUSTOM) && applyGameAccents -> b.copy(accent = game.accent, accent2 = game.accent2, progress = game.progress)
            else -> b
        }
    }

    // ------------------------------------------------------------------ JSON (storage + export/import)
    private fun toJson(l: List<AppTheme>) = JSONArray(l.map { toJson(it) })
    fun toJson(t: AppTheme): JSONObject = JSONObject().put("v", 1).put("id", t.id).put("name", t.name)
        .put("bg", hex(t.bg)).put("bg2", hex(t.bg2)).put("surface", hex(t.surface)).put("accent", hex(t.accent)).put("accent2", hex(t.accent2))
        .put("text", hex(t.text)).put("text2", hex(t.text2)).put("border", hex(t.border)).put("progress", hex(t.progress))
        .put("gameAccents", t.gameAccents).put("base", t.basePreset ?: JSONObject.NULL)
    private fun parseList(s: String): List<AppTheme> { val a = JSONArray(s); return (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it), keepId = true) } }
    /** Strict import: every colour must be a valid hex colour, the name is trimmed, nothing else in the file is read. */
    fun fromJson(o: JSONObject, keepId: Boolean): AppTheme? {
        val name = o.optString("name", "").trim().take(32).ifBlank { return null }
        val bg = parseHex(o.optString("bg", "")) ?: return null
        val bg2 = parseHex(o.optString("bg2", "")) ?: return null
        val sf = parseHex(o.optString("surface", "")) ?: return null
        val ac = parseHex(o.optString("accent", "")) ?: return null
        val ac2 = parseHex(o.optString("accent2", "")) ?: return null
        val tx = parseHex(o.optString("text", "")) ?: return null
        val tx2 = parseHex(o.optString("text2", "")) ?: return null
        val bd = parseHex(o.optString("border", "")) ?: return null
        val pr = parseHex(o.optString("progress", "")) ?: return null
        val t = AppTheme(if (keepId) o.optString("id", "").ifBlank { newId() } else newId(), name, false, bg, bg2, sf, ac, ac2, tx, tx2, bd, pr,
            o.optBoolean("gameAccents", false), o.optString("base", "").takeIf { preset(it) != null })
        return sanitize(t)
    }
    fun importJson(text: String): AppTheme? {
        if (text.length > 4000) return null
        val o = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return null
        val t = fromJson(o, keepId = false) ?: return null
        saveProfile(t, false); return t
    }
}
