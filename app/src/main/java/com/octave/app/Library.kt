package com.octave.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Song(
    val uri: String,
    val title: String,
    val artist: String,
    val added: Long = 0L,
    val fav: Boolean = false,
    val art: String? = null
)

data class Playlist(val name: String, val uris: List<String>)

/** Stores songs and playlists on the device. */
class Library(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("octave", Context.MODE_PRIVATE)

    fun load(): List<Song> {
        val arr = JSONArray(prefs.getString("songs", "[]"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Song(
                o.getString("uri"), o.getString("title"), o.getString("artist"),
                o.optLong("added", 0L), o.optBoolean("fav", false),
                o.optString("art", "").takeIf { it.isNotEmpty() }
            )
        }
    }

    fun save(songs: List<Song>) {
        val arr = JSONArray()
        songs.forEach {
            arr.put(
                JSONObject().put("uri", it.uri).put("title", it.title).put("artist", it.artist)
                    .put("added", it.added).put("fav", it.fav).put("art", it.art ?: "")
            )
        }
        prefs.edit().putString("songs", arr.toString()).apply()
    }

    fun loadPlaylists(): List<Playlist> {
        val arr = JSONArray(prefs.getString("playlists", "[]"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val u = o.getJSONArray("uris")
            Playlist(o.getString("name"), List(u.length()) { u.getString(it) })
        }
    }

    fun savePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p -> arr.put(JSONObject().put("name", p.name).put("uris", JSONArray(p.uris))) }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    /** Per song: [number of plays, listened milliseconds]. */
    fun loadStats(): MutableMap<String, LongArray> {
        val o = JSONObject(prefs.getString("stats", "{}"))
        val m = mutableMapOf<String, LongArray>()
        o.keys().forEach { k ->
            val a = o.getJSONArray(k)
            m[k] = longArrayOf(a.getLong(0), a.getLong(1))
        }
        return m
    }

    fun saveStats(m: Map<String, LongArray>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONArray().put(v[0]).put(v[1])) }
        prefs.edit().putString("stats", o.toString()).apply()
    }

    /** Reads title, artist and cover from the file tags; falls back to the file name. */
    fun readSong(uri: Uri): Song {
        val r = MediaMetadataRetriever()
        var title: String? = null
        var artist: String? = null
        var art: String? = null
        try {
            r.setDataSource(ctx, uri)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            r.embeddedPicture?.let { bytes ->
                val dir = File(ctx.filesDir, "art").apply { mkdirs() }
                val f = File(dir, "${uri.toString().hashCode()}.img")
                f.writeBytes(bytes)
                art = f.absolutePath
            }
        } catch (e: Exception) {
            // keep fallbacks
        } finally {
            r.release()
        }
        return Song(
            uri.toString(),
            title?.takeIf { it.isNotBlank() } ?: fileName(uri),
            artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
            System.currentTimeMillis(), false, art
        )
    }

    private fun fileName(uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).substringBeforeLast('.') else null
        } ?: "Track"
}
