package app.lawnchair.ui.preferences.destinations

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import app.lawnchair.mimax.IslandStyle
import app.lawnchair.mimax.MiMaxIslandPreferences

@Composable
fun MiMaxPreferences() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(MiMaxIslandPreferences.enabled(context)) }
    var selected by remember { mutableStateOf(MiMaxIslandPreferences.style(context)) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            runCatching { MiMaxIslandPreferences.postTestNotification(context) }
                .onFailure { Toast.makeText(context, "تعذر إرسال إشعار الاختبار: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val overlayGranted = Settings.canDrawOverlays(context)
    val listenerGranted = runCatching {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true
    }.getOrDefault(false)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Me Island", style = MaterialTheme.typography.headlineMedium)
        Text(
            "جزيرة إشعارات تظهر فوق التطبيقات. فعّل الإذنين ثم اضغط اختبار الجزيرة الآن للتأكد من أن خدمة الإشعارات والنافذة العائمة تعملان.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }) {
            Text(if (overlayGranted) "إذن الظهور فوق التطبيقات: ممنوح" else "منح إذن الظهور فوق التطبيقات")
        }
        Button(onClick = { context.startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
            Text(if (listenerGranted) "إذن الإشعارات: مفعّل" else "منح إذن الوصول للإشعارات")
        }
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                runCatching { MiMaxIslandPreferences.postTestNotification(context) }
                    .onFailure { Toast.makeText(context, "تعذر إرسال إشعار الاختبار: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }) {
            Text("اختبار الجزيرة الآن")
        }
        RowSwitch(enabled = enabled, onChange = {
            enabled = it
            MiMaxIslandPreferences.setEnabled(context, it)
        })
        Spacer(Modifier.height(8.dp))
        Text("تصميم الجزيرة", style = MaterialTheme.typography.titleLarge)
        IslandStyle.entries.forEach { style ->
            Button(onClick = {
                selected = style
                MiMaxIslandPreferences.setStyle(context, style)
            }) {
                Text((if (selected == style) "✓ " else "") + when (style) {
                    IslandStyle.HIS -> "HyperOS — HIS"
                    IslandStyle.LTIN -> "iPhone-inspired — LTIN"
                    IslandStyle.COLOROS -> "ColorOS"
                })
            }
        }
        Text(
            "القيود: تعتمد الأحداث على الإشعارات والأنشطة المستمرة التي يتيحها أندرويد. لا يستطيع لانشر عادي قراءة كل حالة التطبيقات أو استبدال SystemUI أو ضمان أحداث OEM الخاصة.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RowSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("تفعيل Me Island", style = MaterialTheme.typography.titleMedium)
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}
