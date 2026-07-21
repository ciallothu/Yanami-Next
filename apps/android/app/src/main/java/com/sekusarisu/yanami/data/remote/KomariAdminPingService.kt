package com.sekusarisu.yanami.data.remote

import com.sekusarisu.yanami.data.remote.dto.AdminEnvelopeDto
import com.sekusarisu.yanami.data.remote.dto.AdminPingTaskDto
import com.sekusarisu.yanami.data.remote.dto.PingTaskCreateResultDto
import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.CustomHeader
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class KomariAdminPingService(private val httpClient: HttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun listPingTasks(
        baseUrl: String,
        sessionToken: String,
        authType: AuthType,
        customHeaders: List<CustomHeader>
    ): List<AdminPingTaskDto> {
        val response =
            httpClient.get(baseUrl.trimEnd('/') + "/api/admin/ping/") {
                applyAdminAuth(sessionToken, authType, customHeaders)
            }
        return parseData(response, ListSerializer(AdminPingTaskDto.serializer()))
    }

    suspend fun addPingTask(
        baseUrl: String,
        sessionToken: String,
        authType: AuthType,
        customHeaders: List<CustomHeader>,
        payload: JsonObject
    ): Int {
        val response =
            httpClient.post(baseUrl.trimEnd('/') + "/api/admin/ping/add") {
                applyAdminAuth(sessionToken, authType, customHeaders)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        return parseData(response, PingTaskCreateResultDto.serializer()).taskId
    }

    suspend fun updatePingTasks(
        baseUrl: String,
        sessionToken: String,
        authType: AuthType,
        customHeaders: List<CustomHeader>,
        tasks: List<JsonObject>
    ) {
        val payload =
            buildJsonObject {
                putJsonArray("tasks") {
                    tasks.forEach { task -> add(task) }
                }
            }
        val response =
            httpClient.post(baseUrl.trimEnd('/') + "/api/admin/ping/edit") {
                applyAdminAuth(sessionToken, authType, customHeaders)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        parseNoContent(response)
    }

    suspend fun deletePingTasks(
        baseUrl: String,
        sessionToken: String,
        authType: AuthType,
        customHeaders: List<CustomHeader>,
        ids: List<Int>
    ) {
        val payload =
            buildJsonObject {
                putJsonArray("id") {
                    ids.forEach { id -> add(id) }
                }
            }
        val response =
            httpClient.post(baseUrl.trimEnd('/') + "/api/admin/ping/delete") {
                applyAdminAuth(sessionToken, authType, customHeaders)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        parseNoContent(response)
    }

    suspend fun reorderPingTasks(
        baseUrl: String,
        sessionToken: String,
        authType: AuthType,
        customHeaders: List<CustomHeader>,
        weights: Map<Int, Int>
    ) {
        val payload =
            buildJsonObject {
                weights.forEach { (id, weight) ->
                    put(id.toString(), weight)
                }
            }
        val response =
            httpClient.post(baseUrl.trimEnd('/') + "/api/admin/ping/order") {
                applyAdminAuth(sessionToken, authType, customHeaders)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        parseNoContent(response)
    }

    private suspend fun <T> parseData(response: HttpResponse, serializer: KSerializer<T>): T {
        val statusCode = response.status.value
        val responseText = response.bodyAsText()
        val envelope = parseEnvelopeIfPresent(statusCode, responseText)
        if (envelope != null) {
            val data =
                envelope.data
                    ?: throw AdminApiException(
                        statusCode,
                        missingRemoteDataMessage(statusCode)
                    )
            return runCatching { json.decodeFromJsonElement(serializer, data) }
                .getOrElse {
                    throw AdminApiException(
                        statusCode,
                        invalidRemoteResponseMessage(statusCode)
                    )
                }
        }

        if (statusCode !in 200..299) {
            throw AdminApiException(statusCode, "HTTP $statusCode")
        }
        return runCatching { json.decodeFromString(serializer, responseText) }
            .getOrElse {
                throw AdminApiException(
                    statusCode,
                    invalidRemoteResponseMessage(statusCode)
                )
            }
    }

    private suspend fun parseNoContent(response: HttpResponse) {
        val responseText = response.bodyAsText()
        if (response.status.value in 200..299 && responseText.isBlank()) {
            return
        }
        if (parseEnvelopeIfPresent(response.status.value, responseText) == null) {
            throw AdminApiException(
                response.status.value,
                invalidRemoteResponseMessage(response.status.value)
            )
        }
    }

    private fun parseEnvelopeIfPresent(
        statusCode: Int,
        responseText: String
    ): AdminEnvelopeDto? {
        val root =
            runCatching { json.parseToJsonElement(responseText) }
                .getOrElse {
                    throw AdminApiException(
                        statusCode,
                        invalidRemoteResponseMessage(statusCode)
                    )
                }
        val rootObject = root as? JsonObject ?: return null
        val isEnvelope =
            rootObject.containsKey("status") ||
                rootObject.containsKey("message") ||
                rootObject.containsKey("data")
        if (!isEnvelope) return null

        val envelope =
            runCatching {
                    json.decodeFromJsonElement(AdminEnvelopeDto.serializer(), rootObject)
                }
                .getOrElse {
                    throw AdminApiException(
                        statusCode,
                        invalidRemoteResponseMessage(statusCode)
                    )
                }
        if (statusCode !in 200..299 || envelope.status.equals("error", ignoreCase = true)) {
            throw AdminApiException(
                statusCode = statusCode,
                message = safeRemoteErrorMessage(envelope.message, statusCode)
            )
        }
        if (envelope.status.isBlank() && envelope.data == null) {
            throw AdminApiException(statusCode, invalidRemoteResponseMessage(statusCode))
        }
        return envelope
    }
}
