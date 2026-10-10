package com.curie.store

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URL

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
    val bad = p("#D92D20", "#FF7A70")
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

fun Context.button(s: String, pal: Pal, primary: Boolean = true) =
    label(s, 15f, if (primary) pal.blueInk else pal.ink, primary).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(13), dp(18), dp(13))
        background = pill(if (primary) pal.blue else pal.chip, dpf(40))
    }

fun Context.input(hint: String, pal: Pal, password: Boolean = false) = EditText(this).apply {
    this.hint = hint; setHintTextColor(pal.mute); setTextColor(pal.ink)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); isSingleLine = true
    inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    setPadding(dp(16), dp(12), dp(16), dp(12))
    background = pill(pal.bg, dpf(14)).apply { setStroke(dp(1), pal.line) }
}

/** A bottom sheet with a rounded top, used for the app page and the sign-in screens. */
class Sheet(private val a: Activity, pal: Pal) {
    private val holder = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(20), a.dp(20), a.dp(20), a.dp(28))
    }
    private val dialog = Dialog(a)

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val r = a.dpf(24)
        val scroll = ScrollView(a).apply {
            background = GradientDrawable().apply { setColor(pal.surface); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            addView(holder)
        }
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun clear() = holder.removeAllViews()
    fun add(v: View, topDp: Int = 0) {
        holder.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(topDp) })
    }
    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
    fun onDismiss(f: () -> Unit) = dialog.setOnDismissListener { f() }
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
