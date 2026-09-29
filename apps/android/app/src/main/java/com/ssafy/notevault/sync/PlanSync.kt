package com.ssafy.notevault.sync

import com.ssafy.notevault.github.RemoteEntry

/** blob API 의 파일당 제한. 초과 파일은 건너뛴다. */
const val MAX_BLOB_BYTES: Long = 100L * 1024 * 1024

/** 로컬 files 테이블의 한 행. */
data class LocalFile(val path: String, val blobSha: String)

/** planSync 의 출력. 무엇을 받고 무엇을 지울지. */
data class SyncPlan(val download: List<RemoteEntry>, val delete: List<String>)

/**
 * 점(.)으로 시작하는 경로 세그먼트가 있으면 제외 대상이다.
 * .obsidian/ .claude/ .agents/ .github/ 등을 걸러낸다. `Dev/v1.2/note.md` 는 통과한다.
 */
fun isDotPath(path: String): Boolean = path.split('/').any { it.startsWith('.') }

/**
 * 경로가 구독 폴더 하위에 있는지 검사한다. 빈 문자열 구독은 볼트 전체다.
 * 폴더 경계를 확인하므로 `Dev` 구독이 `Development/x.md` 를 포함하지 않는다.
 */
fun isSubscribed(path: String, subscriptions: Set<String>): Boolean {
    if ("" in subscriptions) return true
    val segments = path.split('/')
    // 파일 자신을 뺀 조상 경로만 검사한다: a/b/c.md → "a/b", "a"
    return (segments.size - 1 downTo 1).any { depth ->
        segments.subList(0, depth).joinToString("/") in subscriptions
    }
}

/**
 * 동기화의 중심 함수. 순수 함수라 네트워크·파일시스템과 무관하다.
 *
 * 입력 (원격 트리, 로컬 파일 목록, 구독 목록) → 출력 (다운로드 목록, 삭제 목록)
 */
fun planSync(remote: List<RemoteEntry>, local: List<LocalFile>, subscriptions: Collection<String>): SyncPlan {
    val subs = subscriptions.toSet()
    val localShaByPath = local.associate { it.path to it.blobSha }

    // 구독 대상으로 남는 원격 파일. 나머지 로컬 파일은 삭제 대상이 된다.
    val wanted = remote.filter { entry ->
        !isDotPath(entry.path) && isSubscribed(entry.path, subs) && entry.size <= MAX_BLOB_BYTES
    }
    val keep = wanted.mapTo(HashSet()) { it.path }

    return SyncPlan(
        // 로컬에 없음(null) → 다운로드 / SHA 다름 → 재다운로드 / SHA 같음 → 스킵
        download = wanted.filter { localShaByPath[it.path] != it.sha },
        // 원격에서 사라졌거나 구독이 해제된 로컬 파일
        delete = local.filter { it.path !in keep }.map { it.path },
    )
}
