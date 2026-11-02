package com.example.cam2vault.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cam2vault.data.AppSettings
import com.example.cam2vault.overlay.FloatingPreviewManager
import com.example.cam2vault.service.VideoRecordingService
import com.example.cam2vault.service.VolumeKeyService

fun isAccessibilityEnabled(ctx: Context): Boolean {
    val cn = ComponentName(ctx, VolumeKeyService::class.java)
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
}

fun isIgnoringBattery(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(service: VideoRecordingService?, resumeTick: Int) {
    val ctx = LocalContext.current

    var audio by remember(resumeTick) { mutableStateOf(AppSettings.audio(ctx)) }
    var stab by remember(resumeTick) { mutableStateOf(AppSettings.stabilization(ctx)) }
    var exp by remember(resumeTick) { mutableStateOf(AppSettings.showExperimental(ctx)) }
    var bitrate by remember(resumeTick) { mutableStateOf(AppSettings.bitratePct(ctx)) }
    var countdown by remember(resumeTick) { mutableStateOf(AppSettings.countdown(ctx)) }
    var volume by remember(resumeTick) { mutableStateOf(AppSettings.volumeTrigger(ctx)) }
    var overlay by remember(resumeTick) { mutableStateOf(AppSettings.overlayEnabled(ctx)) }
    var overlayW by remember(resumeTick) {
        mutableFloatStateOf(AppSettings.overlayWidthDp(ctx).toFloat())
    }

    val canOverlay = remember(resumeTick) { Settings.canDrawOverlays(ctx) }
    val a11y = remember(resumeTick) { isAccessibilityEnabled(ctx) }
    val battery = remember(resumeTick) { isIgnoringBattery(ctx) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {

        Section("الجودة والتسجيل")
        SwitchRow("تسجيل الصوت", "تسجيل المايك مع الفيديو", audio) { audio = it; AppSettings.setAudio(ctx, it) }
        SwitchRow("تثبيت الصورة", "تثبيت إلكتروني/بصري إن كان متاحًا", stab) { stab = it; AppSettings.setStabilization(ctx, it) }
        SwitchRow(
            "إظهار أوضاع 60 إطارًا غير المعلنة (⚠)",
            "تجربة 4K 60 حتى لو الجهاز لا يعلنها، مع الرجوع التلقائي إن فشلت",
            exp
        ) { exp = it; AppSettings.setShowExperimental(ctx, it) }
        ChoiceRow(
            "معدل البت (الجودة/الحجم)",
            listOf("50" to "اقتصادي", "75" to "متوازن", "100" to "قياسي", "150" to "عالٍ"),
            bitrate.toString()
        ) { bitrate = it.toInt(); AppSettings.setBitratePct(ctx, bitrate) }
        ChoiceRow(
            "عد تنازلي قبل التسجيل",
            listOf("0" to "بدون", "3" to "3 ث", "5" to "5 ث", "10" to "10 ث"),
            countdown.toString()
        ) { countdown = it.toInt(); AppSettings.setCountdown(ctx, countdown) }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Section("التحكم بزر الصوت")
        ChoiceRow(
            "ضغطتان سريعتان لبدء/إيقاف التسجيل",
            listOf("off" to "إيقاف", "double_down" to "خفض الصوت", "double_up" to "رفع الصوت"),
            volume
        ) { volume = it; AppSettings.setVolumeTrigger(ctx, it) }
        Text(
            "يحتاج تفعيل خدمة \"Cam2Vault\" في إعدادات إمكانية الوصول (أسفل). يعمل فوق التطبيقات الأخرى، وقد لا يعمل والشاشة مطفأة على بعض الأجهزة.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Section("المعاينة العائمة")
        SwitchRow("إظهار معاينة صغيرة أثناء التسجيل", "نافذة قابلة للسحب فوق التطبيقات", overlay) {
            overlay = it; AppSettings.setOverlayEnabled(ctx, it)
        }
        Text("الحجم الافتراضي: ${overlayW.toInt()} dp", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Slider(
            value = overlayW,
            onValueChange = { overlayW = it },
            onValueChangeFinished = { AppSettings.setOverlayWidthDp(ctx, overlayW.toInt()) },
            valueRange = FloatingPreviewManager.MIN_DP.toFloat()..FloatingPreviewManager.MAX_DP.toFloat(),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            "أثناء التسجيل: اسحب النافذة لتحريكها، اضغط عليها لإظهار الأزرار (−  ●/■  +  ✕)، أو استخدم إصبعين للتكبير والتصغير.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Section("الأذونات والعمل الدائم")
        PermRow("الكاميرا والمايك والإشعارات", checkAllPermissions(ctx), "فتح") {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        PermRow("الظهور فوق التطبيقات (للمعاينة والتشغيل من الخلفية)", canOverlay, "منح") {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        PermRow("خدمة زر الصوت (إمكانية الوصول)", a11y, "تفعيل") {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        PermRow("البطارية: بدون قيود (السماح دائمًا)", battery, "السماح") {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        Text(
            "ملاحظة: أندرويد لا يوفّر خيار \"السماح دائمًا\" لإذن الكاميرا والمايك؛ التسجيل في الخلفية يعمل عبر خدمة أمامية (إشعار دائم). " +
                "المهم تفعيل الظهور فوق التطبيقات وإلغاء قيود البطارية. في بعض الأجهزة (شاومي/أوبو/هواوي..) فعّل أيضًا \"التشغيل التلقائي\" من إعدادات التطبيق.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, fontSize = 14.sp)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }
    }
}

@Composable
private fun PermRow(title: String, granted: Boolean, buttonText: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 13.sp)
            Text(
                if (granted) "✓ مفعّل" else "✗ غير مفعّل",
                fontSize = 11.sp,
                color = if (granted) androidx.compose.ui.graphics.Color(0xFF10B981) else androidx.compose.ui.graphics.Color(0xFFF59E0B)
            )
        }
        if (!granted) Button(onClick = onClick) { Text(buttonText) }
    }
}
