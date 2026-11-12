package com.zhicun.app.data.local

import android.content.Context
import androidx.room.Room
import com.zhicun.app.data.local.migration.ALL_MIGRATIONS

/**
 * 数据库构建的唯一入口。
 *
 * 生产（Hilt）与测试都**必须**经由此处构建，否则 [FtsSyncCallback] 可能漏注册，
 * 导致全文检索虚表在某个环境里静默缺失 —— 这种偏差在功能上线前极难发现。
 * 迁移也在这里统一注册，避免"某条路径建库时忘了带迁移"。
 */
object AppDatabaseFactory {

    /**
     * 磁盘数据库（生产使用）。
     *
     * 刻意**不加** `fallbackToDestructiveMigration()`：迁移写错时宁可启动失败（崩溃日志里有明确原因），
     * 也不能悄悄清库。这个 App 的全部数据都只在这台设备上，清掉就没了。
     */
    fun create(context: Context, name: String = AppDatabase.DATABASE_NAME): AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(FtsSyncCallback())
            .build()

    /** 内存数据库（单元测试 / 集成测试使用，进程结束即销毁）。 */
    fun createInMemory(context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(FtsSyncCallback())
            .allowMainThreadQueries()
            .build()
}
