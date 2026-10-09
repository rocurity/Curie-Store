package com.curie.store

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Download, verify and hand an APK to Android's PackageInstaller. Call off the main thread. */
object Installer {
    fun run(ctx: Context, a: StoreApp, status: (String) -> Unit) {
        if (!a.apkUrl.startsWith("https://github.com/")) error("Untrusted download link")
        val f = File(ctx.cacheDir, "download.apk")
        status("Downloading")
        val sha = download(a.apkUrl, f)
        if (!sha.equals(a.sha256, true)) { f.delete(); error("Checksum does not match the store index") }
        status("Verifying")
        verify(ctx, f, a)
        commit(ctx, f)
    }

    private fun download(url: String, dest: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.inputStream.use { i -> dest.outputStream().use { o ->
            val buf = ByteArray(65536)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n); o.write(buf, 0, n) }
        } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verify(ctx: Context, f: File, a: StoreApp) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = ctx.packageManager.getPackageArchiveInfo(f.path, flags) ?: error("Not a valid APK")
        if (info.packageName != a.id) error("Package name does not match the store index")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (code != a.versionCode.toLong()) error("Version does not match the store index")
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
                   else @Suppress("DEPRECATION") info.signatures
        if (sigs == null || sigs.isEmpty()) error("APK is not signed")
        for (s in sigs) {
            val fp = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
            if (!fp.equals(a.certSha256, true)) error("Signing certificate does not match the store index")
        }
    }

    private fun commit(ctx: Context, f: File) {
        val pi = ctx.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { s ->
            f.inputStream().use { i -> s.openWrite("app.apk", 0, f.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pend = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            s.commit(pend.intentSender)
        }
    }
}
