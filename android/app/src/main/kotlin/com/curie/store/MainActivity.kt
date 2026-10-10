package com.curie.store

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.URL

class MainActivity : Activity() {
    private lateinit var pal: Pal
    private lateinit var session: Session
    private lateinit var auth: AuthUi
    private lateinit var store: StoreApi
    private var apps = listOf<StoreApp>()
    private var shown = listOf<StoreApp>()
    private var stats = mapOf<String, Stat>()
    private val wish = HashSet<String>()
    private var cat = ""
    private var query = ""
    private var loadMsg = "Loading"
    private var sheetRefresh: (() -> Unit)? = null
    private lateinit var chipRow: LinearLayout
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var accountBtn: TextView
    private val tiles = Tiles()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pal = Pal(this)
        session = Session(this)
        store = StoreApi(session)
        auth = AuthUi(this, session, pal) { authChanged() }
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        if (!pal.dark) lightBars()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(14), dp(16), dp(8)) }
        head.addView(label("Curie Store", 22f, pal.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        accountBtn = label("", 14f, pal.ink).apply {
            gravity = Gravity.CENTER
            minimumWidth = dp(40)
            setOnClickListener { auth.open() }
        }
        head.addView(accountBtn, LinearLayout.LayoutParams(-2, dp(40)))
        root.addView(head)

        val search = EditText(this).apply {
            hint = "Search"; setHintTextColor(pal.mute); setTextColor(pal.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
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

        paintAccount()
        buildChips()
        loadStore()
        loadStats()
        verifySession()
    }

    @Suppress("DEPRECATION")
    private fun lightBars() {
        var f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = f
    }

    // ---------- account ----------

    private fun paintAccount() {
        if (session.signedIn) {
            accountBtn.text = (session.name.ifEmpty { session.username.ifEmpty { "C" } }).take(1).uppercase()
            accountBtn.setTextColor(pal.blueInk)
            accountBtn.setPadding(0, 0, 0, 0)
            accountBtn.background = pill(pal.blue, dpf(40))
            accountBtn.typeface = android.graphics.Typeface.DEFAULT_BOLD
        } else {
            accountBtn.text = "Sign in"
            accountBtn.setTextColor(pal.ink)
            accountBtn.setPadding(dp(16), 0, dp(16), 0)
            accountBtn.background = pill(pal.chip, dpf(40))
            accountBtn.typeface = android.graphics.Typeface.DEFAULT
        }
    }

    /** Called after sign-in, sign-out, or when the session is found to be expired. */
    private fun authChanged() {
        paintAccount()
        if (session.signedIn) loadWish() else { wish.clear(); if (cat == "__wish") cat = "" }
        buildChips(); applyFilter()
        sheetRefresh?.invoke()
    }

    private fun lost() {
        session.clear()
        Toast.makeText(this, "Signed out. Sign in again.", Toast.LENGTH_LONG).show()
        authChanged()
    }

    private fun verifySession() {
        if (!session.signedIn) return
        Thread {
            try {
                val r = Net.call("GET", Cfg.CURIE_API + "/me", null, session.token)
                if (r.code == 401) runOnUiThread { session.clear(); authChanged() }
                else if (r.ok) runOnUiThread { session.setNames(r.json.str("displayName"), r.json.str("username")); authChanged() }
            } catch (_: Exception) { }
        }.start()
    }

    // ---------- data ----------

    private fun loadStore() = Thread {
        try {
            val txt = URL(Cfg.STORE_BASE + "store.json").openStream().bufferedReader().use { it.readText() }
            val arr = JSONObject(txt).getJSONArray("apps")
            val out = (0 until arr.length()).map {
                val o = arr.getJSONObject(it); val l = o.getJSONObject("latest"); val p = l.getJSONArray("permissions")
                val ic = o.str("icon")
                StoreApp(
                    o.getString("id"), o.getString("name"), o.getString("author"), o.optString("category", "tools"),
                    o.str("description"),
                    if (Regex("icons/[\\w.\\-]+").matches(ic)) Cfg.STORE_BASE + ic else null,
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

    private fun loadStats() = Thread {
        try { val s = store.stats(); runOnUiThread { stats = s; tiles.notifyDataSetChanged() } } catch (_: Exception) { }
    }.start()

    private fun loadWish() = Thread {
        try {
            val w = store.wishlist()
            runOnUiThread { wish.clear(); wish.addAll(w); buildChips(); applyFilter() }
        } catch (e: AuthLost) { runOnUiThread { lost() } } catch (_: Exception) { }
    }.start()

    private fun buildChips() {
        chipRow.removeAllViews()
        val all = listOf("") + (if (session.signedIn) listOf("__wish") else emptyList()) + (CATS + apps.map { it.category }).distinct()
        for (c in all) {
            val on = c == cat
            val t = label(if (c == "") "All" else if (c == "__wish") "♥ Wishlist" else c.cap(), 14f, if (on) pal.blueInk else pal.ink).apply {
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = pill(if (on) pal.blue else pal.chip, dpf(40))
                setOnClickListener { cat = c; buildChips(); applyFilter() }
            }
            chipRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
    }

    private fun applyFilter() {
        shown = apps.filter {
            (if (cat == "__wish") wish.contains(it.id) else (cat == "" || it.category == cat)) &&
                "${it.name} ${it.author} ${it.category}".lowercase().contains(query)
        }
        empty.text = if (apps.isEmpty()) loadMsg else if (cat == "__wish") "Your wishlist is empty" else "No apps found"
        tiles.notifyDataSetChanged()
    }

    inner class Tiles : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, v: View?, p: ViewGroup): View {
            val a = shown[i]
            val st = stats[a.id]
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(12), dp(10), dp(12)) }
            col.addView(iconBox(a, 72), LinearLayout.LayoutParams(dp(72), dp(72)))
            col.addView(label(a.name, 14f, pal.ink).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            col.addView(label(a.author, 12f, pal.mute).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val line = (if (st != null && st.ratings > 0) "★ %.1f · ".format(st.rating) else "") + mb(a.size)
            col.addView(label(line, 12f, pal.mute).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            return col
        }
    }

    // ---------- app page ----------

    private fun installedCode(id: String): Long? = try {
        val i = packageManager.getPackageInfo(id, 0)
        if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else @Suppress("DEPRECATION") i.versionCode.toLong()
    } catch (e: Exception) { null }

    private fun showSheet(app: StoreApp) {
        val sh = Sheet(this, pal)
        val st0 = stats[app.id] ?: Stat(0.0, 0, 0)
        fun fmtRating(s: Stat) = if (s.ratings > 0) "★ %.1f".format(s.rating) else "-"
        fun fmtCount(s: Stat) = if (s.ratings > 0) "${s.ratings} rating" + (if (s.ratings > 1) "s" else "") else "No ratings"

        // top row
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconBox(app, 76), LinearLayout.LayoutParams(dp(76), dp(76)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(8), 0) }
        names.addView(label(app.name, 21f, pal.ink, true))
        names.addView(label("${app.author} · ${app.category.cap()}", 14f, pal.mute))
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        val heart = label(if (wish.contains(app.id)) "♥" else "♡", 18f, pal.ink).apply {
            gravity = Gravity.CENTER; background = pill(pal.chip, dpf(40))
        }
        top.addView(heart, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) })
        top.addView(label("✕", 16f, pal.ink).apply {
            gravity = Gravity.CENTER; background = pill(pal.chip, dpf(40)); setOnClickListener { sh.dismiss() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        sh.add(top)

        heart.setOnClickListener {
            if (!session.signedIn) { auth.open(); return@setOnClickListener }
            val on = !wish.contains(app.id)
            Thread {
                try {
                    store.setWish(app.id, on)
                    runOnUiThread {
                        if (on) wish.add(app.id) else wish.remove(app.id)
                        heart.text = if (on) "♥" else "♡"
                        buildChips(); applyFilter()
                    }
                } catch (e: AuthLost) { runOnUiThread { lost() } } catch (_: Exception) { }
            }.start()
        }

        // install / update / open
        val have = installedCode(app.id)
        val action = when { have == null -> "Install"; have < app.versionCode -> "Update"; else -> "Open" }
        val err = label("", 13f, pal.bad).apply { visibility = View.GONE }
        val btn = button(action, pal)
        var busy = false
        btn.setOnClickListener {
            if (busy) return@setOnClickListener
            if (action == "Open") { packageManager.getLaunchIntentForPackage(app.id)?.let { startActivity(it) }; return@setOnClickListener }
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, "Allow installs from Curie Store, then tap again", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            busy = true; err.visibility = View.GONE
            Thread {
                try {
                    Installer.run(this, app) { s -> runOnUiThread { btn.text = s } }
                    runOnUiThread { btn.text = "Confirm to install" }
                    if (session.signedIn) try { store.countInstall(app.id) } catch (_: Exception) { }
                } catch (e: Exception) {
                    runOnUiThread { busy = false; btn.text = action; err.text = e.message ?: "Install failed"; err.visibility = View.VISIBLE }
                }
            }.start()
        }
        sh.add(btn, 20)
        sh.add(err, 8)

        // stats row
        val rateTop = label(fmtRating(st0), 15f, pal.ink, true).apply { gravity = Gravity.CENTER }
        val rateSub = label(fmtCount(st0), 12f, pal.mute).apply { gravity = Gravity.CENTER }
        val instTop = label(st0.installs.toString(), 15f, pal.ink, true).apply { gravity = Gravity.CENTER }
        val statsRow = LinearLayout(this)
        fun cell(a: TextView, b: TextView) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(a); c.addView(b)
            statsRow.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        }
        cell(rateTop, rateSub)
        cell(instTop, label("Installs", 12f, pal.mute).apply { gravity = Gravity.CENTER })
        cell(label(app.version, 15f, pal.ink, true).apply { gravity = Gravity.CENTER }, label("Version", 12f, pal.mute).apply { gravity = Gravity.CENTER })
        cell(label(mb(app.size), 15f, pal.ink, true).apply { gravity = Gravity.CENTER }, label("Size", 12f, pal.mute).apply { gravity = Gravity.CENTER })
        statsRow.setPadding(0, dp(14), 0, dp(14))
        sh.add(statsRow, 6)

        if (app.description.isNotBlank()) sh.add(label(app.description, 15f, pal.ink), 4)

        // ratings and reviews
        sh.add(label("Ratings & reviews", 15f, pal.ink, true), 16)
        val rateBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val reviewBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sh.add(rateBox, 8)
        sh.add(reviewBox, 8)

        var mine: Mine? = null
        var stars = 0
        val starViews = (1..5).map { label("☆", 30f, pal.blue).apply { setPadding(0, 0, dp(6), 0) } }
        fun paintStars(n: Int) { starViews.forEachIndexed { i, v -> v.text = if (i < n) "★" else "☆" } }
        starViews.forEachIndexed { idx, v -> v.setOnClickListener { stars = idx + 1; paintStars(stars) } }
        val review = EditText(this).apply {
            hint = "Write a review (optional)"; setHintTextColor(pal.mute); setTextColor(pal.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; gravity = Gravity.TOP
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = pill(pal.bg, dpf(14)).apply { setStroke(dp(1), pal.line) }
        }
        val rateErr = label("", 13f, pal.bad)

        fun drawForm() {
            rateBox.removeAllViews()
            if (!session.signedIn) {
                rateBox.addView(button("Sign in to rate", pal, false).apply { setOnClickListener { auth.open() } })
                return
            }
            mine?.let { stars = it.stars; review.setText(it.review) }
            paintStars(stars)
            val row = LinearLayout(this)
            starViews.forEach { (it.parent as? ViewGroup)?.removeView(it); row.addView(it) }
            rateBox.addView(row)
            (review.parent as? ViewGroup)?.removeView(review)
            rateBox.addView(review, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            (rateErr.parent as? ViewGroup)?.removeView(rateErr)
            rateBox.addView(rateErr, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            val actions = LinearLayout(this)
            val submit = button(if (mine != null) "Update" else "Submit", pal)
            actions.addView(submit, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10) })
            if (mine != null) {
                val remove = button("Remove", pal, false)
                actions.addView(remove, LinearLayout.LayoutParams(0, -2, 1f))
                remove.setOnClickListener {
                    Thread {
                        try { store.unrate(app.id); mine = null; stars = 0; runOnUiThread { review.setText(""); refreshAll() } }
                        catch (e: AuthLost) { runOnUiThread { lost() } }
                        catch (e: Exception) { runOnUiThread { rateErr.text = e.message ?: "Could not remove" } }
                    }.start()
                }
            }
            rateBox.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            submit.setOnClickListener {
                if (stars == 0) { rateErr.text = "Choose a star rating"; return@setOnClickListener }
                rateErr.text = ""; submit.isEnabled = false
                val text = review.text.toString()
                val n = stars
                Thread {
                    try { store.rate(app.id, n, text); mine = Mine(n, text); runOnUiThread { refreshAll(); loadStats() } }
                    catch (e: AuthLost) { runOnUiThread { lost() } }
                    catch (e: Exception) { runOnUiThread { rateErr.text = e.message ?: "Could not save"; submit.isEnabled = true } }
                }.start()
            }
        }

        fun drawReviews(list: List<Review>) {
            reviewBox.removeAllViews()
            for (r in list) {
                val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                c.addView(label(r.name + "   " + "★".repeat(r.stars), 13f, pal.ink, true))
                if (r.text.isNotEmpty()) c.addView(label(r.text, 14f, pal.mute))
                reviewBox.addView(c)
            }
        }

        refreshAllImpl = {
            Thread {
                try {
                    val info = store.app(app.id)
                    val rv = store.reviews(app.id)
                    runOnUiThread {
                        rateTop.text = fmtRating(info.stat); rateSub.text = fmtCount(info.stat); instTop.text = info.stat.installs.toString()
                        if (session.signedIn) {
                            mine = info.mine
                            info.wishlisted?.let { w -> if (w) wish.add(app.id) else wish.remove(app.id); heart.text = if (w) "♥" else "♡" }
                        } else mine = null
                        drawForm(); drawReviews(rv)
                    }
                } catch (e: Exception) { runOnUiThread { drawForm() } }
            }.start()
        }
        sheetRefresh = { refreshAll() }
        sh.onDismiss { sheetRefresh = null; refreshAllImpl = {} }

        // details
        fun section(title: String, text: String, mono: Boolean = false) {
            sh.add(label(title, 15f, pal.ink, true), 16)
            sh.add(label(text, if (mono) 12f else 14f, pal.mute).apply { if (mono) typeface = android.graphics.Typeface.MONOSPACE }, 6)
        }
        section("Permissions", if (app.permissions.isEmpty()) "None" else app.permissions.joinToString(", ") { it.removePrefix("android.permission.") })
        section("Signed by", app.certSha256, true)
        section("Checksum", app.sha256, true)

        drawForm()
        sh.show()
        refreshAll()
    }

    private var refreshAllImpl: () -> Unit = {}
    private fun refreshAll() = refreshAllImpl()
}
