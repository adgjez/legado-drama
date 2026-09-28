package com.legado.drama.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
 *
 * v2（P0 第 2 项）：assets 表新增 reference_image_uri 列（图生图参考图 i2i 接线）。
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
    version = 2,
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
        /** v1→v2：assets 表追加 reference_image_uri 列（图生图参考图） */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assets ADD COLUMN reference_image_uri TEXT")
            }
        }

        fun build(context: Context): DramaDatabase =
            Room.databaseBuilder(context.applicationContext, DramaDatabase::class.java, "drama.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration() // 独立库从零重建，schema 变更不阻塞阅读器
                .build()
    }
}