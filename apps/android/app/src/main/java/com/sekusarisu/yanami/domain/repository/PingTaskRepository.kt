package com.sekusarisu.yanami.domain.repository

import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.CustomHeader
import com.sekusarisu.yanami.domain.model.AdminPingTask
import com.sekusarisu.yanami.domain.model.AdminPingTaskDraft

interface PingTaskRepository {
    suspend fun listPingTasks(
        baseUrl: String,
        sessionToken: String,
        authType: AuthType,
        customHeaders: List<CustomHeader>
    ): List<AdminPingTask>

    suspend fun addPingTask(
        baseUrl: String,
        sessionToken: String,
        authType: AuthType,
        customHeaders: List<CustomHeader>,
        draft: AdminPingTaskDraft
    ): Int

    suspend fun updatePingTasks(
        baseUrl: String,
        sessionToken: String,
        authType: AuthType,
        customHeaders: List<CustomHeader>,
        tasks: List<AdminPingTask>
    )

    suspend fun deletePingTasks(
        baseUrl: String,
        sessionToken: String,
        authType: AuthType,
        customHeaders: List<CustomHeader>,
        ids: List<Int>
    )

    suspend fun reorderPingTasks(
        baseUrl: String,
        sessionToken: String,
        authType: AuthType,
        customHeaders: List<CustomHeader>,
        weights: Map<Int, Int>
    )
}
