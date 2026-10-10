package com.curie.store

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import org.json.JSONObject
import java.util.UUID

/** The signed-in Curie ID account, kept on this phone. */
class Session(ctx: Context) {
    private val p = ctx.getSharedPreferences("curie", Context.MODE_PRIVATE)
    var token: String? = p.getString("token", null); private set
    var curieId: String = p.getString("curieId", "") ?: ""; private set
    var name: String = p.getString("name", "") ?: ""; private set
    var username: String = p.getString("username", "") ?: ""; private set
    val signedIn: Boolean get() = token != null

    val deviceId: String
        get() {
            var d = p.getString("device", null)
            if (d == null) { d = "curie-store-android-" + UUID.randomUUID(); p.edit().putString("device", d).apply() }
            return d
        }

    fun set(token: String, curieId: String, name: String, username: String) {
        this.token = token; this.curieId = curieId; this.name = name; this.username = username
        p.edit().putString("token", token).putString("curieId", curieId).putString("name", name).putString("username", username).apply()
    }

    fun setNames(name: String, username: String) {
        this.name = name; this.username = username
        p.edit().putString("name", name).putString("username", username).apply()
    }

    fun clear() {
        token = null; curieId = ""; name = ""; username = ""
        p.edit().remove("token").remove("curieId").remove("name").remove("username").apply()
    }
}

/** Curie ID sign-in: password, new-device approval, or an emailed code. */
class AuthUi(private val a: Activity, private val s: Session, private val pal: Pal, private val changed: () -> Unit) {
    @Volatile private var alive = false
    @Volatile private var done = false
    private var sheet: Sheet? = null

    fun open() { if (s.signedIn) account() else signIn() }

    private fun fresh(): Sheet {
        sheet?.dismiss()
        val sh = Sheet(a, pal)
        sheet = sh
        alive = true
        done = false
        sh.onDismiss { alive = false }
        sh.show()
        return sh
    }

    private fun title(t: String) = a.label(t, 20f, pal.ink, true)

    private fun signIn() {
        val sh = fresh()
        val id = a.input("Curie ID or username", pal)
        val pw = a.input("Password", pal, password = true)
        val err = a.label("", 13f, pal.bad)
        val go = a.button("Sign in", pal)
        val create = a.label("Create a Curie ID", 14f, pal.blue).apply {
            gravity = Gravity.CENTER
            setPadding(0, a.dp(8), 0, a.dp(8))
            setOnClickListener { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Cfg.CURIE_SIGNUP))) }
        }
        go.setOnClickListener {
            val ident = id.text.toString().trim().lowercase()
            val pass = pw.text.toString()
            if (ident.isEmpty() || pass.isEmpty()) { err.text = "Enter your Curie ID or username, and password"; return@setOnClickListener }
            go.isEnabled = false; go.text = "Signing in"; err.text = ""
            Thread {
                val r = try {
                    Net.call("POST", Cfg.CURIE_API + "/login",
                        JSONObject().put("identifier", ident).put("password", pass).put("deviceId", s.deviceId))
                } catch (e: Exception) { null }
                a.runOnUiThread {
                    pw.setText("")
                    when {
                        r == null -> { err.text = "Could not reach Curie ID. Check your connection."; go.isEnabled = true; go.text = "Sign in" }
                        r.code == 202 -> waitForApproval(sh, r.json)
                        r.ok -> finish(sh, r.json)
                        else -> { err.text = r.error.ifEmpty { "Sign-in failed" }; go.isEnabled = true; go.text = "Sign in" }
                    }
                }
            }.start()
        }
        sh.add(title("Sign in with Curie ID"))
        sh.add(id, 14); sh.add(pw, 10); sh.add(err, 8); sh.add(go, 6); sh.add(create, 10)
    }

    private fun waitForApproval(sh: Sheet, info: JSONObject) {
        val approvalId = info.str("approvalId")
        sh.clear()
        val err = a.label("", 13f, pal.bad)
        sh.add(title("Approve sign-in"))
        sh.add(ProgressBar(a), 16)
        sh.add(a.label("Approve this sign-in on a device where you are already signed in to Curie ID.", 14f, pal.mute), 8)
        sh.add(err, 8)
        if (info.optBoolean("emailFallback")) {
            sh.add(a.button("Email me a code instead", pal, false).apply { setOnClickListener { emailCode(sh, approvalId) } }, 12)
        }
        if (info.optBoolean("autoEmail")) { emailCode(sh, approvalId); return }
        val t0 = System.currentTimeMillis()
        Thread {
            while (alive && System.currentTimeMillis() - t0 < 300_000) {
                try {
                    val r = Net.call("GET", Cfg.CURIE_API + "/login/status/" + approvalId)
                    val st = r.json.str("status")
                    if (st == "approved") { a.runOnUiThread { finish(sh, r.json) }; return@Thread }
                    if (st == "denied") { a.runOnUiThread { err.text = "The sign-in was denied or expired." }; return@Thread }
                } catch (_: Exception) { }
                Thread.sleep(2500)
            }
        }.start()
    }

    private fun emailCode(sh: Sheet, approvalId: String) {
        sh.clear()
        val msg = a.label("Sending code", 14f, pal.mute)
        val code = a.input("6-digit code", pal).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        val err = a.label("", 13f, pal.bad)
        val go = a.button("Verify", pal)
        sh.add(title("Enter code")); sh.add(msg, 8); sh.add(code, 12); sh.add(err, 8); sh.add(go, 8)
        Thread {
            val r = try {
                Net.call("POST", Cfg.CURIE_API + "/login/email-code/send", JSONObject().put("approvalId", approvalId))
            } catch (e: Exception) { null }
            a.runOnUiThread {
                msg.text = when {
                    r != null && r.ok -> "We sent a code to " + r.json.str("maskedEmail").ifEmpty { "your email" }
                    r != null && r.error.isNotEmpty() -> r.error
                    else -> "Could not send the code"
                }
            }
        }.start()
        go.setOnClickListener {
            go.isEnabled = false; err.text = ""
            Thread {
                val r = try {
                    Net.call("POST", Cfg.CURIE_API + "/login/email-code/verify",
                        JSONObject().put("approvalId", approvalId).put("code", code.text.toString().trim()))
                } catch (e: Exception) { null }
                a.runOnUiThread {
                    if (r != null && r.ok) finish(sh, r.json)
                    else { err.text = r?.error?.ifEmpty { "Could not verify the code" } ?: "Could not verify the code"; go.isEnabled = true }
                }
            }.start()
        }
    }

    private fun finish(sh: Sheet, j: JSONObject) {
        if (done) return
        done = true
        val token = j.str("accessToken")
        val curieId = j.str("curieId")
        Thread {
            var name = ""
            var user = ""
            try {
                val r = Net.call("GET", Cfg.CURIE_API + "/me", null, token)
                if (r.ok) { name = r.json.str("displayName"); user = r.json.str("username") }
            } catch (_: Exception) { }
            a.runOnUiThread { s.set(token, curieId, name, user); sh.dismiss(); changed() }
        }.start()
    }

    private fun account() {
        val sh = fresh()
        sh.add(title(s.name.ifEmpty { "Curie ID" }))
        sh.add(a.label((if (s.username.isNotEmpty()) "@${s.username} · " else "") + s.curieId, 14f, pal.mute), 4)
        val row = LinearLayout(a)
        row.addView(a.button("Manage", pal, false).apply {
            setOnClickListener { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Cfg.CURIE_SIGNUP))) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = a.dp(10) })
        row.addView(a.button("Sign out", pal).apply {
            setOnClickListener { s.clear(); sh.dismiss(); changed() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        sh.add(row, 18)
    }
}
