@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PLAT = Color(0xFF7FB2E5); private val GOLD = Color(0xFFE2B33C); private val SILV = Color(0xFFB4BCC6); private val BRON = Color(0xFFC07A3E)
private fun gradeColor(g: Char) = when (g) { 'P' -> PLAT; 'G' -> GOLD; 'S' -> SILV; 'B' -> BRON; else -> Color(0xFF8A8F98) }

/** The real trophy picture of a grade (platinum, gold, silver, bronze); a grey dot while the grade is unknown. */
@Composable private fun GradeIcon(g: Char, height: androidx.compose.ui.unit.Dp) {
    val res = when (g) { 'P' -> R.drawable.trophy_platinum; 'G' -> R.drawable.trophy_gold; 'S' -> R.drawable.trophy_silver; 'B' -> R.drawable.trophy_bronze; else -> 0 }
    if (res == 0) Box(Modifier.size(height * 0.6f).clip(CircleShape).background(gradeColor(g)))
    else Image(painterResource(res), null, Modifier.size(width = height * 0.8f, height = height), contentScale = ContentScale.Fit)
}
private fun gradeName(g: Char) = when (g) { 'P' -> tr("Platinum", "بلاتينية"); 'G' -> tr("Gold", "ذهبية"); 'S' -> tr("Silver", "فضية"); 'B' -> tr("Bronze", "برونزية"); else -> tr("Trophy", "كأس") }

/** Trophy pictures that the console only stores as web addresses (trophy_net.db icon_url): downloaded by the phone and kept in memory. */
private object UrlIcons {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    fun peek(u: String?): Bitmap? = if (u == null) null else cache[u]
    suspend fun load(u: String): Bitmap? = withContext(Dispatchers.IO) {
        cache[u] ?: try {
            val c = java.net.URL(if (u.startsWith("http://")) "https://" + u.removePrefix("http://") else u).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            try { if (c.responseCode == 200) Thumbs.decode(c.inputStream.use { it.readBytes() }, 160)?.also { cache[u] = it } else null } finally { c.disconnect() }
        } catch (e: Exception) { null }
    }
}

private class TrophyData(val accounts: List<Account>, val pack: TrophyPack?, val npwrs: List<String>, val notes: List<String>, val serverSync: Boolean = false)

/** What was read for a game is kept for 5 minutes, so going back into the same game's trophies is instant. */
private object TrophyDataCache { val map = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, TrophyData>>() }

/** Account selector item: progress ring around the avatar. The selected account has a thicker accent ring and an accent label. */
@Composable private fun AccountCircle(ps4: Ps4, a: Account, pct: Int?, selected: Boolean, onClick: () -> Unit) {
    val img by produceState<Bitmap?>(Thumbs.peekAny(ps4, a.avatarPaths, 128), a.id) { if (value == null) value = Thumbs.loadAny(ps4, a.avatarPaths, 128) }
    val cs = MaterialTheme.colorScheme
    val ring = cs.primary; val track = if (selected) cs.primary.copy(alpha = 0.25f) else cs.outlineVariant
    Column(Modifier.width(92.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val w = if (selected) 6.dp.toPx() else 4.dp.toPx(); val inset = w / 2
                val sz = Size(size.width - w, size.height - w)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
                if (pct != null && pct > 0) drawArc(ring, -90f, 360f * pct.coerceIn(0, 100) / 100f, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            Box(Modifier.size(56.dp).clip(CircleShape).background(cs.surfaceContainerHighest).alpha(if (selected) 1f else 0.8f), contentAlignment = Alignment.Center) {
                val b = img
                if (b != null) Image(b.asImageBitmap(), a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(a.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
            }
        }
        Text(a.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (selected) cs.primary else cs.onSurface, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Text(if (pct != null) "$pct%" else "—", style = MaterialTheme.typography.labelSmall, color = if (selected) cs.primary else cs.onSurfaceVariant, maxLines = 1)
    }
}

@Composable private fun TrophyRow(ps4: Ps4, pack: TrophyPack?, t: Trophy, earnedAt: Long?, known: Boolean) {
    val got = earnedAt != null
    val icon by produceState<Bitmap?>(UrlIcons.peek(t.icon), t.id, t.icon, pack?.trpPath) {
        value = UrlIcons.peek(t.icon)
        if (t.icon != null && t.icon.startsWith("http")) { if (value == null) value = UrlIcons.load(t.icon); return@produceState }
        val e = t.icon?.let { pack?.files?.get(it) }
        if (pack != null && e != null && e.second in 1..(2L shl 20)) value = withContext(Dispatchers.IO) { runCatching { Thumbs.decode(Ps4Server.fsRead(ps4, pack.trpPath, e.first, e.second.toInt(), 15000), 160) }.getOrNull() }
    }
    val hiddenLocked = t.hidden && !got
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val tint = if (got) gradeColor(t.grade) else cs.outlineVariant
    Row(Modifier.fillMaxWidth().clip(shape).background(if (got) cs.surfaceContainerHigh else cs.surfaceContainerLow)
        .border(1.dp, if (got) tint.copy(alpha = 0.45f) else cs.outlineVariant.copy(alpha = 0.6f), shape).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            val b = icon
            if (b != null && !hiddenLocked) Image(b.asImageBitmap(), t.name, Modifier.fillMaxSize().alpha(if (got || !known) 1f else 0.4f), contentScale = ContentScale.Crop)
            else Ico(R.drawable.ic_lock, 22.dp, cs.outline)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (hiddenLocked) tr("Hidden trophy", "كأس مخفي") else t.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (got || !known) cs.onSurface else cs.onSurfaceVariant)
            if (!hiddenLocked && t.detail.isNotBlank()) Dim(t.detail, maxLines = 3)
            if (got && earnedAt != null && earnedAt > 0) Text(tr("Earned ", "حُقّق ") + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(earnedAt * 1000)),
                style = MaterialTheme.typography.labelSmall, color = cs.primary)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.alpha(if (got || !known) 1f else 0.55f)) { GradeIcon(t.grade, 28.dp) }
            if (t.grade != '?') Text(gradeName(t.grade), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
            if (known) { if (got) Ico(R.drawable.ic_check_circle, 16.dp, cs.primary) else Ico(R.drawable.ic_lock, 16.dp, cs.onSurfaceVariant) }
        }
    }
}

/** Statistics of the selected account, from the real trophy data only. Missing data shows the existing explanation instead of invented numbers. */
@Composable private fun TrophyOverview(ps4: Ps4, acc: Account, pct: Int?, u: Map<Int, Long>?, earnedCount: Int, total: Int, readable: Boolean, trophies: List<Trophy>, earned: List<Trophy>) {
    val cs = MaterialTheme.colorScheme
    val img by produceState<Bitmap?>(Thumbs.peekAny(ps4, acc.avatarPaths, 128), ps4.id, acc.id) { value = Thumbs.peekAny(ps4, acc.avatarPaths, 128) ?: Thumbs.loadAny(ps4, acc.avatarPaths, 128) }
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Brush.linearGradient(listOf(cs.surfaceContainerHigh, cs.surfaceContainer))).border(1.dp, cs.outlineVariant, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                val b = img
                if (b != null) Image(b.asImageBitmap(), acc.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text(acc.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
            }
            Text(acc.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (pct != null && u != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$pct%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(tr("$earnedCount of $total trophies", "$earnedCount من $total إنجازًا"), Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            val frac by animateFloatAsState(pct.coerceIn(0, 100) / 100f, tween(500), label = "pct")
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(cs.outlineVariant)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(cs.primary, cs.secondary))))
            }
            if (readable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                listOf('P' to tr("Platinum", "بلاتينية"), 'G' to tr("Gold", "ذهبية"), 'S' to tr("Silver", "فضية"), 'B' to tr("Bronze", "برونزية")).forEach { (gr, name) ->
                    val all = trophies.count { it.grade == gr }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        GradeIcon(gr, 30.dp)
                        Column { Text("${earned.count { it.grade == gr }}/$all", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall); Text(name, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1) }
                    }
                }
            }
            if (readable) Text(tr("Points: ", "النقاط: ") + earned.sumOf { t -> TrophyLib.points(t.grade) }, style = MaterialTheme.typography.titleSmall, color = cs.primary)
        } else Dim(tr("This game has no trophy record for this account (it has not played it, or the console's data could not be read).", "لا يوجد سجل إنجازات لهذه اللعبة في هذا الحساب (لم يلعبها، أو تعذّرت قراءة بيانات الجهاز)."))
    }
}

/** Per-game trophy library: account circles with progress, then the earned / not earned trophies of the chosen account. */
@Composable internal fun TrophiesTab(ps4: Ps4, g: GameInfo) {
    val ctx = LocalContext.current
    var data by remember(ps4.id, g.titleId) { mutableStateOf<TrophyData?>(null) }
    var syncMsg by remember { mutableStateOf("") }
    LaunchedEffect(ps4.id, g.titleId) {
        val ck = ps4.id + "|" + g.titleId
        val hit = TrophyDataCache.map[ck]
        if (hit != null && System.currentTimeMillis() - hit.first < 300_000L) { data = hit.second; return@LaunchedEffect }
        data = withContext(Dispatchers.IO) {
            val sup = Ps4Server.info(ps4, 3000)?.trophySync == true
            if (sup) { try { TrophySync.ensure(ps4, false) { syncMsg = it } } catch (e: Exception) { } }
            val (acc, n1) = TrophyLib.accounts(ps4)
            val notes = ArrayList<String>(n1)
            val npwrs = TrophyLib.npwrIds(ps4, g.titleId, notes)
            var pk = TrophyLib.loadPack(ps4, g.titleId, npwrs, notes)
            if (pk == null) pk = TrophyLib.findPackByTitle(ps4, g.title, notes)
            if (pk == null) notes += TrophyLib.listing(ps4, g.titleId)
            TrophyData(acc, pk, npwrs, notes, sup)
        }
        data?.let { TrophyDataCache.map[ck] = System.currentTimeMillis() to it }
    }
    val d = data
    if (d == null) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(tr("Reading trophies…", "جارٍ قراءة الإنجازات…")); if (syncMsg.isNotEmpty()) Dim(syncMsg) }; return }
    val pack = d.pack
    val states = remember(ps4.id, g.titleId) { mutableStateMapOf<String, TrophyState>() }
    LaunchedEffect(d.accounts) {
        val npwr = pack?.npwr?.takeIf { it.isNotBlank() } ?: d.npwrs.firstOrNull().orEmpty()
        for (a in d.accounts) if (a.id !in states) states[a.id] = withContext(Dispatchers.IO) { runCatching { TrophyLib.state(ps4, a, npwr, ctx.cacheDir, g.title) }.getOrElse { TrophyState(null, listOf("${a.name}: ${it.message ?: it.javaClass.simpleName}")) } }
    }
    val loaded = d.accounts.all { it.id in states }
    // the trophy list: the console's own database has the real names (the .trp pack may be encrypted); the pack supplies the pictures
    val dbAny = states.values.firstNotNullOfOrNull { it.db }
    val trophies: List<Trophy>? = dbAny?.trophies?.map { x -> Trophy(x.id, x.name.ifBlank { "Trophy %03d".format(x.id) }, x.detail, pack?.trophies?.firstOrNull { it.id == x.id }?.grade?.takeIf { it != '?' } ?: x.grade, !x.visible, pack?.trophies?.firstOrNull { it.id == x.id }?.icon ?: x.icon.ifBlank { null }) } ?: pack?.trophies
    val readable = dbAny != null || pack?.namesReadable == true
    if (trophies == null) {
        if (!loaded) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(tr("Reading trophies…", "جارٍ قراءة الإنجازات…")) }; return }
        val dbNotes = states.values.flatMap { it.notes }.distinct()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EmptyState(R.drawable.ic_package, tr("This game has no trophy data the app can read.", "هذه اللعبة لا تحتوي بيانات إنجازات يستطيع التطبيق قراءتها."))
            if (d.accounts.isNotEmpty()) {
                SectionHeader(tr("Accounts on this console", "الحسابات في هذا الجهاز"))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.accounts.forEach { a -> AccountCircle(ps4, a, null, false) { } }
                }
            }
            val all = dbNotes + d.notes
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(all.joinToString("\n"))) }) { Lbl(tr("Copy technical details", "نسخ التفاصيل التقنية")) }
            NoteList((listOf(if (d.npwrs.isEmpty()) "No NPWR id found for ${g.titleId}." else "NPWR: ${d.npwrs.joinToString()}") + all).take(30))
        }
        return
    }
    var sel by remember(g.titleId) { mutableStateOf<String?>(null) }
    val total = trophies.size
    fun pctOf(a: Account): Int? {
        val s = states[a.id] ?: return null
        s.db?.let { return it.title.progress.coerceIn(0, 100) }     // the console's own percentage
        return s.unlocked?.let { u -> if (total == 0) 0 else (u.keys.count { k -> trophies.any { it.id == k } } * 100 / total) }
    }
    // by default the account with the most progress (the one that played)
    val shownId = sel ?: d.accounts.maxByOrNull { pctOf(it) ?: -1 }?.id
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val title = dbAny?.title?.title?.takeIf { it.isNotBlank() } ?: pack?.title.orEmpty()
        if (d.accounts.isNotEmpty()) SectionHeader(title.ifBlank { g.title })
        if (d.accounts.isEmpty()) Dim(tr("No accounts found on the console.", "لم يُعثر على حسابات في الجهاز."))
        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            d.accounts.forEach { a -> AccountCircle(ps4, a, pctOf(a), a.id == shownId) { sel = a.id } }
        }
        val acc = d.accounts.firstOrNull { it.id == shownId }
        val st = acc?.let { states[it.id] }
        if (acc != null && st == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(tr("Reading ${acc.name}'s progress…", "جارٍ قراءة تقدّم ${acc.name}…")) }
        if (acc != null && st != null) {
            val u = st.unlocked
            val earned = trophies.filter { u?.containsKey(it.id) == true }
            val pct = pctOf(acc)
            TrophyOverview(ps4, acc, pct, u, earned.size, total, readable, trophies, earned)
            if (u != null) {
                val missing = trophies.filter { u[it.id] == null }
                if (earned.isNotEmpty()) { SectionHeader(tr("Earned", "المحققة") + " (${earned.size})"); earned.sortedByDescending { u[it.id] ?: 0L }.forEach { key(it.id) { TrophyRow(ps4, pack, it, u[it.id], true) } } }
                if (missing.isNotEmpty()) { SectionHeader(tr("Not earned yet", "غير محققة") + " (${missing.size})"); missing.forEach { key(it.id) { TrophyRow(ps4, pack, it, null, true) } } }
            } else trophies.forEach { key(it.id) { TrophyRow(ps4, pack, it, null, false) } }
            val sealedNpwr = d.npwrs.firstOrNull()
            if ((st.unlocked == null || earned.isEmpty() || st.notes.any { it.startsWith("From the sealed trophy image") }) && sealedNpwr != null) {
                val reload: suspend () -> Unit = {
                    states[acc.id] = withContext(Dispatchers.IO) { runCatching { TrophyLib.state(ps4, acc, sealedNpwr, ctx.cacheDir, g.title) }.getOrElse { TrophyState(null, listOf("${acc.name}: ${it.message ?: it.javaClass.simpleName}")) } }
                }
                if (d.serverSync) TrophyRefreshPanel(ps4, reload) else SealedTrophyPanel(ps4, acc.id, sealedNpwr, reload)
            }
            if (st.unlocked == null || earned.isEmpty()) {
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                NoteList(st.notes + tr("Device tab > Scan trophies: send the result so the reader can be matched to this console.", "تبويب الجهاز > فحص الإنجازات: أرسل النتيجة ليُضبط القارئ على هذا الجهاز."))
                TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(st.notes.joinToString("\n"))) }) { Lbl(tr("Copy technical details", "نسخ التفاصيل التقنية")) }
            }
        }
        if (!readable) Dim(tr("The trophy names in this game's file are encrypted, so trophies are numbered (their pictures are real).", "أسماء الإنجازات في ملف اللعبة مشفّرة، لذلك تظهر مرقّمة (الصور حقيقية)."))
    }
}
