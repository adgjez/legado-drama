package com.legado.drama.assemble

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.legado.drama.engine.assemble.AssembleStage
import com.legado.drama.engine.assemble.AssembleStrategySelector
import com.legado.drama.engine.assemble.ClipSpec
import com.legado.drama.engine.assemble.MovieAssembleProgress
import com.legado.drama.engine.assemble.MovieAssembler
import com.legado.drama.engine.assemble.MovieAssembler.ColorGradePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Media3 Transformer 降级合成器（T014-arch.md §2.4 + 架构文档 §1.3 Q5 降级路径）：
 * ffmpeg-kit-full 上游已归档（maven central 404 / jitpack 401，不可获取），
 * 因此端侧改用 Media3 Transformer 完成同规格拼接 / 归一化 / 分段导出：
 *  - CONCAT_COPY → 全部片段经 probe 一致时按序拼接（Transformer 串行导出单文件）
 *  - NORMALIZE   → 分辨率/帧率不一致时统一归一化后拼接
 *  - SEGMENTED   → 片段过多（>32）或超时预算时每 8 镜一段导出 parts
 */
@UnstableApi
class Media3MovieAssembler(
    private val context: Context,
) : MovieAssembler {

    private val _progress = MutableStateFlow(
        MovieAssembleProgress(AssembleStage.GRADE, 0, 0, "准备合成…", 0L),
    )
    override val progress: StateFlow<MovieAssembleProgress> = _progress

    override suspend fun assemble(
        clips: List<File>,
        output: File,
        grade: ColorGradePreset,
    ): MovieAssembler.AssembleResult {
        val startMs = System.currentTimeMillis()
        if (clips.isEmpty()) {
            return MovieAssembler.AssembleResult.Failure(MovieAssembler.Strategy.CONCAT_COPY, "无单镜片段")
        }
        if (clips.any { !it.exists() || it.length() <= 0L }) {
            return MovieAssembler.AssembleResult.Failure(
                MovieAssembler.Strategy.CONCAT_COPY,
                "存在缺失或 0 字节片段",
            )
        }

        // ① 探测规格（MediaMetadataRetriever）
        _progress.value = _progress.value.copy(stage = AssembleStage.PROBE, message = "探测片段规格…")
        val specs = clips.mapNotNull { probe(it) }
        val strategy = AssembleStrategySelector.select(clips, specs)

        // ② 分段导出：>32 镜或强制
        if (strategy == MovieAssembler.Strategy.SEGMENTED && clips.size > AssembleStrategySelector.SEGMENT_SIZE) {
            val partsDir = File(output.parentFile, "parts-${System.currentTimeMillis()}")
            partsDir.mkdirs()
            val parts = mutableListOf<File>()
            _progress.value = _progress.value.copy(stage = AssembleStage.CONCAT, message = "分段导出（每 ${AssembleStrategySelector.SEGMENT_SIZE} 镜一段）…")
            clips.chunked(AssembleStrategySelector.SEGMENT_SIZE).forEachIndexed { idx, chunk ->
                val part = File(partsDir, "part-%02d.mp4".format(idx + 1))
                if (exportChunk(chunk, part)) {
                    parts += part
                } else {
                    return MovieAssembler.AssembleResult.Failure(strategy, "分段 ${idx + 1} 导出失败")
                }
            }
            return MovieAssembler.AssembleResult.Segmented(
                parts = parts,
                elapsedMs = System.currentTimeMillis() - startMs,
            )
        }

        // ③ 单文件导出（CONCAT_COPY / NORMALIZE 统一走 Transformer 按序拼接）
        _progress.value = _progress.value.copy(
            stage = AssembleStage.CONCAT,
            message = "拼接 ${clips.size} 镜（${strategy.label}）…",
        )
        val ok = exportChunk(clips, output)
        if (!ok) {
            return MovieAssembler.AssembleResult.Failure(strategy, "Transformer 导出失败")
        }
        _progress.value = _progress.value.copy(stage = AssembleStage.DONE, message = "成片完成")
        val duration = specs.sumOf { it.durationMs } / 1000.0
        return MovieAssembler.AssembleResult.Success(
            output = output,
            strategy = strategy,
            elapsedMs = System.currentTimeMillis() - startMs,
            durationSeconds = duration,
        )
    }

    /** 探测单镜：宽高/帧率/时长。失败返回 null（视作规格不一致 → 归一化） */
    private fun probe(file: File): ClipSpec? = runCatching {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(file.absolutePath)
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        mmr.release()
        if (w <= 0 || h <= 0) null else ClipSpec(w, h, 24f, true, dur)
    }.getOrNull()

    /** 将一组片段按序导出为一个 mp4（Transformer 内部归一化编码） */
    private suspend fun exportChunk(clips: List<File>, output: File): Boolean {
        _progress.value = _progress.value.copy(
            total = clips.size,
            message = "导出 ${output.name}（${clips.size} 镜）…",
        )
        return suspendCancellableCoroutine { cont ->
            val items = clips.map { EditedMediaItem.Builder(MediaItem.fromUri(it.toURI().toString())).build() }
            val composition = Composition.Builder(
                androidx.media3.transformer.EditedMediaItemSequence.Builder(items).build(),
            ).build()
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            try {
                transformer.start(composition, output.absolutePath)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}