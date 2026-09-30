package com.ssafy.notevault.sync

import com.ssafy.notevault.github.RemoteEntry

/** 구독 화면이 보여주는 폴더 하나. 개수·용량은 하위 폴더까지 합산한 값이다. */
data class FolderNode(
    val path: String,
    val name: String,
    val children: List<FolderNode>,
    val fileCount: Int,
    val noteCount: Int,
    val totalBytes: Long,
)

/** 원격 트리(파일 목록) → 폴더 트리. 루트(path = "")는 볼트 전체다. 점 경로는 동기화 대상이 아니라 뺀다. */
fun buildFolderTree(entries: List<RemoteEntry>): FolderNode {
    // 만드는 동안만 쓰는 가변 노드. 다 만든 뒤 불변 FolderNode 로 바꾼다.
    class Builder(val path: String, val name: String) {
        val children = sortedMapOf<String, Builder>()
        var fileCount = 0
        var noteCount = 0
        var totalBytes = 0L

        fun add(entry: RemoteEntry) {
            fileCount += 1
            if (entry.path.endsWith(".md", ignoreCase = true)) noteCount += 1
            totalBytes += entry.size
        }

        fun build(): FolderNode =
            FolderNode(path, name, children.values.map { it.build() }, fileCount, noteCount, totalBytes)
    }

    val root = Builder("", "")
    for (entry in entries) {
        if (isDotPath(entry.path)) continue
        root.add(entry)
        var node = root
        // 파일 자신을 뺀 폴더 세그먼트를 따라 내려가며 각 조상에 합산한다.
        for (name in entry.path.split('/').dropLast(1)) {
            node = node.children.getOrPut(name) {
                Builder(if (node.path.isEmpty()) name else "${node.path}/$name", name)
            }
            node.add(entry)
        }
    }
    return root.build()
}

/** 선택된 폴더에서 이미 조상이 선택된 것을 뺀다. "" (볼트 전체)가 있으면 그것만 남는다. */
fun normalizeSubscriptions(selected: Set<String>): Set<String> =
    selected.filterTo(sortedSetOf()) { path -> selected.none { it != path && isAncestor(it, path) } }

/** [path] 가 선택된 폴더 자신이거나 그 하위인가. 화면에서 체크 표시에 쓴다. */
fun isCovered(path: String, selected: Set<String>): Boolean =
    selected.any { it == path || isAncestor(it, path) }

/** 폴더 경계를 지킨다 — "spring" 은 "springSecurity" 의 조상이 아니다. */
private fun isAncestor(ancestor: String, path: String): Boolean =
    ancestor.isEmpty() || path.startsWith("$ancestor/")
