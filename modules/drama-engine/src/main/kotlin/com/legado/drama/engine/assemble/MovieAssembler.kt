package com.legado.drama.engine.assemble

import com.legado.drama.engine.model.FinishedFilmMeta
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 端上成片合成器契约（T014-arch.md §2.4 + 架构文档 §1.3 Q5）：
 * 三级策略直接映射 FfmpegAssembler 的 concat-copy / 归一化 / 分段导出。
 * 端侧实现用 com.arthenica:ffmpeg-kit-full:4.5.LTS（Android 层），本层只定义契约与纯逻辑。
 */

/** 合成进度（UI 可见） */
data class MovieAssembleProgress(
    val stage: AssembleStage,
    val step: Int,
    val total: Int,
    val message: String,
    val elapsedMs: Long,
)

enum class AssembleStage { GRADE, CONCAT, NORMALIZE, PROBE, DONE }

interface MovieAssembler {
    val progress: StateFlow<MovieAssembleProgress>

    /**
     * 合成整集。
     * @param clips 已按 shot_no 升序的本地单镜 mp4 文件列表（必须全部存在且 >0 字节）
     * @param output 目标 mp4 文件，位于 Context.getFilesDir()/movies/
     * @param grade 统一色彩分级配方
     */
    suspend fun assemble(
        clips: List<File>,
        output: File,
        grade: ColorGradePreset = ColorGradePreset.CINEMA,
    ): AssembleResult

    sealed class AssembleResult {
        data class Success(
            val output: File,
            val strategy: Strategy,
            val elapsedMs: Long,
            val durationSeconds: Double,   // ffprobe 读取成片时长
        ) : AssembleResult()

        data class Segmented(
            val parts: List<File>,
            val elapsedMs: Long,
            val durationSeconds: Double = 0.0,
        ) : AssembleResult()

        data class Failure(val strategy: Strategy, val message: String) : AssembleResult()
    }

    enum class Strategy(val label: String) {
        CONCAT_COPY("concat-copy"),
        NORMALIZE("mediacodec归一化"),
        SEGMENTED("分段导出"),
    }

    enum class ColorGradePreset(val filter: String) {
        CINEMA("eq=contrast=1.08:brightness=-0.02:saturation=1.06,colortemperature=warm=0.06,format=yuv420p"),
        COOL("eq=contrast=1.06:saturation=1.04,colortemperature=warm=-0.08,format=yuv420p"),
        WARM("eq=contrast=1.06:saturation=1.08,colortemperature=warm=0.12,format=yuv420p"),
        NEUTRAL("format=yuv420p"),
    }
}

/** 三级策略选择纯逻辑（JVM 可单测）：
 * 1. 全部片段编码参数一致 → CONCAT_COPY
 * 2. 混合分辨率/帧率 → NORMALIZE
 * 3. 片段过多或耗时预计超限 → SEGMENTED（每 8 镜一段）
 */
object AssembleStrategySelector {

    /** 每 8 镜一段（架构文档 §1.3 降级路径） */
    const val SEGMENT_SIZE = 8

    /**
     * @param clips 已排序片段文件；调用方保证全部存在且 >0 字节
     * @param probeResult null 表示无法探测规格（视为不一致，走归一化）
     */
    fun select(
        clips: List<File>,
        probeResult: List<ClipSpec>?,
        preferSegmented: Boolean = false,
    ): MovieAssembler.Strategy {
        if (preferSegmented || clips.size > SEGMENT_SIZE * 4) return MovieAssembler.Strategy.SEGMENTED
        if (probeResult == null) return MovieAssembler.Strategy.NORMALIZE
        val allSame = probeResult.distinctBy { it.resolutionKey() to it.frameRate }.size == 1
        return if (allSame) MovieAssembler.Strategy.CONCAT_COPY else MovieAssembler.Strategy.NORMALIZE
    }
}

/** 片段规格探测结果（Android 层用 MediaExtractor/FFprobe 填充） */
data class ClipSpec(
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val hasAudio: Boolean,
    val durationMs: Long,
) {
    fun resolutionKey(): String = "$width x $height"
}