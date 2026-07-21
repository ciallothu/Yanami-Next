package com.sekusarisu.yanami.domain.repository

import com.sekusarisu.yanami.domain.model.CustomHeader
import com.sekusarisu.yanami.domain.model.ServerInstance
import kotlinx.coroutines.flow.Flow

/** 服务端实例仓库接口 */
interface ServerRepository {

    /** 获取所有实例（一次性） */
    suspend fun getAll(): List<ServerInstance>

    /** 获取所有实例（Flow，实时更新） */
    fun getAllFlow(): Flow<List<ServerInstance>>

    /** 获取当前激活的实例 */
    suspend fun getActive(): ServerInstance?

    /** 获取当前激活的实例（Flow） */
    fun getActiveFlow(): Flow<ServerInstance?>

    /** 根据 ID 获取实例 */
    suspend fun getById(id: Long): ServerInstance?

    /**
     * 添加新实例
     *
     * @return 新实例的 ID
     */
    suspend fun add(instance: ServerInstance): Long

    /**
     * 更新实例。
     *
     * [expectedAuthentication] 是编辑开始时的安全快照：同认证身份的普通元数据更新可在
     * token 变化后继续并保留数据库最新 token；认证身份发生变化时则用它阻止旧编辑覆盖
     * 或清除并发写入的新凭据/token。
     */
    suspend fun update(instance: ServerInstance, expectedAuthentication: ServerInstance)

    /** 删除实例 */
    suspend fun remove(id: Long)

    /** 设置指定实例为活跃 */
    suspend fun setActive(id: Long)

    /**
     * 测试与服务端的连接（登录 + 获取版本号）
     *
     * @return 成功返回版本信息，失败抛出异常
     */
    suspend fun testConnection(
            baseUrl: String,
            username: String,
            password: String,
            twoFaCode: String? = null,
            customHeaders: List<CustomHeader> = emptyList()
    ): String

    /**
     * 使用 API Key 测试与服务端的连接
     *
     * @return 成功返回版本信息，失败抛出异常
     */
    suspend fun testConnectionWithApiKey(
            baseUrl: String,
            apiKey: String,
            customHeaders: List<CustomHeader> = emptyList()
    ): String

    /**
     * 使用游客模式测试与服务端的连接（无认证）
     *
     * @return 成功返回版本信息，失败抛出异常
     */
    suspend fun testConnectionAsGuest(
            baseUrl: String,
            customHeaders: List<CustomHeader> = emptyList()
    ): String

    /**
     * 登录到指定实例
     *
     * 登录前后均校验持久化实例的认证上下文。密码登录成功时在单次数据库更新中原子保存
     * 用户名、密码、2FA 标记与 session_token。
     * @return 成功返回 true，需要 2FA 抛出 Requires2FAException，其他失败抛异常
     */
    suspend fun login(instance: ServerInstance, twoFaCode: String? = null): Boolean

    /**
     * 尝试恢复缓存的 session
     *
     * 从 ServerInstance 快照读取已解密的 session_token 并验证有效性。
     * @return true = 恢复成功，false = token 无效或不存在（需要重新登录）
     */
    suspend fun restoreSession(instance: ServerInstance): Boolean

    /**
     * 确保当前 session 有效，返回可用的 session_token
     *
     * 先尝试恢复缓存 token；若失效则自动重新登录。[twoFaCode] 只允许用于这一次密码
     * 登录刷新，不会持久化。敏感终端可将同一份瞬时验证码另行用于本次握手头。
     * 需要 2FA 时抛出 Requires2FAException，其他认证失败抛 SessionExpiredException。
     */
    suspend fun ensureSessionToken(instance: ServerInstance, twoFaCode: String? = null): String

    /**
     * 更新实例的 requires_2fa 标记。
     *
     * [expectedAuthentication] 必须仍与数据库中的完整认证配置一致，防止旧请求把推断结果
     * 写入已改地址、凭据、认证模式或自定义头的实例。并发 token 刷新不影响该能力标记。
     */
    suspend fun updateRequires2fa(
            expectedAuthentication: ServerInstance,
            requires2fa: Boolean
    )

}

/** 需要 2FA 验证码异常 */
class Requires2FAException(message: String = "需要输入两步验证码") : Exception(message)

/** Session 过期异常 */
class SessionExpiredException(message: String = "Session 已过期，请重新登录") : Exception(message)
