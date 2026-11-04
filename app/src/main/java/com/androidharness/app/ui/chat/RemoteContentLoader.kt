package com.androidharness.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.androidharness.app.tools.BROWSER_USER_AGENT
import com.androidharness.app.tools.PublicHttpAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Loads remote images and link previews for chat answers.
 *
 * Images are bounded in bytes and decoded size, cached in memory and on disk,
 * and only fetched from public http(s) hosts. Previews read the page head for
 * Open Graph tags. Nothing here executes page scripts, so the SVG/HTML
 * injection class of bug does not apply.
 */
internal data class RemoteImageBytes(val bytes: ByteArray, val mimeType: String)
internal data class RemoteImageFetch(
    val bytes: ByteArray? = null,
    val mimeType: String? = null,
    val url: String? = null,
    val httpStatus: Int? = null,
    val cached: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val error: String? = null,
)

internal object RemoteContentLoader {
    private const val MAX_IMAGE_BYTES = Long.MAX_VALUE
    private const val MAX_IMAGE_EDGE = 1600
    private const val MEMORY_CACHE_BYTES = 24 * 1024 * 1024
    private const val DISK_CACHE_BYTES = 32L * 1024 * 1024
    private const val MAX_PREVIEW_BYTES = 512 * 1024L
    private const val PREVIEW_TTL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_PREVIEWS = 64

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private data class CachedPreview(val value: LinkPreview, val at: Long)
    private val previews = ConcurrentHashMap<String, CachedPreview>()

    suspend fun loadImage(context: Context, rawUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        val url = com.androidharness.app.tools.httpImageUrl(rawUrl)?.toHttpUrlOrNull() ?: return@withContext null
        val key = url.toString()
        memory.get(key)?.let { return@withContext it }
        if (!isHttp(url) || !isPublic(url)) return@withContext null
        val file = imageCacheFile(context, key)
        val bytes = if (file.exists()) {
            runCatching { file.readBytes() }.getOrNull()
        } else null
        val downloaded = bytes ?: runCatching { downloadImage(url) }.getOrNull() ?: return@withContext null
        if (bytes == null) {
            runCatching { file.writeBytes(downloaded) }
            trimDiskCache(context)
        }
        val bitmap = decode(downloaded) ?: return@withContext null
        memory.put(key, bitmap)
        bitmap
    }

    /** Original bytes for saving or sharing, reusing the image disk cache when possible. */
    suspend fun downloadOriginal(context: Context, rawUrl: String): RemoteImageBytes? = withContext(Dispatchers.IO) {
        val fetched = fetchOriginal(context, rawUrl)
        fetched.bytes?.takeIf { fetched.error == null }?.let { RemoteImageBytes(it, fetched.mimeType ?: imageMimeType(it)) }
    }

    /** Status is retained on failures; a cache hit has no invented HTTP response code. */
    suspend fun fetchOriginal(context: Context, rawUrl: String, httpClient: OkHttpClient = noRedirectClient): RemoteImageFetch = withContext(Dispatchers.IO) {
        val url = com.androidharness.app.tools.httpImageUrl(rawUrl)?.toHttpUrlOrNull()
            ?: return@withContext RemoteImageFetch(error = "invalid_url")
        if (!isHttp(url) || !isPublic(url)) return@withContext RemoteImageFetch(url = url.toString(), error = "blocked")
        val file = imageCacheFile(context, url.toString())
        val cached = if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
        fun checked(result: RemoteImageFetch): RemoteImageFetch {
            val bytes = result.bytes ?: return result
            if (bytes.size > MAX_IMAGE_BYTES) return result.copy(bytes = null, error = "image_too_large")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return result.copy(bytes = null, error = "decode_failed")
            return result.copy(width = bounds.outWidth, height = bounds.outHeight)
        }
        if (cached != null) {
            val result = checked(RemoteImageFetch(cached, imageMimeType(cached), url.toString(), cached = true))
            if (result.error == null) return@withContext result
            file.delete()
        }
        var failure = RemoteImageFetch(url = url.toString(), error = "fetch_failed")
        for (agent in imageUserAgents) {
            val result = try {
                var current = url
                var outcome: RemoteImageFetch? = null
                for (hop in 0..4) {
                    if (!isHttp(current) || !isPublic(current)) { outcome = RemoteImageFetch(url = current.toString(), error = "blocked"); break }
                    val request = Request.Builder().url(current).header("Accept", "image/*").apply {
                        if (agent != null) header("User-Agent", agent)
                    }.build()
                    val next = httpClient.newCall(request).execute().use { response ->
                        val type = response.body?.contentType()?.let { "${it.type}/${it.subtype}" }
                        if (response.code in 301..308) response.header("Location")?.let(current::resolve)
                        else {
                            outcome = when {
                                !response.isSuccessful -> RemoteImageFetch(url = current.toString(), httpStatus = response.code, mimeType = type, error = "fetch_failed")
                                type != null && !type.startsWith("image/") -> RemoteImageFetch(url = current.toString(), httpStatus = response.code, mimeType = type, error = "decode_failed")
                                (response.body?.contentLength() ?: 0) > MAX_IMAGE_BYTES -> RemoteImageFetch(url = current.toString(), httpStatus = response.code, mimeType = type, error = "image_too_large")
                                else -> {
                                    val source = response.body?.source()
                                    source?.request(MAX_IMAGE_BYTES.toLong() + 1)
                                    if (source == null || source.buffer.size > MAX_IMAGE_BYTES) RemoteImageFetch(url = current.toString(), httpStatus = response.code, error = "image_too_large")
                                    else {
                                        val bytes = source.readByteArray()
                                        checked(RemoteImageFetch(bytes, type ?: imageMimeType(bytes), current.toString(), response.code))
                                    }
                                }
                            }
                            null
                        }
                    }
                    if (outcome != null || next == null) break
                    current = next
                }
                outcome ?: RemoteImageFetch(url = current.toString(), error = "fetch_failed")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { RemoteImageFetch(url = url.toString(), error = "fetch_failed") }
            if (result.error == null && result.bytes != null) {
                runCatching { file.writeBytes(result.bytes) }
                trimDiskCache(context)
                return@withContext result
            }
            failure = result
            if (result.error == "blocked" || result.httpStatus !in listOf(null, 403)) break
        }
        failure
    }

    private fun imageMimeType(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "image/webp"
        bytes.size >= 4 && String(bytes, 0, 4) == "GIF8" -> "image/gif"
        bytes.size >= 12 && String(bytes, 4, 4) == "ftyp" &&
            String(bytes, 8, 4) in setOf("avif", "avis") -> "image/avif"
        else -> "image/jpeg"
    }

    suspend fun loadPreview(rawUrl: String): LinkPreview? = withContext(Dispatchers.IO) {
        val url = rawUrl.toHttpUrlOrNull() ?: return@withContext null
        val key = url.toString()
        previews[key]?.takeIf { System.currentTimeMillis() - it.at < PREVIEW_TTL_MS }
            ?.let { return@withContext it.value }
        if (!isHttp(url) || !isPublic(url)) return@withContext null
        val preview = LinkPreviewParser.youtubeVideoId(key)?.let { id ->
            LinkPreview(
                url = key,
                title = null,
                description = null,
                imageUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                siteName = "YouTube",
                embedUrl = "https://www.youtube.com/embed/$id",
            )
        } ?: fetchPreview(url)
        if (previews.size >= MAX_PREVIEWS) previews.clear()
        previews[key] = CachedPreview(preview, System.currentTimeMillis())
        preview
    }

    /**
     * Some image hosts reject the full Chrome UA unless a matching Referer
     * arrives, while still serving a generic UA. Try the browser identity
     * first, then fall back before treating the image as broken.
     */
    private val imageUserAgents = listOf(BROWSER_USER_AGENT, "Mozilla/5.0", null)

    private fun downloadImage(url: HttpUrl, allowQueryStrip: Boolean = true): ByteArray? {
        for (userAgent in imageUserAgents) {
            downloadOnce(url, userAgent, allowQueryStrip)?.let { return it }
        }
        return null
    }

    private fun downloadOnce(url: HttpUrl, userAgent: String?, allowQueryStrip: Boolean): ByteArray? {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8")
        if (userAgent != null) builder.header("User-Agent", userAgent)
        evaluate(builder.build())?.use { response ->
            val type = response.body?.contentType()?.toString()?.lowercase().orEmpty()
            if (type.contains("svg")) {
                // Some CDNs answer a resized preview URL with an SVG placeholder
                // while the plain asset URL is a normal image. Drop the query
                // once before giving up.
                if (allowQueryStrip && url.query != null) return downloadImage(url.newBuilder().query(null).build(), false)
                return null
            }
            if (type.isNotEmpty() && !type.startsWith("image/")) return null
            val length = response.body?.contentLength() ?: -1L
            if (length > MAX_IMAGE_BYTES) return null
            val source = response.body?.source() ?: return null
            source.request(MAX_IMAGE_BYTES.toLong() + 1)
            val buffer = source.buffer
            if (buffer.size > MAX_IMAGE_BYTES) return null
            return buffer.readByteArray()
        }
        return null
    }

    private fun fetchPreview(url: HttpUrl): LinkPreview {
        val host = url.host.removePrefix("www.")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            .header("Accept-Language", java.util.Locale.getDefault().language)
            .build()
        return evaluate(request)?.use { response ->
            val finalUrl = response.request.url.toString()
            val type = response.body?.contentType()?.toString()?.lowercase().orEmpty()
            if (type.isNotEmpty() && !type.contains("html") && !type.contains("text")) {
                return@use LinkPreview(finalUrl, null, null, null, host)
            }
            val html = response.peekBody(MAX_PREVIEW_BYTES).string()
            LinkPreviewParser.parse(html, finalUrl, url.toString())
        } ?: LinkPreview(url.toString(), null, null, null, host)
    }

    /** Follows redirects by hand so every hop is checked against the public-address rule. */
    private fun evaluate(request: Request): Response? {
        var current = request
        repeat(4) {
            val url = current.url
            if (!isHttp(url) || !isPublic(url)) return null
            val response = noRedirectClient.newCall(current).execute()
            if (response.isSuccessful) return response
            if (response.code in 301..308) {
                val location = response.header("Location")
                response.close()
                val next = location?.let { url.resolve(it) } ?: return null
                current = current.newBuilder().url(next).build()
            } else {
                response.close()
                return null
            }
        }
        return null
    }

    private fun isHttp(url: HttpUrl): Boolean = url.scheme == "http" || url.scheme == "https"

    private fun isPublic(url: HttpUrl): Boolean {
        val addresses = runCatching { client.dns.lookup(url.host) }.getOrNull() ?: return false
        return addresses.isNotEmpty() && addresses.all { PublicHttpAddresses.isPublic(it) }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun imageDir(context: Context) = File(context.cacheDir, "remote-images").apply { mkdirs() }

    private fun imageCacheFile(context: Context, key: String): File =
        File(imageDir(context), sha256(key) + ".img")

    private fun trimDiskCache(context: Context) {
        val files = imageDir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= DISK_CACHE_BYTES) break
            total -= file.length()
            runCatching { file.delete() }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
