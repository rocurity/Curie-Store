package com.curie.store

import org.json.JSONObject
import java.net.URLEncoder

/** Thrown when the Curie ID session has expired or was rejected. */
class AuthLost : Exception("Signed out. Sign in again.")

data class Stat(val rating: Double, val ratings: Int, val installs: Int)
data class Review(val name: String, val stars: Int, val text: String)
data class Mine(val stars: Int, val review: String)
data class AppInfo(val stat: Stat, val mine: Mine?, val wishlisted: Boolean?)

/** Client for the Curie Store backend (ratings, reviews, wishlist, install counts). Call off the main thread. */
class StoreApi(private val s: Session) {
    private fun enc(id: String) = URLEncoder.encode(id, "UTF-8")

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val r = Net.call(method, Cfg.STORE_API + path, body, s.token)
        if (r.code == 401 && s.signedIn) throw AuthLost()
        if (!r.ok) throw Exception(r.error.ifEmpty { "Something went wrong" })
        return r.json
    }

    private fun stat(o: JSONObject) = Stat(o.optDouble("rating", 0.0), o.optInt("ratings", 0), o.optInt("installs", 0))

    fun stats(): Map<String, Stat> {
        val apps = call("GET", "/stats").optJSONObject("apps") ?: return emptyMap()
        val out = HashMap<String, Stat>()
        for (k in apps.keys()) out[k] = stat(apps.getJSONObject(k))
        return out
    }

    fun app(id: String): AppInfo {
        val j = call("GET", "/apps/" + enc(id))
        val mine = j.optJSONObject("mine")?.let { Mine(it.optInt("stars"), it.str("review")) }
        return AppInfo(stat(j), mine, if (j.has("wishlisted")) j.optBoolean("wishlisted") else null)
    }

    fun reviews(id: String): List<Review> {
        val arr = call("GET", "/apps/" + enc(id) + "/reviews?limit=5").optJSONArray("reviews") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Review(o.str("name"), o.optInt("stars"), o.str("review"))
        }
    }

    fun rate(id: String, stars: Int, review: String) {
        call("PUT", "/apps/" + enc(id) + "/rating", JSONObject().put("stars", stars).put("review", review))
    }

    fun unrate(id: String) { call("DELETE", "/apps/" + enc(id) + "/rating") }

    fun wishlist(): Set<String> {
        val arr = call("GET", "/me/wishlist").optJSONArray("apps") ?: return emptySet()
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun setWish(id: String, on: Boolean) { call(if (on) "PUT" else "DELETE", "/me/wishlist/" + enc(id)) }

    fun countInstall(id: String) { call("POST", "/apps/" + enc(id) + "/install") }
}
