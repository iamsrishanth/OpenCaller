package io.github.iamsrishanth.opencaller.updates

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal data class HttpResult<T>(
    val statusCode: Int,
    val body: T? = null,
)

internal data class GithubReleasePayload(
    val tagName: String?,
    val releaseUrl: String?,
    val updateMetadataUrl: String?,
)

internal data class GithubUpdateMetadata(
    val applicationId: String?,
    val versionCode: Long?,
    val versionName: String?,
)

internal data class PublishedVersion(
    val versionCode: Long,
    val versionName: String?,
)

internal data class FdroidPackagePayload(
    val packageName: String?,
    val suggestedVersionCode: Long?,
    val packages: List<PublishedVersion>,
)

internal interface UpdateSourceClient {
    suspend fun fetchLatestGithubRelease(): HttpResult<GithubReleasePayload>
    suspend fun fetchGithubUpdateMetadata(url: String): HttpResult<GithubUpdateMetadata>
    suspend fun fetchFdroidPackage(packageName: String): HttpResult<FdroidPackagePayload>
}

internal class HttpUpdateSourceClient : UpdateSourceClient {
    override suspend fun fetchLatestGithubRelease(): HttpResult<GithubReleasePayload> =
        requestJson(UpdateApiUrls.GITHUB_LATEST_RELEASE, "application/vnd.github+json", ::parseGithubRelease)

    override suspend fun fetchGithubUpdateMetadata(url: String): HttpResult<GithubUpdateMetadata> =
        requestJson(url, "application/octet-stream", ::parseGithubMetadata)

    override suspend fun fetchFdroidPackage(packageName: String): HttpResult<FdroidPackagePayload> {
        val encodedName = Uri.encode(packageName)
        return requestJson(
            "${UpdateApiUrls.FDROID_PACKAGE_API}/$encodedName",
            "application/json",
            ::parseFdroidPackage,
        )
    }

    private suspend fun <T> requestJson(
        url: String,
        accept: String,
        parser: (JSONObject) -> T,
    ): HttpResult<T> = withContext(Dispatchers.IO) {
        val raw = request(url, accept)
        val parsedBody = raw.body?.let { body ->
            runCatching { parser(JSONObject(body)) }.getOrNull()
        }
        HttpResult(statusCode = raw.statusCode, body = parsedBody)
    }

    private fun parseGithubRelease(json: JSONObject): GithubReleasePayload {
        val releaseUrl = json.stringField("html_url")
        val tagName = json.stringField("tag_name") ?: json.stringField("name")
        val assets = json.optJSONArray("assets") ?: JSONArray()
        val metadataAssets = (0 until assets.length()).mapNotNull { index ->
            val asset = assets.optJSONObject(index) ?: return@mapNotNull null
            if (asset.stringField("name") == UpdateApiUrls.UPDATE_METADATA_ASSET) asset else null
        }
        val metadataUrl = metadataAssets.singleOrNull()
            ?.stringField("browser_download_url")
            ?.takeIf(::isGithubReleaseAssetUrl)
        return GithubReleasePayload(
            tagName = tagName,
            releaseUrl = releaseUrl,
            updateMetadataUrl = metadataUrl,
        )
    }

    private fun parseGithubMetadata(json: JSONObject): GithubUpdateMetadata = GithubUpdateMetadata(
        applicationId = json.stringField("applicationId"),
        versionCode = json.optLong("versionCode", INVALID_VERSION_CODE).takeIf { it > 0L },
        versionName = json.stringField("versionName"),
    )

    private fun parseFdroidPackage(json: JSONObject): FdroidPackagePayload {
        val packages = json.optJSONArray("packages") ?: JSONArray()
        val publishedVersions = (0 until packages.length()).mapNotNull { index ->
            val item = packages.optJSONObject(index) ?: return@mapNotNull null
            val code = item.optLong("versionCode", INVALID_VERSION_CODE)
                .takeIf { it > 0L } ?: return@mapNotNull null
            PublishedVersion(code, item.stringField("versionName"))
        }
        return FdroidPackagePayload(
            packageName = json.stringField("packageName"),
            suggestedVersionCode = json.optLong("suggestedVersionCode", INVALID_VERSION_CODE)
                .takeIf { it > 0L },
            packages = publishedVersions,
        )
    }

    private fun JSONObject.stringField(name: String): String? =
        optString(name).takeIf { it.isNotBlank() && it != "null" }

    private fun isGithubReleaseAssetUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == UpdateApiUrls.GITHUB_HOST &&
            uri.path.orEmpty().startsWith(UpdateApiUrls.GITHUB_RELEASE_DOWNLOAD_PATH)
    }

    private fun request(url: String, accept: String): RawHttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = REQUEST_TIMEOUT_MILLIS
        connection.readTimeout = REQUEST_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", USER_AGENT)

        return try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) return RawHttpResult(statusCode, null)
            val body = connection.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(RESPONSE_MAX_CHARS)
                    var total = 0
                    while (true) {
                        val remaining = RESPONSE_MAX_CHARS - total
                        if (remaining == 0) {
                            if (reader.read() != -1) throw IOException("Update response was too large")
                            break
                        }
                        val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) break
                        total += count
                        result.append(buffer, 0, count)
                    }
                    result.toString()
                }
            }
            RawHttpResult(statusCode, body)
        } finally {
            connection.disconnect()
        }
    }

    private data class RawHttpResult(
        val statusCode: Int,
        val body: String?,
    )

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 7_000
        const val RESPONSE_MAX_CHARS = 512 * 1024
        const val USER_AGENT = "OpenCaller-Android"
        const val INVALID_VERSION_CODE = -1L
    }
}

internal object UpdateApiUrls {
    const val GITHUB_LATEST_RELEASE = "https://api.github.com/repos/iamsrishanth/OpenCaller/releases/latest"
    const val GITHUB_RELEASES_PAGE = "https://github.com/iamsrishanth/OpenCaller/releases"
    const val FDROID_PACKAGE_API = "https://f-droid.org/api/v1/packages"
    const val FDROID_APP_PAGE = "https://f-droid.org/packages/"
    const val UPDATE_METADATA_ASSET = "opencaller-update.json"
    const val GITHUB_HOST = "github.com"
    const val GITHUB_RELEASE_DOWNLOAD_PATH = "/iamsrishanth/OpenCaller/releases/download/"
}
