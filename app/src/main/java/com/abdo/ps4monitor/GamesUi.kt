@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun GamesTab(ps4: Ps4, reload: Int) {
    var res by remember(ps4.id, reload) { mutableStateOf<Pair<List<GameInfo>, List<String>>?>(null) }
    var done by remember(ps4.id, reload) { mutableIntStateOf(0) }; var total by remember(ps4.id, reload) { mutableIntStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<GameInfo?>(null) }
    var gone by remember(ps4.id, reload) { mutableStateOf(setOf<String>()) }     // games deleted from this screen
    LaunchedEffect(ps4.id, reload) { res = null; res = withContext(Dispatchers.IO) { Ps4Info.scanGames(ps4, force = reload != 0) { d, t -> done = d; total = t } } }
    val r = res
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r == null) { LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth()); Dim(tr("Reading installed games…", "جارٍ قراءة الألعاب المثبتة…") + if (total > 0) " $done/$total" else ""); return@Column }
        if (r.first.isEmpty()) { EmptyState(R.drawable.ic_package, tr("No installed games found.", "لم يُعثر على ألعاب مثبتة.")); NoteList(r.second); return@Column }
        OutlinedTextField(q, { q = it }, label = { Text(tr("Search", "بحث") + " (${r.first.size})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val list = r.first.filter { it.titleId !in gone && (q.isBlank() || it.title.contains(q, true) || it.titleId.contains(q, true)) }
        LazyVerticalGrid(GridCells.Adaptive(128.dp), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.titleId }) { g -> GameCard(ps4, g) { open = g } }
            item(span = { GridItemSpan(maxLineSpan) }) { NoteList(r.second) }
        }
    }
    open?.let { GameSheet(ps4, it, onDeleted = { id -> gone = gone + id }) { open = null } }
}

/** One game: the square cover (icon0.png is 512x512, shown 1:1 so nothing is stretched or cut), then its name and id. */
@Composable private fun GameCard(ps4: Ps4, g: GameInfo, onClick: () -> Unit) {
    val paths = remember(g.titleId, g.metaDir) { Ps4Info.art(g, "icon0.png") }
    val icon by produceState<Bitmap?>(Thumbs.peekAny(ps4, paths, 256), g.titleId) { if (value == null) value = Thumbs.loadAny(ps4, paths, 256) }
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            val b = icon
            if (b != null) Image(b.asImageBitmap(), g.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Ico(R.drawable.ic_package, 36.dp, MaterialTheme.colorScheme.outline)
            val cat = Ps4Info.categoryLabel(g.category)
            if (g.category == "gp" || g.category == "ac") Surface(Modifier.align(Alignment.TopStart).padding(6.dp), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(cat, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(g.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Dim(listOfNotNull(g.version.takeIf { it.isNotBlank() }?.let { "v$it" }, g.titleId).joinToString("  •  "), maxLines = 1)
        }
    }
}

@Composable private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.38f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Colours of the game's cover -> a coordinated theme. Analysed once per game (48x48 sample) off the main thread, then cached in [ThemeEngine]. */
@Composable private fun rememberGameTheme(titleId: String, cover: Bitmap?): AppTheme? {
    val t by produceState<AppTheme?>(ThemeEngine.cachedGame(titleId), titleId, cover) {
        if (value == null && cover != null) value = withContext(Dispatchers.Default) {
            runCatching {
                val sm = Bitmap.createScaledBitmap(cover, 48, 48, true)
                val px = IntArray(48 * 48); sm.getPixels(px, 0, 48, 0, 0, 48, 48)
                ThemeEngine.fromCover(titleId, CoverPalette.analyze(px))
            }.getOrNull()
        }
    }
    return t
}

/** Details of one game: cinematic hero (pic1/pic0 artwork), cover, name, segmented tabs, then the tab content. Colours follow the theme mode. */
@Composable private fun GameSheet(ps4: Ps4, g: GameInfo, onDeleted: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val iconPaths = remember(g.titleId, g.metaDir) { Ps4Info.art(g, "icon0.png") }
    val bannerPaths = remember(g.titleId, g.metaDir) { Ps4Info.art(g, "pic1.png", "pic0.png") }
    val icon by produceState<Bitmap?>(Thumbs.peekAny(ps4, iconPaths, 512), g.titleId) { if (value == null) value = Thumbs.loadAny(ps4, iconPaths, 512) }
    val banner by produceState<Bitmap?>(Thumbs.peekAny(ps4, bannerPaths, 1280), g.titleId) { if (value == null) value = Thumbs.loadAny(ps4, bannerPaths, 1280) }
    val rows = buildList {
        add(tr("Title ID", "معرّف اللعبة") to g.titleId)
        g.sfo["CONTENT_ID"]?.takeIf { it.isNotBlank() }?.let { add(tr("Content ID", "معرّف المحتوى") to it) }
        Ps4Info.categoryLabel(g.category).takeIf { it.isNotBlank() }?.let { add(tr("Type", "النوع") to "$it (${g.category})") }
        g.version.takeIf { it.isNotBlank() }?.let { add(tr("Version", "الإصدار") to it) }
        g.sfo["VERSION"]?.takeIf { it.isNotBlank() && it != g.version }?.let { add(tr("Content version", "إصدار المحتوى") to it) }
        Ps4Info.firmware(g.sfo["SYSTEM_VER"])?.let { add(tr("Required firmware", "النظام المطلوب") to it) }
        g.sfo["PARENTAL_LEVEL"]?.takeIf { it.isNotBlank() }?.let { add(tr("Parental level", "المستوى العائلي") to it) }
        add(tr("Install folder", "مجلد التثبيت") to "/user/app/${g.titleId}")
    }
    var tab by rememberSaveable(g.titleId) { mutableIntStateOf(0) }
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    // theme: Dynamic mode = the game's own; Preset / Custom = the saved theme (optionally with the game's accents); App default = the app's current scheme
    val gameTheme = rememberGameTheme(g.titleId, icon)
    val cs = if (ThemeEngine.mode == ThemeMode.DEFAULT) MaterialTheme.colorScheme else ThemeEngine.scheme(ThemeEngine.forGame(gameTheme))
    val sheetBg by animateColorAsState(cs.surface, tween(350), label = "gameBg")
    MaterialTheme(colorScheme = cs, shapes = MaterialTheme.shapes) {
        ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = sheetBg, contentColor = cs.onSurface, dragHandle = null, scrimColor = Color.Black.copy(alpha = 0.6f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GameHero(g, icon, banner) 
                Box(Modifier.padding(horizontal = 16.dp)) { SegmentedTabs(listOf(tr("Details", "التفاصيل"), tr("Captures", "اللقطات"), tr("Trophies", "الإنجازات")), tab) { tab = it } }
                if (tab == 1) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(screenH * 0.7f)) { CapturesTab(ps4, 0, g.titleId) }
                if (tab == 2) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { TrophiesTab(ps4, g) }
                if (tab == 0) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh).border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) { rows.forEach { (k, v) -> InfoRow(k, v) } }
                    FilledTonalButton(onClick = { clip.setText(AnnotatedString(g.titleId)); Toast.makeText(ctx, tr("Copied", "تم النسخ"), Toast.LENGTH_SHORT).show() }) {
                        Ico(R.drawable.ic_copy, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Copy title ID", "نسخ معرّف اللعبة"))
                    }
                    if (DeletePolicy.isGameId(g.titleId)) DeleteGameSection(ps4, g, onDeleted, onClose)
                }
            }
        }
    }
}

/** Edge-to-edge artwork fading into the page, with the cover and title on top. Missing artwork falls back to a themed gradient. */
@Composable private fun GameHero(g: GameInfo, icon: Bitmap?, banner: Bitmap?) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(250.dp)) {
        val bn = banner
        if (bn != null) Image(bn.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(cs.primaryContainer, cs.surfaceContainer, cs.surface))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.35f to Color.Transparent, 0.75f to cs.surface.copy(alpha = 0.85f), 1f to cs.surface)))
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.25f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.25f))))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(104.dp).shadow(12.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHighest).border(1.dp, cs.primary.copy(alpha = 0.5f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                val ic = icon
                if (ic != null) Image(ic.asImageBitmap(), g.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Ico(R.drawable.ic_package, 36.dp, cs.outline)
            }
            Column(Modifier.weight(1f).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(g.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Dim(listOfNotNull(g.titleId, g.version.takeIf { it.isNotBlank() }?.let { "v$it" }).joinToString("  •  "), maxLines = 1)
            }
        }
    }
}

/** Segmented tab control: the selected segment is tinted with the active accent. Same behaviour as before (index in, index out). */
@Composable fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)).padding(4.dp)) {
        labels.forEachIndexed { i, t ->
            val on = i == selected
            val bg by animateColorAsState(if (on) cs.primary.copy(alpha = 0.20f) else Color.Transparent, tween(180), label = "tab")
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(bg).clickable { onSelect(i) }.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Lbl(t, color = if (on) cs.primary else cs.onSurfaceVariant, weight = if (on) FontWeight.SemiBold else FontWeight.Medium, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Section title with a fading rule, used between blocks of the game page. */
@Composable fun SectionHeader(t: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.outlineVariant, Color.Transparent))))
    }
}

/**
 * Delete the game completely. Two dialogs: the first says what is removed and what stays, the second is a final warning whose button only
 * becomes active after a few seconds. Needs "Allow deleting" in the Storage tab. Only CUSA games (see DeletePolicy.gameTargets).
 */
@Composable private fun DeleteGameSection(ps4: Ps4, g: GameInfo, onDeleted: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember(g.titleId) { mutableIntStateOf(0) }          // 0 none, 1 first dialog, 2 final warning, 3 deleting
    var result by remember(g.titleId) { mutableStateOf<DeleteResult?>(null) }
    val allowed = Ps4Server.deleteAllowed()
    HorizontalDivider()
    Text(tr("Delete game", "حذف اللعبة"), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
    Dim(tr("Removes the game, its update and its add-on content from the console. Saved games and trophies stay.", "يزيل اللعبة وتحديثها ومحتواها الإضافي من الجهاز. الحفظ (Save) والإنجازات تبقى."))
    if (!allowed) Dim(tr("Turn on “Allow deleting” in the Storage tab first.", "فعّل «السماح بالحذف» من تبويب التخزين أولًا."))
    OutlinedButton(enabled = allowed && step == 0, onClick = { step = 1 }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
        Ico(R.drawable.ic_delete, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Delete this game…", "حذف هذه اللعبة…"))
    }
    if (step == 1) AlertDialog(onDismissRequest = { step = 0 },
        title = { Text(tr("Delete “${g.title}”?", "حذف «${g.title}»؟")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("These will be deleted from the PS4:", "سيُحذف التالي من الـPS4:"))
            Text("• " + tr("the game itself (${g.titleId})", "اللعبة نفسها (${g.titleId})"))
            Text("• " + tr("its installed update and add-on content", "تحديثها ومحتواها الإضافي المثبّت"))
            Text(tr("Kept: saved games, trophies, captures.", "تبقى: الحفظ (Save) والإنجازات واللقطات."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tr("Close the game on the PS4 first if it is running.", "أغلق اللعبة على الـPS4 أولًا إن كانت تعمل."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(onClick = { step = 2 }) { Lbl(tr("Continue", "متابعة")) } },
        dismissButton = { TextButton(onClick = { step = 0 }) { Lbl(tr("Cancel", "إلغاء")) } })
    if (step == 2) {
        var left by remember { mutableIntStateOf(4) }
        LaunchedEffect(Unit) { while (left > 0) { delay(1000); left-- } }
        AlertDialog(onDismissRequest = { step = 0 },
            icon = { Ico(R.drawable.ic_warning, 28.dp, MaterialTheme.colorScheme.error) },
            title = { Text(tr("Final warning", "تحذير أخير")) },
            text = { Text(tr("This will delete “${g.title}” COMPLETELY from the console. It cannot be undone: to play it again you would have to download and install it again.",
                "سيحذف هذا «${g.title}» كليًا من الجهاز. لا يمكن التراجع: لتلعبها مجددًا يجب تحميلها وتثبيتها من جديد.")) },
            confirmButton = { TextButton(enabled = left == 0, onClick = {
                step = 3
                scope.launch {
                    val r = withContext(Dispatchers.IO) { StorageOps.deleteGame(ps4, g.titleId) }
                    Ps4Info.invalidate()
                    result = r; step = 0
                }
            }) { Lbl(tr("Delete completely", "حذف نهائيًا") + if (left > 0) " ($left)" else "", color = if (left == 0) MaterialTheme.colorScheme.error else Color.Unspecified) } },
            dismissButton = { TextButton(onClick = { step = 0 }) { Lbl(tr("Keep the game", "الإبقاء على اللعبة")) } })
    }
    if (step == 3) AlertDialog(onDismissRequest = { },
        title = { Text(tr("Deleting…", "جارٍ الحذف…")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("Keep the app open. This can take a few minutes.", "أبقِ التطبيق مفتوحًا. قد يستغرق بضع دقائق.")) } },
        confirmButton = { })
    result?.let { r ->
        AlertDialog(onDismissRequest = { result = null; if (r.ok) { onDeleted(g.titleId); onClose() } },
            title = { Text(if (r.ok) tr("Done", "تم") else tr("Not finished", "لم يكتمل")) },
            text = { Text(r.message + if (r.ok) "\n\n" + tr("If the icon is still on the PS4 home screen, press Options > Delete on it there to clear it.",
                "إن بقيت الأيقونة في الشاشة الرئيسية للـPS4 فاضغط عليها Options ← Delete لإزالتها.") else "") },
            confirmButton = { TextButton(onClick = { result = null; if (r.ok) { onDeleted(g.titleId); onClose() } }) { Lbl("OK") } })
    }
}
