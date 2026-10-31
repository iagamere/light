@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

private fun modeLabel(m: ThemeMode) = when (m) {
    ThemeMode.DYNAMIC -> tr("Game theme", "ثيم اللعبة")
    ThemeMode.PRESET -> tr("Preset", "جاهز")
    ThemeMode.CUSTOM -> tr("Custom", "مخصص")
    ThemeMode.DEFAULT -> tr("App default", "الافتراضي")
}
private fun modeHint(m: ThemeMode) = when (m) {
    ThemeMode.DYNAMIC -> tr("Each game page takes its colours from the game's artwork. Other screens use the app default.", "تأخذ كل صفحة لعبة ألوانها من صورة اللعبة. باقي الشاشات تستخدم الافتراضي.")
    ThemeMode.PRESET -> tr("The chosen preset is used on every screen, including every game.", "يُستخدم الثيم الجاهز المختار في كل الشاشات وكل الألعاب.")
    ThemeMode.CUSTOM -> tr("Your own colour profile is used on every screen.", "يُستخدم ملفك اللوني في كل الشاشات.")
    ThemeMode.DEFAULT -> tr("The standard PS4 Monitor look (Light / Dark / phone colours stay available under Appearance).", "المظهر القياسي للتطبيق (الفاتح/الداكن/ألوان الهاتف متاحة ضمن «المظهر»).")
}

/** Isolated sample screen: it only reads [t], never real data, and never touches the saved theme. */
@Composable fun ThemePreview(t: AppTheme, modifier: Modifier = Modifier) {
    val k = ThemeEngine.tokens(t)
    val shape = RoundedCornerShape(20.dp)
    CompositionLocalProvider(LocalTokens provides k) {
        MaterialTheme(colorScheme = ThemeEngine.scheme(t), shapes = MaterialTheme.shapes) {
            Column(modifier.fillMaxWidth().clip(shape).background(Brush.verticalGradient(listOf(k.bg2, k.bg))).border(1.dp, k.border, shape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
                    Text(tr("Sample Game", "لعبة تجريبية"), color = k.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("CUSA00000  •  v01.00", color = k.text2, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(k.surface).padding(4.dp)) {
                    listOf(tr("Details", "التفاصيل"), tr("Captures", "اللقطات"), tr("Trophies", "الإنجازات")).forEachIndexed { i, s ->
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (i == 2) k.accent.copy(alpha = 0.22f) else Color.Transparent).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Lbl(s, color = if (i == 2) k.accent else k.text2, weight = if (i == 2) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Ali" to 0, "Sara" to 54, "Omar" to 12).forEach { (n, pct) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.matchParentSize()) {
                                    val w = 4.dp.toPx(); val sz = Size(size.width - w, size.height - w)
                                    drawArc(k.border, 0f, 360f, false, Offset(w / 2, w / 2), sz, style = Stroke(w))
                                    if (pct > 0) drawArc(if (pct == 54) k.accent else k.accent2, -90f, 360f * pct / 100f, false, Offset(w / 2, w / 2), sz, style = Stroke(w, cap = StrokeCap.Round))
                                }
                                Box(Modifier.size(34.dp).clip(CircleShape).background(k.surface), contentAlignment = Alignment.Center) { Text(n.take(1), color = k.text, fontWeight = FontWeight.Bold) }
                            }
                            Text(n, color = if (pct == 54) k.accent else k.text2, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(k.surface).border(1.dp, k.border, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("54%", color = k.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                        Text(tr("19 of 35 trophies", "19 من 35 إنجازًا"), color = k.text2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(k.border)) { Box(Modifier.fillMaxWidth(0.54f).fillMaxHeight().background(k.progress)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        listOf(Color(0xFF7FB2E5) to "0/1", Color(0xFFE2B33C) to "1/5", Color(0xFFB4BCC6) to "3/11", Color(0xFFC07A3E) to "15/18").forEach { (c, s) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(c)); Text(s, color = k.text, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(k.accent).padding(horizontal = 16.dp, vertical = 9.dp)) { Lbl(tr("Refresh", "تحديث"), color = k.onAccent, weight = FontWeight.SemiBold) }
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, k.accent2, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 9.dp)) { Lbl(tr("Wake PS4", "إيقاظ"), color = k.accent2) }
                }
            }
        }
    }
}

@Composable private fun Swatches(t: AppTheme, size: Int = 14) = Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    listOf(t.bg, t.surface, t.accent, t.accent2, t.text).forEach { Box(Modifier.size(size.dp).clip(CircleShape).background(Color(it)).border(0.5.dp, Color.White.copy(alpha = 0.25f), CircleShape)) }
}

@Composable private fun ThemeCard(t: AppTheme, active: Boolean, shown: Boolean, onClick: () -> Unit) {
    val k = ThemeEngine.tokens(t); val shape = RoundedCornerShape(16.dp)
    Column(Modifier.width(132.dp).clip(shape).background(Brush.verticalGradient(listOf(k.bg2, k.bg))).border(if (shown) 2.dp else 1.dp, if (shown) k.accent else k.border, shape)
        .clickable(onClick = onClick).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(k.surface)) {
            Box(Modifier.padding(6.dp).fillMaxWidth(0.55f).height(5.dp).clip(CircleShape).background(k.accent))
            Box(Modifier.align(Alignment.BottomStart).padding(6.dp).fillMaxWidth(0.3f).height(5.dp).clip(CircleShape).background(k.accent2))
        }
        Lbl(t.name, color = k.text, weight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Swatches(t, 12)
            if (active) Box(Modifier.size(7.dp).clip(CircleShape).background(k.success))
        }
    }
}

/** HEX field + swatch + hue / saturation / lightness sliders. Only complete, valid colours are accepted; the rest is ignored safely. */
@Composable private fun ColorRow(label: String, value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(ThemeEngine.hex(value)) }
    val valid = ThemeEngine.parseHex(text) != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(value)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)).clickable { open = !open })
            Text(label, Modifier.weight(1f).clickable { open = !open }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(text, { s -> text = s.take(7); val h = s.trim().removePrefix("#"); if (h.length == 6) ThemeEngine.parseHex(h)?.let(onChange) },
                Modifier.width(118.dp), singleLine = true, isError = !valid, textStyle = MaterialTheme.typography.bodyMedium)
        }
        if (open) {
            val hsl = remember(value) { ColorMath.toHsl(value) }
            Column(Modifier.padding(start = 50.dp)) {
                Slider(hsl[0], { onChange(ColorMath.fromHsl(it, hsl[1], hsl[2])) }, valueRange = 0f..360f)
                Slider(hsl[1], { onChange(ColorMath.fromHsl(hsl[0], it, hsl[2])) }, valueRange = 0f..1f)
                Slider(hsl[2], { onChange(ColorMath.fromHsl(hsl[0], hsl[1], it)) }, valueRange = 0f..1f)
            }
        }
    }
}

@Composable fun ThemeSettingsScreen(nav: NavController) {
    val mode = ThemeEngine.mode
    val profiles = ThemeEngine.profiles
    var pending by remember { mutableStateOf(ThemeEngine.base) }           // what the preview shows (saved choice or a tapped card)
    var editing by remember { mutableStateOf<AppTheme?>(null) }            // unsaved draft, preview only
    var delete by remember { mutableStateOf<AppTheme?>(null) }
    var importing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current; val ctx = LocalContext.current
    val shown = editing ?: pending
    fun activeId(): String? = when (mode) { ThemeMode.PRESET -> ThemeEngine.presetId; ThemeMode.CUSTOM -> ThemeEngine.activeProfileId; ThemeMode.DEFAULT -> "default"; else -> null }
    val ed = editing

    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { BackHeader(tr("Theme", "الثيم"), nav) }
        item { ThemePreview(shown) }
        if (ed == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(tr("Theme mode", "نمط الثيم"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.values().forEach { m ->
                            FilterChip(mode == m, {
                                when (m) {
                                    ThemeMode.DEFAULT -> { ThemeEngine.chooseMode(m); pending = ThemeEngine.DEFAULT }
                                    ThemeMode.DYNAMIC -> ThemeEngine.chooseMode(m)
                                    ThemeMode.PRESET -> ThemeEngine.selectPreset(ThemeEngine.presetId.takeIf { it != "default" } ?: "aurora").also { pending = ThemeEngine.base }
                                    ThemeMode.CUSTOM -> if (profiles.isEmpty()) editing = ThemeEngine.editableCopy(pending).copy(name = tr("My theme", "ثيمي"))
                                                        else { ThemeEngine.selectProfile(ThemeEngine.activeProfileId ?: profiles.first().id); pending = ThemeEngine.base }
                                }
                            }, label = { Lbl(modeLabel(m)) })
                        }
                    }
                    Dim(modeHint(mode))
                    if (mode == ThemeMode.PRESET || mode == ThemeMode.CUSTOM) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { Text(tr("Use each game's accent colours", "استخدم ألوان تمييز كل لعبة")); Dim(tr("Keeps this theme's background and only takes the game's accents.", "يبقي خلفية هذا الثيم ويأخذ ألوان التمييز من اللعبة فقط.")) }
                        Switch(ThemeEngine.applyGameAccents, { ThemeEngine.setGameAccents(it) })
                    }
                }
            }
            if (shown.id != activeId() && mode != ThemeMode.DYNAMIC) item {
                Button(onClick = { if (shown.builtin) ThemeEngine.selectPreset(shown.id) else ThemeEngine.selectProfile(shown.id); pending = ThemeEngine.base }, Modifier.fillMaxWidth()) {
                    Ico(R.drawable.ic_check, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Apply “${shown.name}”", "تطبيق «${shown.name}»")) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(tr("Ready-made themes", "ثيمات جاهزة"))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ThemeEngine.PRESETS, key = { it.id }) { t -> ThemeCard(t, mode == ThemeMode.PRESET && ThemeEngine.presetId == t.id || mode == ThemeMode.DEFAULT && t.id == "default", pending.id == t.id) { pending = t } }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(tr("My profiles", "ملفاتي"))
                    if (profiles.isEmpty()) Dim(tr("No custom profile yet. Create one from the current look.", "لا توجد ملفات مخصصة بعد. أنشئ واحدًا من المظهر الحالي."))
                    profiles.forEach { p ->
                        val on = mode == ThemeMode.CUSTOM && ThemeEngine.activeProfileId == p.id
                        Card(Modifier.fillMaxWidth().clickable { pending = p }, shape = MaterialTheme.shapes.large,
                            border = if (pending.id == p.id) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Swatches(p)
                                    Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (on) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { Lbl(tr("Active", "نشط"), Modifier.padding(horizontal = 10.dp, vertical = 3.dp), MaterialTheme.typography.labelSmall) }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (!on) TextButton(onClick = { ThemeEngine.selectProfile(p.id); pending = ThemeEngine.base }) { Lbl(tr("Use", "استخدام")) }
                                    TextButton(onClick = { editing = p }) { Lbl(tr("Edit", "تعديل")) }
                                    TextButton(onClick = { pending = ThemeEngine.duplicate(p) }) { Lbl(tr("Duplicate", "نسخ")) }
                                    TextButton(onClick = { clip.setText(AnnotatedString(ThemeEngine.toJson(p).toString())); android.widget.Toast.makeText(ctx, tr("Theme copied", "تم نسخ الثيم"), android.widget.Toast.LENGTH_SHORT).show() }) { Lbl(tr("Export", "تصدير")) }
                                    TextButton(onClick = { delete = p }) { Lbl(tr("Delete", "حذف"), color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { editing = ThemeEngine.editableCopy(shown).copy(name = if (shown.builtin) shown.name + " (mine)" else shown.name) }) { Ico(R.drawable.ic_add, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("New profile", "ملف جديد")) }
                        OutlinedButton(onClick = { importing = true }) { Ico(R.drawable.ic_paste, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Import", "استيراد")) }
                    }
                }
            }
            item { OutlinedButton(onClick = { confirmReset = true }, Modifier.fillMaxWidth()) { Lbl(tr("Restore the default appearance", "استعادة المظهر الافتراضي")) } }
        } else {
            item { SectionTitle(if (ed.builtin || ThemeEngine.profiles.none { it.id == ed.id }) tr("New profile", "ملف جديد") else tr("Edit profile", "تعديل الملف")) }
            item { OutlinedTextField(ed.name, { editing = ed.copy(name = it.take(32)) }, Modifier.fillMaxWidth(), label = { Text(tr("Profile name", "اسم الملف")) }, singleLine = true, isError = ed.name.isBlank()) }
            item {
                Panel {
                    ColorRow(tr("Primary background", "الخلفية الأساسية"), ed.bg) { editing = ed.copy(bg = it) }
                    ColorRow(tr("Secondary background", "الخلفية الثانوية"), ed.bg2) { editing = ed.copy(bg2 = it) }
                    ColorRow(tr("Card / surface", "البطاقة / السطح"), ed.surface) { editing = ed.copy(surface = it) }
                    ColorRow(tr("Primary accent", "لون التمييز الأساسي"), ed.accent) { editing = ed.copy(accent = it) }
                    ColorRow(tr("Secondary accent", "لون التمييز الثانوي"), ed.accent2) { editing = ed.copy(accent2 = it) }
                    ColorRow(tr("Primary text", "النص الأساسي"), ed.text) { editing = ed.copy(text = it) }
                    ColorRow(tr("Secondary text", "النص الثانوي"), ed.text2) { editing = ed.copy(text2 = it) }
                    ColorRow(tr("Borders", "الحدود"), ed.border) { editing = ed.copy(border = it) }
                    ColorRow(tr("Progress", "التقدّم"), ed.progress) { editing = ed.copy(progress = it) }
                }
            }
            item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { Text(tr("Take each game's accents", "خذ ألوان تمييز كل لعبة")); Dim(tr("Only the accents change per game; your backgrounds stay.", "تتغير ألوان التمييز فقط لكل لعبة وتبقى خلفياتك.")) }
                Switch(ed.gameAccents, { editing = ed.copy(gameAccents = it) }) } }
            item { Dim(tr("Colours that would be hard to read are adjusted automatically when you save.", "تُعدَّل تلقائيًا الألوان التي يصعب قراءتها عند الحفظ.")) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = ed.name.isNotBlank(), onClick = { ThemeEngine.saveProfile(ed, true); pending = ThemeEngine.base; editing = null }) { Lbl(tr("Save and apply", "حفظ وتطبيق")) }
                    OutlinedButton(onClick = { editing = null }) { Lbl(tr("Discard changes", "تجاهل التغييرات")) }
                    ThemeEngine.preset(ed.basePreset)?.let { b -> TextButton(onClick = { editing = ed.copy(bg = b.bg, bg2 = b.bg2, surface = b.surface, accent = b.accent, accent2 = b.accent2, text = b.text, text2 = b.text2, border = b.border, progress = b.progress) }) { Lbl(tr("Restore “${b.name}” values", "استعادة قيم «${b.name}»")) } }
                }
            }
        }
    }
    delete?.let { p -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(tr("Delete “${p.name}”?", "حذف «${p.name}»؟")) },
        text = { Text(tr("This profile will be removed permanently.", "سيُحذف هذا الملف نهائيًا.")) },
        confirmButton = { TextButton(onClick = { ThemeEngine.deleteProfile(p.id); if (pending.id == p.id) pending = ThemeEngine.base; delete = null }) { Lbl(tr("Delete", "حذف"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Lbl(tr("Cancel", "إلغاء")) } }) }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text(tr("Restore the default appearance?", "استعادة المظهر الافتراضي؟")) },
        text = { Text(tr("Your saved profiles are kept.", "تبقى ملفاتك المحفوظة.")) },
        confirmButton = { TextButton(onClick = { ThemeEngine.setGameAccents(false); ThemeEngine.resetToDefault(); pending = ThemeEngine.DEFAULT; confirmReset = false }) { Lbl(tr("Restore", "استعادة")) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Lbl(tr("Cancel", "إلغاء")) } })
    if (importing) {
        var txt by remember { mutableStateOf("") }; var err by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { importing = false }, title = { Text(tr("Import a theme", "استيراد ثيم")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(txt, { txt = it; err = false }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 8, isError = err, label = { Text(tr("Paste the exported theme", "الصق الثيم المُصدَّر")) })
                if (err) Text(tr("This is not a valid theme.", "هذا ليس ثيمًا صالحًا."), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = { val t = ThemeEngine.importJson(txt); if (t == null) err = true else { pending = t; importing = false } }) { Lbl(tr("Import", "استيراد")) } },
            dismissButton = { TextButton(onClick = { importing = false }) { Lbl(tr("Cancel", "إلغاء")) } })
    }
}
