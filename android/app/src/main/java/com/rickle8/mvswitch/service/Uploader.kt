package com.rickle8.mvswitch.service

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Puts a game recording on GitHub for review: as a file on the app repository's "recordings"
 * pre-release (a pre-release is never "latest", so the app's own updates aren't affected).
 */
object Uploader {
    private const val REPO = "rickle8/multiview-audio-switcher"
    private const val TAG = "recordings"
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES).build()
    private val JSON = "application/json".toMediaType()

    private fun req(url: String, token: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    /** Uploads [file] as [name] (its path is flattened to the file name). Returns what to tell you. */
    fun put(token: String, name: String, file: File): String {
        val asset = name.substringAfterLast('/')
        val release = http.newCall(req("https://api.github.com/repos/$REPO/releases/tags/$TAG", token).build()).execute().use { r ->
            when (r.code) {
                200 -> JSONObject(r.body!!.string())
                404 -> null
                401, 403 -> return "Upload failed: the GitHub token can't write to $REPO."
                else -> return "Upload failed: GitHub said ${r.code}."
            }
        } ?: http.newCall(req("https://api.github.com/repos/$REPO/releases", token).post(JSONObject()
            .put("tag_name", TAG).put("name", "Game recordings").put("prerelease", true).put("target_commitish", "main")
            .put("body", "Recordings from the app for review. Not an app release.").toString().toRequestBody(JSON)).build())
            .execute().use { r ->
                if (r.code !in 200..201) return "Upload failed: couldn't make the recordings release (GitHub said ${r.code})."
                JSONObject(r.body!!.string())
            }
        // The same recording uploaded again replaces the old copy.
        val assets = release.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == asset) http.newCall(req(a.getString("url"), token).delete().build()).execute().close()
        }
        val id = release.getLong("id")
        val up = req("https://uploads.github.com/repos/$REPO/releases/$id/assets?name=$asset", token)
            .post(file.asRequestBody("application/zip".toMediaType())).build()
        return http.newCall(up).execute().use { r ->
            if (r.code in 200..201) "Uploaded the recording ${asset.removeSuffix(".zip")} (${file.length() / 1_000_000} MB). Tell Claude it's there."
            else "Upload failed: GitHub said ${r.code}."
        }
    }
}
