package com.iptvapp.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Poster art for launcher cards. Public artwork hosts are passed through (LauncherMetadata);
 * provider artwork, whose URL can carry the account, is fetched here and handed to the launcher
 * as content://<app>.launcherart/<sha256>, so the provider URL never leaves the app.
 * Publishing only reads the cache (it runs under the catalog lock); a poster not cached yet is
 * noted and fetched by [fetchPending] after the lock is released, for the next publish.
 */
object LauncherArtwork {
    private const val DIR = "launcher-art"
    private const val MAX_BYTES = 3L * 1024 * 1024
    private const val KEEP_MS = 7L * 24 * 3600 * 1000
    private val NAME = Regex("[0-9a-f]{64}")
    private const val MAX_PENDING = 50
    private const val FETCH_BUDGET_MS = 30_000L
    private val pending = LinkedHashSet<String>()

    fun authority(context: Context) = "${context.packageName}.launcherart"

    fun dir(context: Context) = File(context.cacheDir, DIR)

    fun fallback(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${context.applicationInfo.icon}")

    /**
     * The poster for [url]: public as-is, provider art already cached, else the app icon (and the
     * art is queued for [fetchPending]). Never touches the network.
     */
    fun poster(context: Context, url: String?): Uri {
        LauncherMetadata.artwork(url)?.let { return Uri.parse(it) }
        if (url.isNullOrBlank()) return fallback(context)
        val scheme = Uri.parse(url).scheme
        if (scheme != "http" && scheme != "https") return fallback(context)
        val file = File(dir(context), sha256(url))
        if (file.length() > 0 && System.currentTimeMillis() - file.lastModified() < KEEP_MS) {
            return Uri.Builder().scheme("content").authority(authority(context)).appendPath(file.name).build()
        }
        synchronized(pending) { if (pending.size < MAX_PENDING) pending.add(url) }
        return fallback(context)
    }

    /** Fetches queued posters, outside any catalog lock, within a total time budget. True when any arrived. */
    fun fetchPending(context: Context): Boolean {
        val urls = synchronized(pending) { pending.toList().also { pending.clear() } }
        val deadline = System.currentTimeMillis() + FETCH_BUDGET_MS
        var got = false
        for (url in urls) {
            if (System.currentTimeMillis() > deadline) break
            if (runCatching { download(context, url) }.getOrNull() != null) got = true
        }
        return got
    }

    private fun download(context: Context, url: String): File? {
        val dir = dir(context).apply { mkdirs() }
        val file = File(dir, sha256(url))
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000; instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) return null
            if (conn.contentType?.startsWith("image/") != true) return null
            if (conn.contentLengthLong > MAX_BYTES) return null
            val tmp = File(dir, file.name + ".tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) { tmp.delete(); return null }
                        out.write(buf, 0, n)
                    }
                }
            }
            return if (tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) file else null
        } finally {
            conn.disconnect()
        }
    }

    /** Drops posters not refreshed for a week (cards that left the rows). */
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir(context).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    fun isName(name: String?) = name != null && NAME.matches(name)

    private fun sha256(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Read-only access for the system launcher to cached posters. Exported because the launcher
 * package varies by device; it serves only files named by a 64-hex hash inside the poster cache,
 * so no other file can be reached.
 */
class LauncherArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        val ctx = context ?: return null
        val name = uri.lastPathSegment
        if (uri.pathSegments.size != 1 || !LauncherArtwork.isName(name)) throw java.io.FileNotFoundException()
        val file = File(LauncherArtwork.dir(ctx), name!!)
        if (!file.isFile) throw java.io.FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/*"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
