package com.zwolsman.ptcgl.mirror.rainier.cdn

import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(CdnClient::class.java)

/** Exception thrown when a CDN asset is absent (HTTP 403 or 404). */
class AssetNotFoundException(url: String) : Exception("Asset not found: $url")

/**
 * Downloads asset bundles and raw assets from the PTCGL CDN.
 *
 * [contentPath] is the base URL from GameSettings.json (hardcoded until dynamic lookup is added).
 * Example: "https://cdn.studio-prod.pokemon.com/rainier/content/poke_default_all/default/"
 *
 * Asset bundles are public — no auth header is required.
 * The CDN returns HTTP 403 for missing/pruned keys rather than 404.
 */
class CdnClient(
    private val http: OkHttpClient,
    private val contentPath: String,
) {
    /**
     * Download a per-bucket Unity manifest bundle.
     * URL: {contentPath}{bucket}/manifest_{locale}_{bucket}
     *
     * @throws AssetNotFoundException on 403/404
     */
    fun downloadManifestBundle(bucket: String, locale: String): ByteArray {
        val url = "${contentPath}${bucket}/manifest_${locale}_${bucket}"
        return download(url)
    }

    /**
     * Download an arbitrary raw asset by its CDN-relative path.
     * URL: {contentPath}{path}
     *
     * @throws AssetNotFoundException on 403/404
     */
    fun downloadRaw(path: String): ByteArray {
        val url = "$contentPath$path"
        return download(url)
    }

    private fun download(url: String): ByteArray {
        log.debug("CDN GET {}", url)
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 404) {
                throw AssetNotFoundException(url)
            }
            check(response.isSuccessful) { "CDN GET $url failed ${response.code}" }
            return response.body?.bytes() ?: error("Empty response body from $url")
        }
    }
}
