package com.example.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

class App(val label: String, val intent: Intent, val icon: ImageBitmap)

class Prefs(c: Context) {
    private val p = c.getSharedPreferences("s", 0)
    private val m = mutableStateMapOf<String, Int>()
    init { listOf("theme", "color", "shape", "font", "anim", "smooth").forEach { m[it] = p.getInt(it, 0) } }
    operator fun get(k: String): Int = m[k] ?: 0
    operator fun set(k: String, v: Int) { m[k] = v; p.edit().putInt(k, v).apply() }
    var pack by mutableStateOf(p.getString("pack", "") ?: "")
        private set
    fun setPack(v: String) { pack = v; p.edit().putString("pack", v).apply() }
}

val seeds = listOf(0xFF6750A4, 0xFF006C51, 0xFFB3261E, 0xFF0061A4, 0xFF8B5000, 0xFFA23A8E).map { Color(it) }
val shapes = listOf(
    CircleShape, RoundedCornerShape(30), RoundedCornerShape(6.dp),
    RoundedCornerShape(50, 50, 8, 50), RoundedCornerShape(14.dp), RoundedCornerShape(50, 8, 50, 8)
)
val fonts = listOf(FontFamily.Default, FontFamily.SansSerif, FontFamily.Serif, FontFamily.Monospace, FontFamily.Cursive)
val fontNames = listOf("Обычный", "Sans", "Serif", "Mono", "Cursive")
val animNames = listOf("Стандарт", "Samsung", "iOS", "Pixel", "Xiaomi")
val damp = listOf(1f, 0.8f, 0.5f, 0.9f, 0.6f)
val stiff = listOf(Spring.StiffnessMedium, Spring.StiffnessMedium, Spring.StiffnessLow, Spring.StiffnessMedium, Spring.StiffnessHigh)

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { Root(this) }
    }
}

fun packs(c: Context): List<Pair<String, String>> {
    val pm = c.packageManager
    return listOf("org.adw.launcher.THEME", "com.novalauncher.THEME")
        .flatMap { pm.queryIntentActivities(Intent(it), 0) }
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .distinctBy { it.first }
}

fun loadPack(c: Context, pkg: String): Pair<Resources, Map<String, String>>? = try {
    val res = c.packageManager.getResourcesForApplication(pkg)
    val id = res.getIdentifier("appfilter", "xml", pkg)
    val xp: XmlPullParser = if (id != 0) res.getXml(id)
    else Xml.newPullParser().also { it.setInput(res.assets.open("appfilter.xml"), "utf-8") }
    val map = HashMap<String, String>()
    var e = xp.eventType
    while (e != XmlPullParser.END_DOCUMENT) {
        if (e == XmlPullParser.START_TAG && xp.name == "item") {
            val comp = xp.getAttributeValue(null, "component")
            val d = xp.getAttributeValue(null, "drawable")
            if (comp != null && d != null) map[comp] = d
        }
        e = xp.next()
    }
    res to map
} catch (t: Throwable) { null }

fun loadApps(c: Context, pack: String): List<App> {
    val pm = c.packageManager
    val pk = if (pack.isEmpty()) null else loadPack(c, pack)
    val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(q, 0).mapNotNull {
        val ai = it.activityInfo
        val i = pm.getLaunchIntentForPackage(ai.packageName) ?: return@mapNotNull null
        var d: Drawable? = null
        if (pk != null) {
            val n = pk.second["ComponentInfo{${ai.packageName}/${ai.name}}"]
            val id = if (n != null) pk.first.getIdentifier(n, "drawable", pack) else 0
            if (id != 0) d = try { pk.first.getDrawable(id, null) } catch (t: Throwable) { null }
        }
        App(it.loadLabel(pm).toString(), i, (d ?: it.loadIcon(pm)).toBitmap(128, 128).asImageBitmap())
    }.sortedBy { it.label.lowercase() }
}

@Composable
fun Root(a: Activity) {
    val p = remember { Prefs(a) }
    val dark = when (p["theme"]) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val c = p["color"]
    val scheme = if (c == 0 && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(a) else dynamicLightColorScheme(a)
    } else {
        val s = seeds[if (c == 0) 0 else c - 1]
        if (dark) darkColorScheme(primary = s, primaryContainer = s.copy(alpha = .35f))
        else lightColorScheme(primary = s, primaryContainer = s.copy(alpha = .25f))
    }
    val ff = fonts[p["font"]]
    val t = Typography().run {
        copy(
            titleLarge = titleLarge.copy(fontFamily = ff), titleSmall = titleSmall.copy(fontFamily = ff),
            bodyMedium = bodyMedium.copy(fontFamily = ff), bodySmall = bodySmall.copy(fontFamily = ff),
            labelLarge = labelLarge.copy(fontFamily = ff)
        )
    }
    MaterialTheme(colorScheme = scheme, typography = t) { Home(a, p) }
}

@Composable
fun Home(a: Activity, p: Prefs) {
    var apps by remember { mutableStateOf(listOf<App>()) }
    var menu by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var lite by remember { mutableStateOf(false) }
    val view = LocalView.current
    LaunchedEffect(p.pack) { apps = withContext(Dispatchers.IO) { loadApps(a, p.pack) } }
    BackHandler(menu) { menu = false }
    DisposableEffect(Unit) {
        var n = 0
        var slow = 0
        @Suppress("DEPRECATION")
        val budget = 1e9f / a.windowManager.defaultDisplay.refreshRate
        val l = Window.OnFrameMetricsAvailableListener { _, fm, _ ->
            n++
            if (fm.getMetric(FrameMetrics.TOTAL_DURATION) > (budget * 1.5f).toLong()) slow++
            if (n >= 120) {
                if (slow > 12) lite = true else if (slow < 3) lite = false
                n = 0; slow = 0
            }
        }
        a.window.addOnFrameMetricsAvailableListener(l, Handler(Looper.getMainLooper()))
        onDispose { a.window.removeOnFrameMetricsAvailableListener(l) }
    }
    val simple = p["smooth"] == 2 || (p["smooth"] == 0 && lite)
    val shown = apps.filter { it.label.contains(q, true) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = .4f))) {
        Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Поиск") })
                if (simple) Text("⚡", Modifier.padding(start = 6.dp))
                TextButton({ menu = true }) { Text("⚙") }
            }
            LazyVerticalGrid(GridCells.Fixed(4), contentPadding = PaddingValues(8.dp)) {
                items(shown) { AppIcon(it, p, a, view, simple) }
            }
        }
        if (menu) Settings(a, p) { menu = false }
    }
}

fun go(a: Activity, app: App, v: View, x: Int, y: Int, w: Int, h: Int, i: Int) {
    val o = when (i) {
        1 -> ActivityOptions.makeCustomAnimation(a, android.R.anim.fade_in, android.R.anim.fade_out)
        2 -> ActivityOptions.makeScaleUpAnimation(v, x, y, w, h)
        3 -> ActivityOptions.makeClipRevealAnimation(v, x, y, w, h)
        4 -> ActivityOptions.makeCustomAnimation(a, android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        else -> null
    }
    a.startActivity(app.intent, o?.toBundle())
}

@Composable
fun AppIcon(app: App, p: Prefs, a: Activity, view: View, simple: Boolean) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val i = if (simple) 1 else p["anim"]
    val sp = if (simple) spring<Float>(1f, Spring.StiffnessHigh) else spring(damp[i], stiff[i])
    val sc by animateFloatAsState(if (pressed) .85f else 1f, sp, label = "s")
    var x by remember { mutableStateOf(0) }
    var y by remember { mutableStateOf(0) }
    var w by remember { mutableStateOf(0) }
    var h by remember { mutableStateOf(0) }
    Column(
        Modifier.padding(6.dp)
            .onGloballyPositioned { val o = it.positionInWindow(); x = o.x.toInt(); y = o.y.toInt(); w = it.size.width; h = it.size.height }
            .clickable(src, null) { go(a, app, view, x, y, w, h, i) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(app.icon, app.label, Modifier.size(56.dp).scale(sc).clip(shapes[p["shape"]]))
        Text(app.label, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
fun Title(s: String) = Text(s, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
fun Chips(names: List<String>, sel: Int, fam: List<FontFamily>? = null, on: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { i, n ->
            val t = @Composable { Text(n, fontFamily = fam?.get(i)) }
            if (i == sel) Button({ on(i) }) { t() } else OutlinedButton({ on(i) }) { t() }
        }
    }
}

@Composable
fun Opts(title: String, names: List<String>, p: Prefs, k: String) {
    Title(title)
    Chips(names, p[k]) { p[k] = it }
}

@Composable
fun Settings(a: Activity, p: Prefs, close: () -> Unit) {
    val list = remember { packs(a) }
    Surface(Modifier.fillMaxSize().padding(top = 28.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Настройки", style = MaterialTheme.typography.titleLarge)
            Opts("Тема", listOf("Системная", "Светлая", "Тёмная"), p, "theme")
            Opts("Цвет Material You", listOf("Из обоев", "Фиолетовый", "Зелёный", "Красный", "Синий", "Оранжевый", "Розовый"), p, "color")
            Title("Форма иконок")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                shapes.forEachIndexed { i, s ->
                    Box(Modifier.size(52.dp).clip(s)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (i == p["shape"]) 1f else .3f))
                        .clickable { p["shape"] = i })
                }
            }
            Title("Шрифт")
            Chips(fontNames, p["font"], fonts) { p["font"] = it }
            Opts("Стиль анимации", animNames, p, "anim")
            Opts("Режим плавности", listOf("Авто", "Максимум", "Экономия"), p, "smooth")
            Title("Иконпак")
            if (list.isEmpty()) Text("Иконпаки не найдены. Установите иконпак (например, из Google Play) и откройте настройки снова.")
            else Chips(listOf("Без пака") + list.map { it.second }, if (p.pack.isEmpty()) 0 else list.indexOfFirst { it.first == p.pack } + 1) {
                p.setPack(if (it == 0) "" else list[it - 1].first)
            }
            Button({ a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Обои")) },
                Modifier.padding(top = 18.dp)) { Text("Выбрать обои") }
            TextButton(close) { Text("Закрыть") }
        }
    }
}
