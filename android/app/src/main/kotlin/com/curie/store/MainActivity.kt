package com.curie.store

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.URL

const val STORE_BASE = "https://rocurity.github.io/Curie-Store/"
val CATS = listOf("tools", "productivity", "media", "games", "social", "education", "security")

data class StoreApp(
    val id: String, val name: String, val author: String, val category: String,
    val description: String, val iconUrl: String?, val version: String, val versionCode: Int,
    val apkUrl: String, val sha256: String, val certSha256: String, val size: Long,
    val permissions: List<String>,
)

class Pal(c: Context) {
    val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun p(l: String, d: String) = Color.parseColor(if (dark) d else l)
    val bg = p("#F5F7FB", "#0A0E1A"); val surface = p("#FFFFFF", "#131A2B")
    val ink = p("#0F172A", "#EEF2FB"); val mute = p("#667085", "#8F9BB8")
    val line = p("#E3E8F0", "#232D47"); val blue = p("#2447F5", "#6F8BFF")
    val blueInk = p("#FFFFFF", "#0A0E1A"); val chip = p("#E9EEFB", "#1B2440")
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Int) = v * resources.displayMetrics.density
fun pill(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }
fun String.cap() = replaceFirstChar { it.uppercase() }
fun mb(b: Long) = if (b > 0) "%.1f MB".format(b / 1048576.0) else "-"

fun Context.label(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

object Icons {
    private val cache = LruCache<String, Bitmap>(40)
    fun load(url: String?, img: ImageView) {
        if (url == null) return
        img.tag = url
        cache.get(url)?.let { img.setImageBitmap(it); return }
        Thread {
            try {
                val bmp = URL(url).openStream().use { BitmapFactory.decodeStream(it) } ?: return@Thread
                cache.put(url, bmp)
                img.post { if (img.tag == url) img.setImageBitmap(bmp) }
            } catch (_: Exception) { }
        }.start()
    }
}

fun Context.iconBox(a: StoreApp, sizeDp: Int): FrameLayout {
    val size = dp(sizeDp); val r = size * 0.22f
    val x = a.id.fold(7) { acc, ch -> (acc * 31 + ch.code) % 360 }
    val c1 = Color.HSVToColor(floatArrayOf(x.toFloat(), .65f, .92f))
    val c2 = Color.HSVToColor(floatArrayOf(((x + 40) % 360).toFloat(), .75f, .72f))
    val box = FrameLayout(this)
    box.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { cornerRadius = r }
    box.addView(label(a.name.take(1).uppercase(), sizeDp / 2.6f, Color.WHITE, true).apply { gravity = Gravity.CENTER },
        FrameLayout.LayoutParams(-1, -1))
    val img = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, r)
        }
        clipToOutline = true
    }
    box.addView(img, FrameLayout.LayoutParams(-1, -1))
    Icons.load(a.iconUrl, img)
    return box
}

class MainActivity : Activity() {
    private lateinit var pal: Pal
    private var apps = listOf<StoreApp>()
    private var shown = listOf<StoreApp>()
    private var cat = ""
    private var query = ""
    private var loadMsg = "Loading"
    private lateinit var chipRow: LinearLayout
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private val tiles = Tiles()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pal = Pal(this)
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        if (!pal.dark) lightBars()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        root.addView(label("Curie Store", 22f, pal.ink, true).apply { setPadding(dp(20), dp(16), dp(20), dp(8)) })

        val search = EditText(this).apply {
            hint = "Search"; setHintTextColor(pal.mute); setTextColor(pal.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = pill(pal.surface, dpf(40)).apply { setStroke(dp(1), pal.line) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim().lowercase(); applyFilter() }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(12)) })

        chipRow = LinearLayout(this).apply { setPadding(dp(16), 0, dp(8), dp(12)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })

        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        grid = GridView(this).apply {
            numColumns = maxOf(3, (widthDp / 120).toInt()); adapter = tiles
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(8), 0, dp(8), dp(16)); clipToPadding = false
            setOnItemClickListener { _, _, pos, _ -> showSheet(shown[pos]) }
        }
        empty = label(loadMsg, 15f, pal.mute).apply { gravity = Gravity.CENTER }
        val frame = FrameLayout(this)
        frame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        frame.addView(empty, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        grid.emptyView = empty
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        buildChips()
        loadStore()
    }

    @Suppress("DEPRECATION")
    private fun lightBars() {
        var f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = f
    }

    private fun loadStore() = Thread {
        try {
            val txt = URL(STORE_BASE + "store.json").openStream().bufferedReader().use { it.readText() }
            val arr = JSONObject(txt).getJSONArray("apps")
            val out = (0 until arr.length()).map {
                val o = arr.getJSONObject(it); val l = o.getJSONObject("latest"); val p = l.getJSONArray("permissions")
                val ic = o.optString("icon", "")
                StoreApp(
                    o.getString("id"), o.getString("name"), o.getString("author"), o.optString("category", "tools"),
                    o.optString("description"),
                    if (Regex("icons/[\\w.\\-]+").matches(ic)) STORE_BASE + ic else null,
                    l.getString("version"), l.getInt("versionCode"), l.getString("apkUrl"),
                    l.getString("sha256"), l.getString("certSha256"), l.getLong("sizeBytes"),
                    (0 until p.length()).map { i -> p.getString(i) },
                )
            }
            runOnUiThread { apps = out; buildChips(); applyFilter() }
        } catch (e: Exception) {
            runOnUiThread { loadMsg = "Store unavailable"; applyFilter() }
        }
    }.start()

    private fun buildChips() {
        chipRow.removeAllViews()
        for (c in listOf("") + (CATS + apps.map { it.category }).distinct()) {
            val on = c == cat
            val t = label(if (c == "") "All" else c.cap(), 14f, if (on) pal.blueInk else pal.ink).apply {
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = pill(if (on) pal.blue else pal.chip, dpf(40))
                setOnClickListener { cat = c; buildChips(); applyFilter() }
            }
            chipRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
    }

    private fun applyFilter() {
        shown = apps.filter { (cat == "" || it.category == cat) && "${it.name} ${it.author} ${it.category}".lowercase().contains(query) }
        empty.text = if (apps.isEmpty()) loadMsg else "No apps found"
        tiles.notifyDataSetChanged()
    }

    inner class Tiles : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, v: View?, p: ViewGroup): View {
            val a = shown[i]
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(12), dp(10), dp(12)) }
            col.addView(iconBox(a, 72), LinearLayout.LayoutParams(dp(72), dp(72)))
            col.addView(label(a.name, 14f, pal.ink).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            col.addView(label("${a.author} · ${mb(a.size)}", 12f, pal.mute).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            return col
        }
    }

    private fun installedCode(id: String): Long? = try {
        val i = packageManager.getPackageInfo(id, 0)
        if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else @Suppress("DEPRECATION") i.versionCode.toLong()
    } catch (e: Exception) { null }

    private fun showSheet(a: StoreApp) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(28)) }
        val r = dpf(24)
        val sheet = ScrollView(this).apply {
            background = GradientDrawable().apply { setColor(pal.surface); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            addView(body)
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconBox(a, 76), LinearLayout.LayoutParams(dp(76), dp(76)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(8), 0) }
        names.addView(label(a.name, 21f, pal.ink, true))
        names.addView(label(a.author, 14f, pal.mute))
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label("✕", 16f, pal.ink).apply {
            gravity = Gravity.CENTER; background = pill(pal.chip, dpf(40)); setOnClickListener { d.dismiss() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        body.addView(top)

        val have = installedCode(a.id)
        val action = when { have == null -> "Install"; have < a.versionCode -> "Update"; else -> "Open" }
        val err = label("", 13f, Color.parseColor("#D92D20")).apply { visibility = View.GONE }
        val btn = label(action, 16f, pal.blueInk, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); background = pill(pal.blue, dpf(40))
        }
        var busy = false
        btn.setOnClickListener {
            if (busy) return@setOnClickListener
            if (action == "Open") { packageManager.getLaunchIntentForPackage(a.id)?.let { startActivity(it) }; return@setOnClickListener }
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, "Allow installs from Curie Store, then tap again", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            busy = true; err.visibility = View.GONE
            Thread {
                try {
                    Installer.run(this, a) { s -> runOnUiThread { btn.text = s } }
                    runOnUiThread { btn.text = "Confirm to install" }
                } catch (e: Exception) {
                    runOnUiThread { busy = false; btn.text = action; err.text = e.message ?: "Install failed"; err.visibility = View.VISIBLE }
                }
            }.start()
        }
        body.addView(btn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        body.addView(err, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        val stats = LinearLayout(this).apply { setPadding(0, dp(14), 0, dp(14)) }
        for ((v, l) in listOf(a.version to "Version", mb(a.size) to "Size", a.category.cap() to "Category")) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            c.addView(label(v, 15f, pal.ink, true)); c.addView(label(l, 12f, pal.mute))
            stats.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(stats)

        if (a.description.isNotBlank()) body.addView(label(a.description, 15f, pal.ink), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        fun section(title: String, text: String, mono: Boolean = false) {
            body.addView(label(title, 15f, pal.ink, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            body.addView(label(text, if (mono) 12f else 14f, pal.mute).apply { if (mono) typeface = Typeface.MONOSPACE },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
        section("Permissions", if (a.permissions.isEmpty()) "None" else a.permissions.joinToString(", ") { it.removePrefix("android.permission.") })
        section("Signed by", a.certSha256, true)
        section("Checksum", a.sha256, true)

        d.setContentView(sheet)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        d.show()
    }
}
