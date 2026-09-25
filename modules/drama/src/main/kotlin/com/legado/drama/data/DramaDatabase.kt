package com.legado.drama.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.legado.drama.data.dao.AssetDao
import com.legado.drama.data.dao.EpisodeDao
import com.legado.drama.data.dao.FinishedFilmDao
import com.legado.drama.data.dao.ProjectDao
import com.legado.drama.data.dao.ProviderConfigDao
import com.legado.drama.data.dao.RenderTaskDao
import com.legado.drama.data.dao.ShotDao
import com.legado.drama.data.entity.AssetEntity
import com.legado.drama.data.entity.EpisodeEntity
import com.legado.drama.data.entity.FinishedFilmEntity
import com.legado.drama.data.entity.ProjectEntity
import com.legado.drama.data.entity.ProviderConfigEntity
import com.legado.drama.data.entity.RenderTaskEntity
import com.legado.drama.data.entity.ShotEntity

/**
 * Drama 数据库（架构文档 §5）：
 * 6 张业务表 + T014 finished_films 成片表，共 7 张。
 * 从零重建 → version=1，schema 完整落地（不携带历史迁移链）。
 */
@Database(
    entities = [
        ProjectEntity::class,
        AssetEntity::class,
        ShotEntity::class,
        RenderTaskEntity::class,
        ProviderConfigEntity::class,
        EpisodeEntity::class,
        FinishedFilmEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DramaDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun assetDao(): AssetDao
    abstract fun shotDao(): ShotDao
    abstract fun renderTaskDao(): RenderTaskDao
    abstract fun providerConfigDao(): ProviderConfigDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun finishedFilmDao(): FinishedFilmDao

    companion object {
        fun build(context: Context): DramaDatabase =
            Room.databaseBuilder(context.applicationContext, DramaDatabase::class.java, "drama.db")
                .fallbackToDestructiveMigration() // 独立库从零重建，schema 变更不阻塞阅读器
                .build()
    }
}