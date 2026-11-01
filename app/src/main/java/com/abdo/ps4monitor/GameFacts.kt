package com.abdo.ps4monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * General information about a game (genre, age rating, release date, short premise ...) from open encyclopedia data: Wikipedia for the
 * text, Wikidata for the structured facts. Nothing here touches the app's colours or shapes — this is informational only.
 */
data class GameFacts(
    val title: String, val summary: String, val genres: List<String>, val modes: List<String>, val developers: List<String>, val publishers: List<String>,
    val platforms: List<String>, val ratings: List<String>, val series: String?, val release: String?, val pageUrl: String?, val lang: String, val fetchedAt: Long = 0L,
) {
    fun isEmpty() = summary.isBlank() && genres.isEmpty() && developers.isEmpty() && publishers.isEmpty() && release == null && ratings.isEmpty()

    fun toJson(): JSONObject = JSONObject().put("t", title).put("s", summary).put("g", JSONArray(genres)).put("m", JSONArray(modes)).put("d", JSONArray(developers))
        .put("p", JSONArray(publishers)).put("pl", JSONArray(platforms)).put("r", JSONArray(ratings)).put("se", series ?: JSONObject.NULL).put("rel", release ?: JSONObject.NULL)
        .put("u", pageUrl ?: JSONObject.NULL).put("l", lang).put("ts", fetchedAt)

    companion object {
        private fun list(o: JSONObject, k: String): List<String> = o.optJSONArray(k)?.let { a -> (0 until minOf(a.length(), 12)).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        /** Android's org.json turns a JSON null into the text "null" in optString, so null is tested explicitly. */
        private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }
        fun fromJson(o: JSONObject): GameFacts = GameFacts(o.optString("t"), o.optString("s"), list(o, "g"), list(o, "m"), list(o, "d"), list(o, "p"), list(o, "pl"), list(o, "r"),
            str(o, "se"), str(o, "rel"), str(o, "u"), o.optString("l", "en"), o.optLong("ts"))
    }
}

object GameFactsParser {
    class Page(val title: String, val extract: String, val qid: String?, val arTitle: String?, val index: Int = 0)
    class Claims(val byProperty: Map<String, List<String>>, val releaseDates: List<String>) {
        fun ids(p: String) = byProperty[p].orEmpty()
        fun allIds(): List<String> = byProperty.values.flatten().distinct()
    }

    const val GENRE = "P136"; const val MODE = "P404"; const val DEVELOPER = "P178"; const val PUBLISHER = "P123"; const val PLATFORM = "P400"
    const val ESRB = "P852"; const val PEGI = "P908"; const val SERIES = "P179"; const val RELEASE = "P577"
    private val itemProps = listOf(GENRE, MODE, DEVELOPER, PUBLISHER, PLATFORM, ESRB, PEGI, SERIES)

    private fun pageOf(p: JSONObject): Page {
        val qid = p.optJSONObject("pageprops")?.optString("wikibase_item", "")?.takeIf { it.startsWith("Q") }
        val ar = p.optJSONArray("langlinks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("lang") == "ar" }?.optString("*", "")?.ifBlank { null } }
        return Page(p.optString("title"), p.optString("extract"), qid, ar, p.optInt("index", 0))
    }
    /** Wikipedia `generator=search` response (extracts + pageprops + langlinks) -> every result page, best search rank first. */
    fun parsePages(body: String): List<Page> {
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        return pages.keys().asSequence().map { pageOf(pages.getJSONObject(it)) }.sortedBy { if (it.index == 0) 99 else it.index }.toList()
    }
    fun parsePage(body: String): Page? = parsePages(body).firstOrNull()

    /** Arabic (or any) extract response: the first page's extract. */
    fun parseExtract(body: String): String {
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return ""
        val keys = pages.keys(); return if (keys.hasNext()) pages.getJSONObject(keys.next()).optString("extract") else ""
    }

    private val seriesCue = Regex("(?i)\\b(video game series|game series|media franchise|video game franchise|franchise|series of (?:\\w+ ){0,3}games)\\b")
    private val remakeCue = Regex("(?i)remaster|collection|\\bhd\\b|definitive|trilogy|anniversary")
    private fun norm(s: String) = s.lowercase().replace(Regex("\\s*\\([^)]*\\)"), " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Search results of "<title> video game" can be the series, an older game of the series, or a later one. The page about THIS game is
     * chosen by: the words of the title in the page title (an exact match counts most), an article that is not about a series / franchise,
     * and a release year in the console's lifetime (2013 or later) unless the title says remaster / collection; search rank breaks ties.
     */
    fun pick(pages: List<Page>, title: String): Page? {
        val toks = norm(title).split(' ').filter { it.length >= 3 }
        if (toks.isEmpty()) return null
        val base = norm(title); val remake = remakeCue.containsMatchIn(title)
        var best: Page? = null; var bestScore = 0.5
        pages.forEachIndexed { i, p ->
            if (!p.extract.contains("game", ignoreCase = true)) return@forEachIndexed
            val pt = p.title.lowercase(); val hit = toks.count { pt.contains(it) }
            if (hit * 2 < toks.size) return@forEachIndexed
            var s = if (hit == toks.size) 3.0 else 1.0
            if (norm(p.title) == base) s += 2.0
            val first = p.extract.substringBefore('.').take(300)
            if (seriesCue.containsMatchIn(first)) s -= 6.0
            Regex("\\b(19|20)\\d{2}\\b").find(first)?.value?.toIntOrNull()?.let { y -> s += if (y in 2013..2027) 3.0 else if (remake) 0.0 else -3.0 }
            s -= i * 0.3
            if (s > bestScore) { bestScore = s; best = p }
        }
        return best
    }

    private val spoilerCue = Regex("(?i)\\b(dies|die|death|dead|killed|kills|murder\\w*|betray\\w*|twist|reveal\\w*|ending|endings|sacrific\\w*|turns out|finale|spoiler|traitor)\\b|يموت|موت|قتل|خيان|نهاية|يكشف")
    private val receptionCue = Regex("(?i)\\b(received|reviews?|critics?|acclaim\\w*|awards?|nominated|sold|sales|copies|metacritic|praised|criticism|sequel|remaster\\w*|announced|controvers\\w*)\\b|تلقت|مبيعات|نسخة|جوائز|نقاد|إشادة|الإعلان")
    private val productionLead = Regex("(?i)\\b(developed by|published by|developed and published)\\b|طورتها|نشرتها|تطوير|نشر")

    /** A short, spoiler-conscious premise from the lead of an article. */
    fun premise(extract: String, maxSentences: Int = 3, maxChars: Int = 460): String {
        if (extract.isBlank()) return ""
        val clean = extract.replace(Regex("\\[[^]]*]"), "").replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim()
        val sentences = clean.split(Regex("(?<=[.!?؟])\\s+")).map { it.trim() }.filter { it.length > 25 }
        val ok = sentences.filter { !spoilerCue.containsMatchIn(it) && !receptionCue.containsMatchIn(it) }
        val body = ok.filterIndexed { i, s -> !(i == 0 && productionLead.containsMatchIn(s) && ok.size >= 3) }
        var out = ""
        for (s in body.take(maxSentences)) { if ((out + " " + s).trim().length > maxChars && out.isNotEmpty()) break; out = (out + " " + s).trim() }
        if (out.length > maxChars) out = out.take(maxChars).substringBeforeLast(' ') + "…"
        return out
    }

    /** Wikidata `wbgetentities&props=claims` response -> the claims used. Deprecated statements are ignored. */
    fun parseClaims(body: String, qid: String): Claims {
        val claims = JSONObject(body).optJSONObject("entities")?.optJSONObject(qid)?.optJSONObject("claims") ?: return Claims(emptyMap(), emptyList())
        fun statements(p: String): List<JSONObject> = claims.optJSONArray(p)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("rank") != "deprecated" } } ?: emptyList()
        val items = HashMap<String, List<String>>()
        for (p in itemProps) {
            val ids = statements(p).mapNotNull { it.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id")?.takeIf { id -> id.startsWith("Q") } }.distinct()
            if (ids.isNotEmpty()) items[p] = ids
        }
        val dates = statements(RELEASE).mapNotNull { st ->
            val v = st.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value") ?: return@mapNotNull null
            formatTime(v.optString("time"), v.optInt("precision", 9))
        }
        return Claims(items, dates)
    }
    fun formatTime(time: String, precision: Int): String? {
        val m = Regex("^[+-]?(\\d{4,})-(\\d{2})-(\\d{2})T").find(time) ?: return null
        val y = m.groupValues[1].padStart(4, '0'); val mo = m.groupValues[2]; val d = m.groupValues[3]
        return when { precision >= 11 && mo != "00" && d != "00" -> "$y-$mo-$d"; precision == 10 && mo != "00" -> "$y-$mo"; else -> y }
    }
    fun earliest(dates: List<String>): String? = dates.minByOrNull { it.padEnd(10, '0') }

    fun parseLabels(body: String, lang: String): Map<String, String> {
        val ents = JSONObject(body).optJSONObject("entities") ?: return emptyMap()
        val out = HashMap<String, String>()
        for (id in ents.keys()) {
            val labels = ents.getJSONObject(id).optJSONObject("labels") ?: continue
            val l = labels.optJSONObject(lang)?.optString("value") ?: labels.optJSONObject("en")?.optString("value")
            if (!l.isNullOrBlank()) out[id] = l
        }
        return out
    }

    /** An online entry must really be about this game: most significant words of the title are in the page title, and the text talks about a game. */
    fun plausible(game: String, pageTitle: String, extract: String): Boolean {
        val toks = game.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
        if (toks.isEmpty()) return false
        val pt = pageTitle.lowercase()
        return toks.count { pt.contains(it) } * 2 >= toks.size && extract.contains("game", ignoreCase = true)
    }

    fun build(page: Page, claims: Claims, labels: Map<String, String>, summarySource: String, lang: String, pageUrl: String?, now: Long): GameFacts {
        fun names(p: String, limit: Int = 6) = claims.ids(p).mapNotNull { labels[it] }.distinct().take(limit)
        fun rating(p: String, tag: String) = claims.ids(p).mapNotNull { labels[it] }.map { if (it.contains(tag, ignoreCase = true)) it else "$tag $it" }.distinct().take(2)
        val platforms = names(PLATFORM, 20).sortedBy { if (it.contains("PlayStation", true) || it.contains("بلاي")) 0 else 1 }.take(7)
        return GameFacts(page.title, premise(summarySource), names(GENRE), names(MODE, 4), names(DEVELOPER, 3), names(PUBLISHER, 3), platforms,
            rating(ESRB, "ESRB") + rating(PEGI, "PEGI"), names(SERIES, 1).firstOrNull(), earliest(claims.releaseDates), pageUrl, lang, now)
    }
}

/** "EA SPORTS™ FC 24 Deluxe Edition" -> "EA SPORTS FC 24": the search query, not what is shown. */
private fun cleanGameTitle(t: String): String = t.replace(Regex("[™®©]"), " ").replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
    .replace(Regex("(?i)\\b(director'?s cut|digital deluxe|deluxe|ultimate|gold|complete|standard|premium|game of the year|goty)\\b( edition)?"), " ")
    .replace(Regex("(?i)\\bedition\\b"), " ").replace(Regex("\\s+"), " ").trim()

/**
 * Looks a game up online (Settings > "Look up game information online"): one lookup per game and language, cached on the phone for 90 days
 * (a miss for 7 days), works from the cache offline afterwards. Only the game's NAME is sent — no account, file, or console data.
 */
object GameWebInfo {
    private const val DAY = 86_400_000L
    private val http by lazy { OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).connectTimeout(6, TimeUnit.SECONDS).build() }
    class FetchResult(val facts: GameFacts?, val networkError: Boolean)
    private fun key(titleId: String, lang: String) = "gf_${titleId}_$lang"

    fun cached(titleId: String, lang: String): GameFacts? {
        val s = runCatching { Store.sp.getString(key(titleId, lang), null) }.getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(s); if (o.has("none")) return null
            val f = GameFacts.fromJson(o); if (System.currentTimeMillis() - f.fetchedAt > 90 * DAY) null else f
        }.getOrNull()
    }
    fun knownMissing(titleId: String, lang: String): Boolean {
        val s = runCatching { Store.sp.getString(key(titleId, lang), null) }.getOrNull() ?: return false
        return runCatching { val o = JSONObject(s); o.has("none") && System.currentTimeMillis() - o.optLong("none") < 7 * DAY }.getOrDefault(false)
    }
    private fun markMissing(titleId: String, lang: String) { runCatching { Store.sp.edit().putString(key(titleId, lang), JSONObject().put("none", System.currentTimeMillis()).toString()).apply() } }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "PS4Monitor (personal PS4 companion app)").build()
        return http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("http ${r.code}"); r.body?.string().orEmpty() }
    }
    private fun enc(s: String) = URLEncoder.encode(s.replace(' ', '_'), "UTF-8")

    // two UI parts (the game page and, in the future, other screens) could ask for the same game+language at once; sharing one in-flight
    // request avoids firing the lookup twice.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<FetchResult>>()

    suspend fun fetch(titleId: String, title: String, lang: String): FetchResult {
        val k = key(titleId, lang)
        val d = inFlight.computeIfAbsent(k) { scope.async { fetchNow(titleId, title, lang) } }
        return try { d.await() } finally { inFlight.remove(k, d) }
    }

    private suspend fun fetchNow(titleId: String, title: String, lang: String): FetchResult = withContext(Dispatchers.IO) {
        val clean = cleanGameTitle(title)
        try {
            val q = URLEncoder.encode("$clean video game", "UTF-8")
            val pages = GameFactsParser.parsePages(get("https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=$q&gsrlimit=6&prop=extracts%7Cpageprops%7Clanglinks&ppprop=wikibase_item&lllang=ar&lllimit=20&exlimit=6&exintro=1&explaintext=1&exsentences=8&redirects=1&format=json"))
            val page = GameFactsParser.pick(pages, clean)
            if (page == null) { markMissing(titleId, lang); return@withContext FetchResult(null, false) }
            var claims = GameFactsParser.Claims(emptyMap(), emptyList()); var labels = emptyMap<String, String>()
            if (page.qid != null) {
                claims = GameFactsParser.parseClaims(get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${page.qid}&props=claims&format=json"), page.qid)
                val ids = claims.allIds().take(45)
                if (ids.isNotEmpty()) labels = GameFactsParser.parseLabels(get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${ids.joinToString("%7C")}&props=labels&languages=en%7Car&format=json"), lang)
            }
            var summary = page.extract; var url = "https://en.wikipedia.org/wiki/" + enc(page.title)
            if (lang == "ar" && page.arTitle != null) runCatching {
                val ex = GameFactsParser.parseExtract(get("https://ar.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exsentences=8&redirects=1&titles=${enc(page.arTitle)}&format=json"))
                if (ex.isNotBlank()) { summary = ex; url = "https://ar.wikipedia.org/wiki/" + enc(page.arTitle) }
            }
            val facts = GameFactsParser.build(page, claims, labels, summary, lang, url, System.currentTimeMillis())
            if (facts.isEmpty()) { markMissing(titleId, lang); FetchResult(null, false) }
            else { runCatching { Store.sp.edit().putString(key(titleId, lang), facts.toJson().toString()).apply() }; FetchResult(facts, false) }
        } catch (e: Exception) { FetchResult(null, true) }
    }
}
