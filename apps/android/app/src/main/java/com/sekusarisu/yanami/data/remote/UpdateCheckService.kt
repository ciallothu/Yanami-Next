package com.sekusarisu.yanami.data.remote

import android.util.Log
import com.sekusarisu.yanami.data.remote.dto.UpdateInfoDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class UpdateCheckService {

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    companion object {
        private const val TAG = "UpdateCheckService"
        private const val UPDATE_URL =
                "https://api.github.com/repos/ciallothu/Yanami-Next/releases/latest"
        private const val RELEASE_METADATA_URL =
                "https://api.github.com/repos/ciallothu/Yanami-Next/contents/docs/update.json"
        private const val MAX_METADATA_BASE64_LENGTH = 64 * 1024
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val _latestUpdate = MutableStateFlow<UpdateInfoDto?>(null)
    val latestUpdate: StateFlow<UpdateInfoDto?> = _latestUpdate.asStateFlow()

    suspend fun checkForUpdate(): UpdateInfoDto? {
        return try {
            val response = httpClient.get(UPDATE_URL) {
                header(HttpHeaders.UserAgent, "Yanami-Next-Update-Checker")
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            if (!response.status.isSuccess()) return null
            val body = response.bodyAsText()
            val release = json.decodeFromString<GitHubReleaseDto>(body)
            if (release.draft || release.prerelease) return null
            val releaseVersion = UpdateVersionPolicy.parseReleaseTag(release.tagName) ?: return null
            val versionCode = resolveVersionCode(releaseVersion)
            val universalAsset =
                    release.assets.firstOrNull {
                        it.name == releaseVersion.universalAssetName
                    }
            val downloadUrl =
                    universalAsset
                            ?.browserDownloadUrl
                            ?.takeIf {
                                isTrustedReleaseAssetUrl(
                                        value = it,
                                        tagName = release.tagName,
                                        assetName = universalAsset.name
                                )
                            }
                            ?: release.htmlUrl.takeIf {
                                isTrustedReleaseHtmlUrl(it, release.tagName)
                            }
                            ?: return null
            UpdateInfoDto(
                    versionCode = versionCode,
                    versionName = releaseVersion.versionName,
                    downloadUrl = downloadUrl,
                    changelog = release.body.ifBlank { release.name }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for update", e)
            null
        }
    }

    suspend fun checkForUpdateSilent(currentVersionCode: Int) {
        val info = checkForUpdate()
        if (info != null && info.versionCode > currentVersionCode) {
            _latestUpdate.value = info
        }
    }

    private suspend fun resolveVersionCode(
            releaseVersion: UpdateVersionPolicy.ReleaseVersion
    ): Int {
        val metadata =
                runCatching {
                            val response =
                                    httpClient.get(RELEASE_METADATA_URL) {
                                        header(HttpHeaders.UserAgent, "Yanami-Next-Update-Checker")
                                        header(HttpHeaders.Accept, "application/vnd.github+json")
                                        header(HttpHeaders.CacheControl, "no-cache")
                                        header("X-GitHub-Api-Version", "2022-11-28")
                                        parameter("ref", releaseVersion.tagName)
                                    }
                            if (!response.status.isSuccess()) return@runCatching null
                            val contents =
                                    json.decodeFromString<GitHubContentsDto>(response.bodyAsText())
                            if (contents.type != "file" ||
                                            contents.encoding != "base64" ||
                                            contents.content.length > MAX_METADATA_BASE64_LENGTH
                            ) {
                                return@runCatching null
                            }
                            val metadataJson =
                                    java.util.Base64.getMimeDecoder()
                                            .decode(contents.content)
                                            .toString(Charsets.UTF_8)
                            json.decodeFromString<ReleaseUpdateMetadataDto>(metadataJson)
                        }
                        .onFailure { error ->
                            Log.w(TAG, "Failed to read revision metadata for the release tag", error)
                        }
                        .getOrNull()

        return UpdateVersionPolicy.selectVersionCode(
                releaseVersion = releaseVersion,
                metadataVersionName = metadata?.versionName,
                metadataVersionCode = metadata?.versionCode
        )
    }

    private fun isTrustedReleaseAssetUrl(
            value: String,
            tagName: String,
            assetName: String
    ): Boolean {
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        return isTrustedGitHubUri(uri) &&
                uri.path == "/ciallothu/Yanami-Next/releases/download/$tagName/$assetName"
    }

    private fun isTrustedReleaseHtmlUrl(value: String, tagName: String): Boolean {
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        return isTrustedGitHubUri(uri) &&
                uri.path == "/ciallothu/Yanami-Next/releases/tag/$tagName"
    }

    private fun isTrustedGitHubUri(uri: java.net.URI): Boolean {
        return uri.scheme.equals("https", true) &&
                uri.host.equals("github.com", true) &&
                uri.userInfo == null &&
                uri.port == -1 &&
                uri.query == null &&
                uri.fragment == null
    }
}

@Serializable
private data class ReleaseUpdateMetadataDto(
        val versionCode: Long? = null,
        val versionName: String? = null
)

@Serializable
private data class GitHubContentsDto(
        val type: String = "",
        val encoding: String = "",
        val content: String = ""
)

@Serializable
private data class GitHubReleaseDto(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubReleaseAssetDto> = emptyList()
)

@Serializable
private data class GitHubReleaseAssetDto(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = ""
)
