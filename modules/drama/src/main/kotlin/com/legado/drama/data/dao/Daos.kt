package com.legado.drama.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.legado.drama.data.entity.AssetEntity
import com.legado.drama.data.entity.EpisodeEntity
import com.legado.drama.data.entity.FinishedFilmEntity
import com.legado.drama.data.entity.ProjectEntity
import com.legado.drama.data.entity.ProviderConfigEntity
import com.legado.drama.data.entity.RenderTaskEntity
import com.legado.drama.data.entity.ShotEntity
import kotlinx.coroutines.flow.Flow

/** 项目 */
@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE project_id = :projectId LIMIT 1")
    suspend fun get(projectId: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE project_id = :projectId")
    suspend fun delete(projectId: String)
}

/** 资产（含 6pose 包） */
@Dao
interface AssetDao {
    @Query("SELECT * FROM assets WHERE project_id = :projectId ORDER BY kind, asset_id")
    fun observeByProject(projectId: String): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE project_id = :projectId")
    suspend fun listByProject(projectId: String): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE asset_id = :assetId LIMIT 1")
    suspend fun get(assetId: String): AssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: AssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assets: List<AssetEntity>)

    @Query("SELECT COUNT(*) FROM assets WHERE project_id = :projectId AND g1_state = 'pass'")
    suspend fun countG1Passed(projectId: String): Int

    @Query("SELECT COUNT(*) FROM assets WHERE project_id = :projectId AND review_state = 'keep'")
    suspend fun countReviewed(projectId: String): Int

    @Query("SELECT COUNT(*) FROM assets WHERE project_id = :projectId")
    suspend fun countAll(projectId: String): Int

    @Query("UPDATE assets SET review_state = :state WHERE asset_id IN (:assetIds)")
    suspend fun updateReviewState(assetIds: List<String>, state: String)
}

/** 剧集 */
@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE project_id = :projectId ORDER BY ep_no")
    fun observeByProject(projectId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes")
    suspend fun listAll(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE project_id = :projectId ORDER BY ep_no")
    suspend fun listByProject(projectId: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE episode_id = :episodeId LIMIT 1")
    suspend fun get(episodeId: String): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(episode: EpisodeEntity)

    @Query("UPDATE episodes SET review_passed = :passed WHERE episode_id = :episodeId")
    suspend fun setReviewPassed(episodeId: String, passed: Boolean)

    @Query("UPDATE episodes SET storyboard_report = :report WHERE episode_id = :episodeId")
    suspend fun setStoryboardReport(episodeId: String, report: String)

    @Query("UPDATE episodes SET stage_flags = :flags WHERE episode_id = :episodeId")
    suspend fun setStageFlags(episodeId: String, flags: String)
}

/** 分镜 */
@Dao
interface ShotDao {
    @Query("SELECT * FROM shots WHERE episode_id = :episodeId ORDER BY shot_no")
    suspend fun listByEpisode(episodeId: String): List<ShotEntity>

    @Query("SELECT * FROM shots WHERE episode_id = :episodeId ORDER BY shot_no")
    fun observeByEpisode(episodeId: String): Flow<List<ShotEntity>>

    @Query("SELECT * FROM shots WHERE shot_id = :shotId LIMIT 1")
    suspend fun get(shotId: String): ShotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(shots: List<ShotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shot: ShotEntity)

    @Query("DELETE FROM shots WHERE shot_id = :shotId")
    suspend fun delete(shotId: String)

    @Query("DELETE FROM shots WHERE episode_id = :episodeId")
    suspend fun deleteByEpisode(episodeId: String)
}

/** 渲染任务 checkpoint */
@Dao
interface RenderTaskDao {
    @Query("SELECT * FROM render_tasks WHERE episode_id = :episodeId ORDER BY shot_id")
    suspend fun listByEpisode(episodeId: String): List<RenderTaskEntity>

    @Query("SELECT * FROM render_tasks WHERE episode_id = :episodeId")
    fun observeByEpisode(episodeId: String): Flow<List<RenderTaskEntity>>

    @Query("SELECT * FROM render_tasks WHERE shot_id = :shotId LIMIT 1")
    suspend fun get(shotId: String): RenderTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: RenderTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<RenderTaskEntity>)

    @Query("SELECT COUNT(*) FROM render_tasks WHERE episode_id = :episodeId AND state = 'SUBMITTED'")
    suspend fun countSubmitted(episodeId: String): Int
}

/** 供应商配置 */
@Dao
interface ProviderConfigDao {
    @Query("SELECT * FROM provider_configs ORDER BY channel, provider_id")
    fun observeAll(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs WHERE config_id = :configId LIMIT 1")
    suspend fun get(configId: String): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs WHERE channel = :channel ORDER BY updated_at DESC LIMIT 1")
    suspend fun latestByChannel(channel: String): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs WHERE channel IN (:channels)")
    suspend fun listByChannels(channels: List<String>): List<ProviderConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: ProviderConfigEntity)

    @Query("DELETE FROM provider_configs WHERE config_id = :configId")
    suspend fun delete(configId: String)
}

/** 成片库 */
@Dao
interface FinishedFilmDao {
    @Query("SELECT * FROM finished_films ORDER BY assembled_at DESC")
    fun observeAll(): Flow<List<FinishedFilmEntity>>

    @Query("SELECT * FROM finished_films WHERE film_id = :filmId LIMIT 1")
    suspend fun get(filmId: String): FinishedFilmEntity?

    @Query("SELECT * FROM finished_films WHERE episode_id = :episodeId LIMIT 1")
    suspend fun getByEpisode(episodeId: String): FinishedFilmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(film: FinishedFilmEntity)

    @Query("DELETE FROM finished_films WHERE film_id = :filmId")
    suspend fun delete(filmId: String)
}