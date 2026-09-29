package com.ssafy.notevault.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SubscriptionEntity::class,
        FileEntity::class,
        MetaEntity::class,
        BookmarkEntity::class,
        RecentEntity::class,
    ],
    version = 1,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao

    companion object {
        /** 앱 전체에서 하나만 만든다 (AppContainer 가 보관). WAL 모드는 Room 기본값이다. */
        fun create(context: Context): VaultDatabase =
            Room.databaseBuilder(context, VaultDatabase::class.java, "notevault.db").build()
    }
}
