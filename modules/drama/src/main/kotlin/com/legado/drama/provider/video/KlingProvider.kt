package com.legado.drama.provider.video

import com.legado.drama.engine.provider.ModelSpec
import com.legado.drama.engine.provider.PollResult
import com.legado.drama.engine.provider.ProviderError
import com.legado.drama.engine.provider.VideoSubmitRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 可灵 Kling 视频生成适配器（P0-③：独立实现，非 Agnes 兼容壳；对齐源工程 KlingProvider）。
 *
 * API（生产）：https://api-beijing.klingai.com
 * - 文生视频 POST /v1/videos/text2video；图生视频 POST /v1/videos/image2video
 * - 鉴权：Authorization: Bearer <API_KEY>
 * - 关键帧：image（首帧）+ image_tail（尾帧），URL 或裸 base64（非 data: URI）
 * - 尺寸仅 aspect_ratio（无 width/height）；时长 duration 字符串；sound=on/off 控制音频
 * - 轮询 GET /v1/videos/{type}/{task_id}；task_status: submitted/processing/succeed/failed
 *   视频 URL：data.task_result.videos[].url
 */
class KlingProvider(
    override var apiKeyProvider: suspend () -> String = { "" },
    client: io.ktor.client.HttpClient = defaultHttpClient(),
    rateGate: com.legado.drama.engine.queue.RateGate = com.legado.drama.engine.queue.DefaultRateGate(),
    sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : BaseVideoProvider(apiKeyProvider, client, rateGate, sleeper) {

    override val id: String = "kling"
    override val baseUrl: String = "https://api-beijing.klingai.com"

    private val defaultModel = "kling-v2-6"
    private val defaultAspect = "9:16"

    override fun listModels(): List<ModelSpec> = listOf(
        ModelSpec(
            id = "kling-v2-6", label = "可灵 Kling v2.6",
            providerId = id, baseUrl = baseUrl,
            supportsAudio = true,
        ),
        ModelSpec(
            id = "kling-v3", label = "可灵 Kling v3",
            providerId = id, baseUrl = baseUrl,
            supportsAudio = true,
        ),
        ModelSpec(
            id = "kling-v1-6", label = "可灵 Kling v1.6（多图参考）",
            providerId = id, baseUrl = baseUrl,
            supportsAudio = true,
        ),
    )

    override suspend fun doSubmit(req: VideoSubmitRequest): String {
        val first = firstReference(req)
        val last = req.lastImageUri
        val useImage = first != null || last != null
        val endpoint = if (useImage) "/v1/videos/image2video" else "/v1/videos/text2video"

        val firstNorm = normalizeImage(first, ImageAcceptance.RAW_BASE64)
        val lastNorm = normalizeImage(last, ImageAcceptance.RAW_BASE64)

        val body = buildJsonObject {
            put("model_name", defaultModel)
            put("prompt", req.prompt)
            put("duration", durationSeconds(req.numFrames, req.frameRate).toString())
            put("mode", "std")
            put("aspect_ratio", aspectRatio(req.width, req.height).takeIf { it != "1:1" } ?: defaultAspect)
            put("sound", if (req.generateAudio) "on" else "off")
            req.negativePrompt?.let { put("negative_prompt", it) }
            if (firstNorm != null) put("image", JsonPrimitive(firstNorm))
            if (lastNorm != null) put("image_tail", JsonPrimitive(lastNorm))
        }
        val out = postJson(endpoint, body)
        val taskId = out["data"]?.jsonObject?.get("task_id")?.jsonPrimitive?.content
            ?: throw ProviderError.TransientError(
                "提交成功但未返回 data.task_id（remote task may be billed）: ${out.toString().take(400)}",
            )
        // 轮询路径依赖提交端点（text2video/image2video），编码进 taskId 以跨重启存活
        return "${endpoint.substringAfterLast("/")}:$taskId"
    }

    override suspend fun doPoll(taskId: String): PollResult {
        val (type, id) = taskId.split(":", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else "text2video" to taskId
        }
        val out = getJson("/v1/videos/$type/$id")
        val status = out["data"]?.jsonObject?.get("task_status")?.jsonPrimitive?.content ?: "processing"
        return when (status) {
            "succeed" -> {
                val videos = out["data"]?.jsonObject?.get("task_result")?.jsonObject
                    ?.get("videos")?.jsonArray
                val url = videos?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                    ?: return PollResult.Failed("completed but missing video url")
                PollResult.Completed(url)
            }
            "failed" -> {
                val msg = out["data"]?.jsonObject?.get("task_status_msg")?.jsonPrimitive?.content ?: "unknown"
                PollResult.Failed(msg.take(400))
            }
            else -> PollResult.InProgress(null)
        }
    }
}