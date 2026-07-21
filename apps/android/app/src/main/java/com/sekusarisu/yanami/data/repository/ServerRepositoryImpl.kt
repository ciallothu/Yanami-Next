package com.sekusarisu.yanami.data.repository

import android.util.Log
import com.sekusarisu.yanami.data.local.crypto.CryptoManager
import com.sekusarisu.yanami.data.local.dao.ServerInstanceDao
import com.sekusarisu.yanami.data.local.entity.ServerInstanceEntity
import com.sekusarisu.yanami.data.remote.KomariAuthService
import com.sekusarisu.yanami.data.remote.KomariRpcService
import com.sekusarisu.yanami.data.remote.LoginResult
import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.CustomHeader
import com.sekusarisu.yanami.domain.model.ServerInstance
import com.sekusarisu.yanami.domain.repository.Requires2FAException
import com.sekusarisu.yanami.domain.repository.ServerRepository
import com.sekusarisu.yanami.domain.repository.SessionExpiredException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * 服务端实例仓库实现
 *
 * 负责：
 * - Entity ↔ Domain Model 转换
 * - 用户名/密码加解密
 * - session_token 持久化与恢复
 * - 通过 KomariAuthService 登录获取 session_token
 * - 通过 KomariRpcService 验证连接
 */
class ServerRepositoryImpl(
        private val dao: ServerInstanceDao,
        private val cryptoManager: CryptoManager,
        private val authService: KomariAuthService,
        private val rpcService: KomariRpcService
) : ServerRepository {

    companion object {
        private const val TAG = "ServerRepo"

        /**
         * Room serializes individual statements, but several repository operations are
         * read/decide/write sequences. Keep those sequences process-wide so a stale profile
         * snapshot cannot overwrite a newer session token.
         */
        private val profileMutationMutex = Mutex()

        /**
         * Avoid duplicate validation/login attempts for the same process. Mutations may still
         * happen while a network request is in flight; every request therefore performs a
         * security-identity check both before and after the request.
         */
        private val profileAuthenticationMutex = Mutex()
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getAll(): List<ServerInstance> {
        return dao.getAll().map { it.toDomain() }
    }

    override fun getAllFlow(): Flow<List<ServerInstance>> {
        return dao.getAllFlow().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getActive(): ServerInstance? {
        return dao.getActive()?.toDomain()
    }

    override fun getActiveFlow(): Flow<ServerInstance?> {
        return dao.getActiveFlow().map { it?.toDomain() }
    }

    override suspend fun getById(id: Long): ServerInstance? {
        return dao.getById(id)?.toDomain()
    }

    override suspend fun add(instance: ServerInstance): Long {
        return profileMutationMutex.withLock {
            // A session token may only enter storage through a server-bound login result.
            // Force an auto-generated ID so add() cannot replace an existing profile row.
            dao.insert(instance.copy(id = 0L, sessionToken = null).toEntity())
        }
    }

    override suspend fun update(
            instance: ServerInstance,
            expectedAuthentication: ServerInstance
    ) {
        profileMutationMutex.withLock {
            val stored = dao.getById(instance.id)?.toDomain() ?: return@withLock
            requireUnchangedProfile(
                    canApplyOrdinaryServerUpdate(
                            stored = stored,
                            requested = instance,
                            expectedAuthentication = expectedAuthentication
                    )
            )
            dao.update(mergeOrdinaryServerUpdate(stored, instance).toEntity())
        }
    }

    override suspend fun remove(id: Long) {
        profileMutationMutex.withLock {
            val entity = dao.getById(id) ?: return@withLock
            dao.delete(entity)
        }
    }

    override suspend fun setActive(id: Long) {
        profileMutationMutex.withLock {
            dao.deactivateAll()
            dao.activateById(id)
        }
    }

    override suspend fun testConnection(
            baseUrl: String,
            username: String,
            password: String,
            twoFaCode: String?,
            customHeaders: List<CustomHeader>
    ): String {
        // 1. 登录获取 session_token
        val loginResult = authService.login(baseUrl, username, password, twoFaCode, customHeaders)
        val sessionToken =
                when (loginResult) {
                    is LoginResult.Success -> loginResult.sessionToken
                    is LoginResult.Requires2FA -> throw Requires2FAException(loginResult.message)
                    is LoginResult.Error -> throw Exception(loginResult.message)
                }

        // 2. 用 session_token 调用 RPC 获取版本号验证
        val version =
                rpcService.getVersion(
                        baseUrl,
                        sessionToken,
                        AuthType.PASSWORD,
                        customHeaders = customHeaders
                )
        Log.d(TAG, "Test connection ok, version=$version")
        return version
    }

    override suspend fun testConnectionWithApiKey(
            baseUrl: String,
            apiKey: String,
            customHeaders: List<CustomHeader>
    ): String {
        // 使用 Bearer 认证调用 getVersion 验证 API Key
        val version =
                rpcService.getVersion(
                        baseUrl,
                        apiKey,
                        AuthType.API_KEY,
                        customHeaders = customHeaders
                )
        Log.d(TAG, "Test connection with API Key ok, version=$version")
        return version
    }

    override suspend fun testConnectionAsGuest(
            baseUrl: String,
            customHeaders: List<CustomHeader>
    ): String {
        val version =
                rpcService.getVersion(
                        baseUrl,
                        "",
                        AuthType.GUEST,
                        customHeaders = customHeaders
                )
        Log.d(TAG, "Test connection as guest ok, version=$version")
        return version
    }

    override suspend fun login(instance: ServerInstance, twoFaCode: String?): Boolean =
            profileAuthenticationMutex.withLock {
                val storedAtStart =
                        profileMutationMutex.withLock {
                            val stored = requireCurrentProfile(instance.id)
                            val matches =
                                    if (instance.authType == AuthType.PASSWORD) {
                                        // A user-triggered re-login may intentionally replace the
                                        // username/password, but never the target origin or headers.
                                        sameServerAuthenticationOrigin(stored, instance)
                                    } else {
                                        sameServerAuthenticationConfiguration(stored, instance)
                                    }
                            requireUnchangedProfile(matches)
                            stored
                        }

                when (instance.authType) {
                    AuthType.GUEST -> Unit
                    AuthType.API_KEY -> {
                        serverBoundStoredCredential(storedAtStart)
                                ?: throw SessionExpiredException("API Key is missing")
                    }
                    AuthType.PASSWORD ->
                            loginWithExplicitPassword(
                                    storedAtStart = storedAtStart,
                                    loginTarget = instance,
                                    twoFaCode = twoFaCode
                            )
                }
                true
            }

    override suspend fun ensureSessionToken(
            instance: ServerInstance,
            twoFaCode: String?
    ): String =
            profileAuthenticationMutex.withLock {
                // Bind the operation to the latest stored token while requiring the caller's
                // origin, headers and credentials to still describe the same profile.
                var launchSnapshot = resolveCurrentAuthenticationSnapshot(instance)

                when (launchSnapshot.authType) {
                    AuthType.GUEST -> return@withLock ""
                    AuthType.API_KEY ->
                            return@withLock serverBoundStoredCredential(launchSnapshot)
                                    ?: throw SessionExpiredException("API Key is missing")
                    AuthType.PASSWORD -> Unit
                }

                val cachedToken = launchSnapshot.sessionToken?.takeIf { it.isNotBlank() }
                if (cachedToken != null) {
                    if (validateStoredPasswordSession(launchSnapshot, cachedToken)) {
                        return@withLock cachedToken
                    }
                    // validateStoredPasswordSession cleared exactly the token it validated.
                    launchSnapshot = launchSnapshot.copy(sessionToken = null)
                }

                loginWithStoredPassword(launchSnapshot, twoFaCode)
            }

    override suspend fun restoreSession(instance: ServerInstance): Boolean =
            profileAuthenticationMutex.withLock {
                val launchSnapshot = resolveCurrentAuthenticationSnapshot(instance)
                when (launchSnapshot.authType) {
                    AuthType.GUEST -> true
                    AuthType.API_KEY -> !launchSnapshot.apiKey.isNullOrBlank()
                    AuthType.PASSWORD -> {
                        val token = launchSnapshot.sessionToken?.takeIf { it.isNotBlank() }
                                ?: return@withLock false
                        validateStoredPasswordSession(launchSnapshot, token)
                    }
                }
            }

    /** Resolve a fresh DB snapshot without ever pairing stored credentials with a caller URL. */
    private suspend fun resolveCurrentAuthenticationSnapshot(
            requested: ServerInstance
    ): ServerInstance {
        return profileMutationMutex.withLock {
            val stored = requireCurrentProfile(requested.id)
            requireUnchangedProfile(sameServerAuthenticationConfiguration(stored, requested))
            stored
        }
    }

    private suspend fun validateStoredPasswordSession(
            launchSnapshot: ServerInstance,
            cachedToken: String
    ): Boolean {
        val isValid =
                authService.validateSession(
                        launchSnapshot.baseUrl,
                        cachedToken,
                        launchSnapshot.customHeaders
                )

        profileMutationMutex.withLock {
            val current = requireCurrentProfile(launchSnapshot.id)
            // A failed request may only clear the exact token and security identity it used.
            requireUnchangedProfile(sameServerAuthenticationState(launchSnapshot, current))
            if (!isValid) {
                dao.updateSessionToken(launchSnapshot.id, null)
            }
        }
        return isValid
    }

    /** Automatic login uses only the already verified, current stored credential snapshot. */
    private suspend fun loginWithStoredPassword(
            launchSnapshot: ServerInstance,
            twoFaCode: String?
    ): String {
        profileMutationMutex.withLock {
            val current = requireCurrentProfile(launchSnapshot.id)
            requireUnchangedProfile(sameServerAuthenticationState(launchSnapshot, current))
        }

        val loginResult =
                authService.login(
                        launchSnapshot.baseUrl,
                        launchSnapshot.username,
                        launchSnapshot.password,
                        // This value belongs only to this mutex-serialized refresh attempt. The
                        // successful persistence path stores the returned token, never the code.
                        twoFaCode = twoFaCode,
                        customHeaders = launchSnapshot.customHeaders
                )

        return when (loginResult) {
            is LoginResult.Success -> {
                val token = loginResult.sessionToken
                profileMutationMutex.withLock {
                    val current = requireCurrentProfile(launchSnapshot.id)
                    requireUnchangedProfile(
                            sameServerAuthenticationState(launchSnapshot, current)
                    )
                    dao.updateSessionToken(launchSnapshot.id, cryptoManager.encrypt(token))
                }
                Log.d(TAG, "Automatic login succeeded")
                token
            }
            is LoginResult.Requires2FA -> throw Requires2FAException(loginResult.message)
            is LoginResult.Error -> throw SessionExpiredException(loginResult.message)
        }
    }

    /**
     * A successful user re-login replaces username/password and token in one Room UPDATE. The
     * request is rejected if any security-bearing stored state changed while it was in flight.
     */
    private suspend fun loginWithExplicitPassword(
            storedAtStart: ServerInstance,
            loginTarget: ServerInstance,
            twoFaCode: String?
    ) {
        val loginResult =
                authService.login(
                        loginTarget.baseUrl,
                        loginTarget.username,
                        loginTarget.password,
                        twoFaCode,
                        loginTarget.customHeaders
                )

        when (loginResult) {
            is LoginResult.Success -> {
                profileMutationMutex.withLock {
                    val current = requireCurrentProfile(storedAtStart.id)
                    requireUnchangedProfile(
                            sameServerAuthenticationState(storedAtStart, current) &&
                                    sameServerAuthenticationOrigin(current, loginTarget)
                    )
                    dao.update(
                            mergeSuccessfulExplicitLogin(
                                            current,
                                            loginTarget,
                                            loginResult.sessionToken
                                    )
                                    .toEntity()
                    )
                }
                Log.d(TAG, "Explicit login succeeded")
            }
            is LoginResult.Requires2FA -> throw Requires2FAException(loginResult.message)
            is LoginResult.Error -> throw SessionExpiredException(loginResult.message)
        }
    }

    override suspend fun updateRequires2fa(
            expectedAuthentication: ServerInstance,
            requires2fa: Boolean
    ) {
        profileMutationMutex.withLock {
            val entity =
                    dao.getById(expectedAuthentication.id)
                            ?: throw SessionExpiredException("Server profile no longer exists")
            requireUnchangedProfile(
                    canUpdateRequiresTwoFactorHint(
                            stored = entity.toDomain(),
                            expectedAuthentication = expectedAuthentication
                    )
            )
            dao.update(entity.copy(requires2fa = requires2fa))
        }
    }

    private suspend fun requireCurrentProfile(id: Long): ServerInstance {
        return dao.getById(id)?.toDomain()
                ?: throw SessionExpiredException("Server profile no longer exists")
    }

    private fun requireUnchangedProfile(condition: Boolean) {
        if (!condition) {
            throw SessionExpiredException("Server settings changed; retry with the latest profile")
        }
    }

    // ─── Entity ↔ Domain 转换 ───

    private fun ServerInstanceEntity.toDomain(): ServerInstance {
        return ServerInstance(
                id = id,
                name = name,
                baseUrl = baseUrl,
                username =
                        try {
                            cryptoManager.decrypt(encryptedUsername)
                        } catch (e: Exception) {
                            ""
                        },
                password =
                        try {
                            cryptoManager.decrypt(encryptedPassword)
                        } catch (e: Exception) {
                            ""
                        },
                sessionToken =
                        encryptedSessionToken?.let {
                            try {
                                cryptoManager.decrypt(it)
                            } catch (e: Exception) {
                                null
                            }
                        },
                requires2fa = requires2fa,
                isActive = isActive,
                createdAt = createdAt,
                authType =
                        try {
                            AuthType.valueOf(authType)
                        } catch (e: Exception) {
                            AuthType.PASSWORD
                        },
                apiKey =
                        encryptedApiKey?.let {
                            try {
                                cryptoManager.decrypt(it)
                            } catch (e: Exception) {
                                null
                            }
                        },
                customHeaders = decryptCustomHeaders(encryptedCustomHeaders)
        )
    }

    private fun ServerInstance.toEntity(): ServerInstanceEntity {
        return ServerInstanceEntity(
                id = if (id == 0L) 0 else id,
                name = name,
                baseUrl = baseUrl,
                encryptedUsername = cryptoManager.encrypt(username),
                encryptedPassword = cryptoManager.encrypt(password),
                encryptedSessionToken =
                        sessionToken?.let {
                            try {
                                cryptoManager.encrypt(it)
                            } catch (e: Exception) {
                                null
                            }
                        },
                requires2fa = requires2fa,
                isActive = isActive,
                createdAt = createdAt,
                authType = authType.name,
                encryptedApiKey =
                        apiKey?.let {
                            try {
                                cryptoManager.encrypt(it)
                            } catch (e: Exception) {
                                null
                            }
                        },
                encryptedCustomHeaders = encryptCustomHeaders(customHeaders)
        )
    }

    private fun decryptCustomHeaders(encryptedCustomHeaders: String?): List<CustomHeader> {
        if (encryptedCustomHeaders.isNullOrBlank()) return emptyList()
        return try {
            val rawJson = cryptoManager.decrypt(encryptedCustomHeaders)
            json.decodeFromString(ListSerializer(CustomHeader.serializer()), rawJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encryptCustomHeaders(customHeaders: List<CustomHeader>): String? {
        val sanitized =
                customHeaders
                        .map { CustomHeader(it.name.trim(), it.value.trim()) }
                        .filter { it.name.isNotBlank() && it.value.isNotBlank() }
        if (sanitized.isEmpty()) return null
        return try {
            cryptoManager.encrypt(
                    json.encodeToString(
                            ListSerializer(CustomHeader.serializer()),
                            sanitized
                    )
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Resolves only credentials carried by the supplied immutable server snapshot.
 *
 * This has no global credential dependency, making concurrent resolution for different servers
 * deterministic and testable.
 */
internal fun serverBoundStoredCredential(instance: ServerInstance): String? {
    return when (instance.authType) {
        AuthType.GUEST -> ""
        AuthType.API_KEY -> instance.apiKey?.takeIf { it.isNotBlank() }
        AuthType.PASSWORD -> instance.sessionToken?.takeIf { it.isNotBlank() }
    }
}

/** Security-bearing request target, excluding credentials that an explicit re-login may replace. */
private data class ServerAuthenticationOrigin(
        val id: Long,
        val baseUrl: String,
        val authType: AuthType,
        val customHeaders: List<Pair<String, String>>
)

/** Credentials that are meaningful for the selected authentication mode only. */
private sealed interface ServerAuthenticationCredential {
    data object Guest : ServerAuthenticationCredential

    data class ApiKey(val value: String?) : ServerAuthenticationCredential

    data class Password(val username: String, val password: String) :
            ServerAuthenticationCredential
}

private fun ServerInstance.authenticationOrigin(): ServerAuthenticationOrigin {
    val canonicalHeaders =
            customHeaders
                    .map { header ->
                        header.name.lowercase(Locale.ROOT) to header.value
                    }
                    .sortedWith(
                            compareBy<Pair<String, String>> { it.first }
                                    .thenBy { it.second }
                    )
    return ServerAuthenticationOrigin(
            id = id,
            baseUrl = baseUrl.trimEnd('/'),
            authType = authType,
            customHeaders = canonicalHeaders
    )
}

private fun ServerInstance.authenticationCredential(): ServerAuthenticationCredential {
    return when (authType) {
        AuthType.GUEST -> ServerAuthenticationCredential.Guest
        AuthType.API_KEY -> ServerAuthenticationCredential.ApiKey(apiKey)
        AuthType.PASSWORD -> ServerAuthenticationCredential.Password(username, password)
    }
}

/**
 * Whether two snapshots can safely share an authentication result. Display name, active state,
 * creation time and the 2FA UI hint are intentionally not security identities.
 */
internal fun sameServerAuthenticationConfiguration(
        first: ServerInstance,
        second: ServerInstance
): Boolean {
    return first.authenticationOrigin() == second.authenticationOrigin() &&
            first.authenticationCredential() == second.authenticationCredential()
}

/**
 * Explicit password login may replace username/password, but it must stay on the same profile,
 * URL, auth mode and custom-header context.
 */
internal fun sameServerAuthenticationOrigin(
        first: ServerInstance,
        second: ServerInstance
): Boolean = first.authenticationOrigin() == second.authenticationOrigin()

/** Network post-flight guard, including the exact password-session token used by the request. */
internal fun sameServerAuthenticationState(
        first: ServerInstance,
        second: ServerInstance
): Boolean {
    return sameServerAuthenticationConfiguration(first, second) &&
            (first.authType != AuthType.PASSWORD || first.sessionToken == second.sessionToken)
}

/** A capability hint may cross a token refresh, but never an authentication-identity change. */
internal fun canUpdateRequiresTwoFactorHint(
        stored: ServerInstance,
        expectedAuthentication: ServerInstance
): Boolean = sameServerAuthenticationConfiguration(stored, expectedAuthentication)

/**
 * Optimistic-concurrency rule for an ordinary edit. Metadata-only edits are allowed to cross a
 * token refresh because they retain the DB token. An authentication-changing edit must still be
 * based on the exact stored security state, including its token.
 */
internal fun canApplyOrdinaryServerUpdate(
        stored: ServerInstance,
        requested: ServerInstance,
        expectedAuthentication: ServerInstance
): Boolean {
    if (stored.id != requested.id || stored.id != expectedAuthentication.id) return false
    return sameServerAuthenticationConfiguration(stored, requested) ||
            sameServerAuthenticationState(stored, expectedAuthentication)
}

/**
 * Ordinary profile edits never trust the caller's token snapshot. They preserve the latest
 * stored token only when every authentication-bearing setting is unchanged.
 */
internal fun mergeOrdinaryServerUpdate(
        stored: ServerInstance,
        requested: ServerInstance
): ServerInstance {
    require(stored.id == requested.id) { "Cannot merge different server profiles" }
    val retainedToken =
            if (requested.authType == AuthType.PASSWORD &&
                            sameServerAuthenticationConfiguration(stored, requested)
            ) {
                stored.sessionToken
            } else {
                null
            }
    return requested.copy(sessionToken = retainedToken)
}

/** Builds the single-row atomic mutation performed after a user-triggered password login. */
internal fun mergeSuccessfulExplicitLogin(
        storedAtCompletion: ServerInstance,
        loginTarget: ServerInstance,
        sessionToken: String
): ServerInstance {
    require(storedAtCompletion.authType == AuthType.PASSWORD) {
        "Explicit password login requires password authentication"
    }
    require(sameServerAuthenticationOrigin(storedAtCompletion, loginTarget)) {
        "Explicit login target changed"
    }
    require(sessionToken.isNotBlank()) { "Session token must not be blank" }
    return storedAtCompletion.copy(
            username = loginTarget.username,
            password = loginTarget.password,
            sessionToken = sessionToken,
            requires2fa = loginTarget.requires2fa
    )
}
