package com.sekusarisu.yanami.data.backup

import android.content.Context
import android.net.Uri
import com.sekusarisu.yanami.data.local.preferences.UserPreferencesRepository
import com.sekusarisu.yanami.data.remote.isAllowedCustomHeaderName
import com.sekusarisu.yanami.data.remote.normalizeServerBaseUrl
import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.CustomHeader
import com.sekusarisu.yanami.domain.model.ServerInstance
import com.sekusarisu.yanami.domain.model.TerminalSnippet
import com.sekusarisu.yanami.domain.repository.ServerRepository
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConfigBackupManager(
        private val context: Context,
        private val serverRepository: ServerRepository,
        private val userPreferencesRepository: UserPreferencesRepository
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun exportToUri(uri: Uri): ConfigExportSummary {
        val servers = serverRepository.getAll()
        val backup =
                ConfigBackup(
                        servers = servers.map { it.toBackupServer() },
                        // Snippets frequently contain access tokens or private commands.
                        snippets = emptyList()
                )

        val content = json.encodeToString(backup)
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.write(content)
        } ?: throw IllegalStateException("Unable to open output stream")

        return ConfigExportSummary(serverCount = servers.size, snippetCount = 0)
    }

    suspend fun importFromUri(uri: Uri): ConfigImportSummary {
        val content =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readUtf8WithLimit(MAX_BACKUP_BYTES)
                } ?: throw IllegalStateException("Unable to open input stream")

        val backup = json.decodeFromString<ConfigBackup>(content)
        require(backup.version in 1..ConfigBackup.CURRENT_VERSION) {
            "Unsupported backup version: ${backup.version}"
        }
        require(backup.servers.size <= MAX_BACKUP_SERVERS) {
            "Backup contains too many servers"
        }
        require(backup.snippets.size <= MAX_BACKUP_SNIPPETS) {
            "Backup contains too many terminal snippets"
        }
        require(backup.servers.all { it.customHeaders.size <= MAX_CUSTOM_HEADERS_PER_SERVER }) {
            "Backup contains too many custom headers"
        }

        val existingServers = serverRepository.getAll()
        val currentSnippets = userPreferencesRepository.terminalSnippets.first()

        var addedServerCount = 0
        var updatedServerCount = 0
        var skippedServerCount = 0
        var activeServerIdToRestore: Long? = null

        val existingServersByKey = existingServers.associateBy { it.mergeKey() }.toMutableMap()

        backup.servers.forEach { backupServer ->
            val normalizedServer = backupServer.toValidatedServerOrNull()
            if (normalizedServer == null) {
                skippedServerCount++
                return@forEach
            }

            val existing = existingServersByKey[normalizedServer.mergeKey()]
            if (existing != null) {
                val updated =
                        existing.copy(
                                name = normalizedServer.name,
                                baseUrl = normalizedServer.baseUrl,
                                username = normalizedServer.username,
                                password = normalizedServer.password.ifBlank { existing.password },
                                sessionToken = existing.sessionToken,
                                requires2fa = normalizedServer.requires2fa,
                                authType = normalizedServer.authType,
                                apiKey = normalizedServer.apiKey ?: existing.apiKey,
                                customHeaders =
                                        normalizedServer.customHeaders.ifEmpty {
                                            existing.customHeaders
                                        }
                        )
                serverRepository.update(updated, expectedAuthentication = existing)
                existingServersByKey[updated.mergeKey()] = updated
                updatedServerCount++
                if (backupServer.isActive) {
                    activeServerIdToRestore = updated.id
                }
            } else {
                val newId = serverRepository.add(normalizedServer.copy(isActive = false))
                val inserted = normalizedServer.copy(id = newId, isActive = false)
                existingServersByKey[inserted.mergeKey()] = inserted
                addedServerCount++
                if (backupServer.isActive) {
                    activeServerIdToRestore = newId
                }
            }
        }

        activeServerIdToRestore?.let { activeServerId ->
            serverRepository.setActive(activeServerId)
        }

        val mergedSnippets = currentSnippets.toMutableList()
        var addedSnippetCount = 0
        var updatedSnippetCount = 0
        var skippedSnippetCount = 0

        backup.snippets.forEach { rawSnippet ->
            val snippet = rawSnippet.normalizedOrNull()
            if (snippet == null) {
                skippedSnippetCount++
                return@forEach
            }

            val sameIdIndex = mergedSnippets.indexOfFirst { it.id == snippet.id }
            if (sameIdIndex >= 0) {
                mergedSnippets[sameIdIndex] = snippet
                updatedSnippetCount++
                return@forEach
            }

            val sameContentIndex =
                    mergedSnippets.indexOfFirst {
                        it.title.trim() == snippet.title &&
                                normalizeSnippetContent(it.content) == snippet.content
                    }
            if (sameContentIndex >= 0) {
                val existing = mergedSnippets[sameContentIndex]
                mergedSnippets[sameContentIndex] =
                        existing.copy(
                                title = snippet.title,
                                content = snippet.content,
                                appendEnter = snippet.appendEnter
                        )
                updatedSnippetCount++
                return@forEach
            }

            mergedSnippets += snippet
            addedSnippetCount++
        }

        userPreferencesRepository.setTerminalSnippets(
                mergedSnippets.sortedBy { it.title.lowercase() }
        )

        return ConfigImportSummary(
                addedServerCount = addedServerCount,
                updatedServerCount = updatedServerCount,
                skippedServerCount = skippedServerCount,
                addedSnippetCount = addedSnippetCount,
                updatedSnippetCount = updatedSnippetCount,
                skippedSnippetCount = skippedSnippetCount,
                restoredActiveServer = activeServerIdToRestore != null
        )
    }

    private fun ServerInstance.toBackupServer(): BackupServer {
        return BackupServer(
                name = name,
                baseUrl = baseUrl.trim().trimEnd('/'),
                username = username.trim(),
                password = "",
                // Session tokens are short-lived bearer credentials and must not leave the app.
                sessionToken = null,
                requires2fa = requires2fa,
                isActive = isActive,
                createdAt = createdAt,
                authType = authType,
                apiKey = null,
                customHeaders = emptyList()
        )
    }

    private fun BackupServer.toValidatedServerOrNull(): ServerInstance? {
        val normalizedName = name.trim()
        val normalizedBaseUrl =
                runCatching { normalizeServerBaseUrl(baseUrl) }.getOrNull() ?: return null
        val normalizedUsername = username.trim()
        val normalizedApiKey = apiKey?.trim()?.ifBlank { null }
        val normalizedCustomHeaders = customHeaders.normalizedCustomHeaders()

        if (normalizedName.isBlank() || normalizedBaseUrl.isBlank()) {
            return null
        }

        return when (authType) {
            AuthType.PASSWORD -> {
                if (normalizedUsername.isBlank()) {
                    null
                } else {
                    ServerInstance(
                            name = normalizedName,
                            baseUrl = normalizedBaseUrl,
                            username = normalizedUsername,
                            password = password,
                            sessionToken = null,
                            requires2fa = requires2fa,
                            isActive = false,
                            createdAt = createdAt,
                            authType = authType,
                            apiKey = null,
                            customHeaders = normalizedCustomHeaders
                    )
                }
            }
            AuthType.API_KEY -> {
                ServerInstance(
                            name = normalizedName,
                            baseUrl = normalizedBaseUrl,
                            username = normalizedUsername,
                            password = "",
                            sessionToken = null,
                            requires2fa = false,
                            isActive = false,
                            createdAt = createdAt,
                            authType = authType,
                            apiKey = normalizedApiKey,
                            customHeaders = normalizedCustomHeaders
                    )
            }
            AuthType.GUEST -> {
                ServerInstance(
                        name = normalizedName,
                        baseUrl = normalizedBaseUrl,
                        username = "",
                        password = "",
                        sessionToken = null,
                        requires2fa = false,
                        isActive = false,
                        createdAt = createdAt,
                        authType = authType,
                        apiKey = null,
                        customHeaders = normalizedCustomHeaders
                )
            }
        }
    }

    private fun ServerInstance.mergeKey(): String {
        return buildString {
            append(baseUrl.trim().trimEnd('/'))
            append('|')
            append(authType.name)
            append('|')
            append(username.trim())
        }
    }

    private fun TerminalSnippet.normalizedOrNull(): TerminalSnippet? {
        val normalizedTitle = title.trim()
        val normalizedContent = normalizeSnippetContent(content)
        if (id.isBlank() || normalizedTitle.isBlank() || normalizedContent.isBlank()) {
            return null
        }
        return copy(title = normalizedTitle, content = normalizedContent)
    }

    private fun normalizeSnippetContent(content: String): String {
        return content.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun List<CustomHeader>.normalizedCustomHeaders(): List<CustomHeader> {
        return map { CustomHeader(it.name.trim(), it.value.trim()) }
                .filter {
                    it.name.isNotBlank() &&
                            it.value.isNotBlank() &&
                            isAllowedCustomHeaderName(it.name) &&
                            it.name.length <= MAX_HEADER_NAME_LENGTH &&
                            it.value.length <= MAX_HEADER_VALUE_LENGTH &&
                            it.value.none { char -> char == '\r' || char == '\n' || char == '\u0000' }
                }
                .distinctBy { it.name.lowercase() }
    }

    private fun InputStream.readUtf8WithLimit(maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, READ_BUFFER_SIZE))
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val bytesRead = read(buffer)
            if (bytesRead == -1) break
            totalBytes += bytesRead
            require(totalBytes <= maxBytes) { "Backup file is too large" }
            output.write(buffer, 0, bytesRead)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
        const val MAX_BACKUP_SERVERS = 500
        const val MAX_BACKUP_SNIPPETS = 1_000
        const val MAX_CUSTOM_HEADERS_PER_SERVER = 64
        const val MAX_HEADER_NAME_LENGTH = 128
        const val MAX_HEADER_VALUE_LENGTH = 8 * 1024
        const val READ_BUFFER_SIZE = 8 * 1024
    }
}
