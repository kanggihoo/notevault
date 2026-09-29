package com.ssafy.notevault.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 * 테이블 정의. RN 판 src/db/schema.ts 와 같은 스키마다 (컬럼 이름까지 동일).
 * JPA 의 @Entity 와 같은 역할이지만 연관관계 매핑은 없다 — 전부 path 문자열로 잇는다.
 */

/** 구독 폴더 경로(prefix). "" 은 볼트 전체 구독을 뜻한다. */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(@PrimaryKey val path: String)

/** 받아둔 파일. blob_sha 가 증분 동기화의 전부다. */
@Entity(tableName = "files")
data class FileEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "blob_sha") val blobSha: String,
    val size: Long,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long,
)

/** last_commit_sha, owner, repo, branch 등 key-value. 키 목록은 [MetaKeys]. */
@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "recents")
data class RecentEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
)

object MetaKeys {
    /** "그 시점의 구독 집합 기준으로 이 커밋까지 동기화됨" 완료 마커. */
    const val LAST_COMMIT_SHA = "last_commit_sha"
    const val LAST_SYNCED_AT = "last_synced_at"
    const val OWNER = "owner"
    const val REPO = "repo"
    const val BRANCH = "branch"
}

const val RECENTS_LIMIT = 30
