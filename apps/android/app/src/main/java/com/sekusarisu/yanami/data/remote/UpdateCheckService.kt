package com.sekusarisu.yanami.data.remote

import android.util.Log
import com.sekusarisu.yanami.data.remote.dto.UpdateInfoDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
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
            val versionName = release.tagName.removePrefix("v")
            val versionCode = semanticVersionCode(versionName) ?: return null
            val downloadUrl =
                    release.assets
                            .firstOrNull { it.name.endsWith("-universal.apk") }
                            ?.browserDownloadUrl
                            ?.takeIf(::isTrustedReleaseUrl)
                            ?: release.htmlUrl.takeIf(::isTrustedReleaseUrl)
                            ?: return null
            UpdateInfoDto(
                    versionCode = versionCode,
                    versionName = versionName,
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

    private fun semanticVersionCode(version: String): Int? {
        val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(version) ?: return null
        val (major, minor, patch) = match.destructured.toList().map { it.toIntOrNull() ?: return null }
        if (minor > 99 || patch > 99 || major > 2_000) return null
        return major * 1_000_000 + minor * 10_000 + patch * 100
    }

    private fun isTrustedReleaseUrl(value: String): Boolean {
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && uri.host.equals("github.com", true)
    }
}

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
