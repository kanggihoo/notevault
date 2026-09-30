package com.ssafy.notevault.vault

/** 서랍 트리의 노드. 폴더면 [children] 이 있고, 파일이면 비어 있다. */
data class LocalNode(val path: String, val name: String, val isFolder: Boolean, val children: List<LocalNode>)

/** 화면에 펼쳐 보일 한 줄. [depth] 만큼 들여 쓴다. */
data class LocalRow(val node: LocalNode, val depth: Int)

enum class FileKind { Markdown, Html, Image, Text, Other }

/** 받은 파일 경로 목록 → 트리. 폴더를 파일보다 먼저, 각각 이름순으로 둔다. 루트(path = "")는 볼트 자체다. */
fun buildLocalTree(paths: List<String>): LocalNode {
    class Builder(val path: String, val name: String) {
        val folders = mutableMapOf<String, Builder>()
        val files = mutableListOf<LocalNode>()

        fun build(): LocalNode = LocalNode(
            path, name, isFolder = true,
            children = folders.values.sortedBy { it.name.lowercase() }.map { it.build() } +
                files.sortedBy { it.name.lowercase() },
        )
    }

    val root = Builder("", "")
    for (path in paths) {
        val segments = path.split('/')
        var node = root
        for (name in segments.dropLast(1)) {
            node = node.folders.getOrPut(name) { Builder(if (node.path.isEmpty()) name else "${node.path}/$name", name) }
        }
        node.files += LocalNode(path, segments.last(), isFolder = false, children = emptyList())
    }
    return root.build()
}

/** 루트의 자식부터 depth 0 으로 보여준다. 펼친 폴더만 자식을 이어 붙인다. */
fun visibleLocalRows(root: LocalNode, expanded: Set<String>): List<LocalRow> = buildList {
    fun visit(node: LocalNode, depth: Int) {
        add(LocalRow(node, depth))
        if (node.isFolder && node.path in expanded) node.children.forEach { visit(it, depth + 1) }
    }
    root.children.forEach { visit(it, 0) }
}

/**
 * 파일 이름 검색 (경로·본문은 보지 않는다). 공백으로 나눈 단어가 모두 들어 있어야 한다.
 * 정렬: 이름이 검색어와 같음 → 검색어로 시작 → 포함, 같은 순위면 짧은 이름이 먼저.
 */
fun searchByName(paths: List<String>, query: String, limit: Int = 100): List<String> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val whole = words.joinToString(" ")

    return paths
        .map { it to displayName(it).lowercase() }
        .filter { (_, name) -> words.all { it in name } }
        .sortedWith(
            compareBy<Pair<String, String>>(
                { (_, name) -> if (name == whole) 0 else if (name.startsWith(whole)) 1 else 2 },
                { (_, name) -> name.length },
                { (path, _) -> path },
            ),
        )
        .take(limit)
        .map { it.first }
}

/** 노트(.md)는 확장자를 빼고 보여준다 — Obsidian 과 같다. */
fun displayName(path: String): String {
    val name = path.substringAfterLast('/')
    return if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name
}

fun fileKind(path: String): FileKind = when (path.substringAfterLast('.', "").lowercase()) {
    "md", "markdown" -> FileKind.Markdown
    "html", "htm" -> FileKind.Html
    "png", "jpg", "jpeg", "gif", "webp", "bmp" -> FileKind.Image
    "txt", "json", "yaml", "yml", "toml", "csv", "xml", "css", "js", "ts",
    "py", "java", "kt", "go", "rs", "sh", "sql", "properties" -> FileKind.Text
    else -> FileKind.Other
}
