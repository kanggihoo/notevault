package com.ssafy.notevault.vault

/*
 * Obsidian 식 링크 해석. 모두 순수 함수 — 받은 파일 경로 목록(paths)만 보고 판단한다.
 * 경로는 볼트 루트 기준 상대 경로다 ("spring/AOP/Advice.md").
 */

private val IMAGE_EXT = Regex("""\.(png|jpe?g|gif|svg|webp|avif|bmp)$""", RegexOption.IGNORE_CASE)
private val EMBED = Regex("""!\[\[([^\]]+?)]]""")

/**
 * `[[대상]]`·`![[대상]]` 의 대상을 실제 파일로 푼다. 못 찾으면 null.
 *
 * 1) `#제목`·`#^블록` 은 뗀다. 확장자가 없으면 노트(.md)다.
 * 2) 볼트 루트 기준 경로로 정확히 있으면 그것.
 * 3) 아니면 이름(또는 경로 끝부분)이 같은 파일 중 지금 노트와 폴더를 가장 많이 공유하는 것.
 *    동률이면 짧은 경로 → 사전순. Obsidian 의 "가장 가까운 파일" 규칙과 같다.
 */
fun resolveWikiTarget(target: String, fromNote: String, paths: Collection<String>): String? {
    val name = target.substringBefore('#').trim()
    if (name.isEmpty()) return null
    val wanted = if (name.substringAfterLast('/').contains('.')) name else "$name.md"

    paths.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }

    val suffix = "/$wanted"
    val noteDir = fromNote.substringBeforeLast('/', "")
    return paths
        .filter { it.endsWith(suffix, ignoreCase = true) }
        .sortedWith(compareBy<String>({ -sharedFolderDepth(noteDir, it) }, { it.length }, { it }))
        .firstOrNull()
}

/** 노트 속 `![[이미지]]` 들을 실제 경로로 푼다. 렌더러가 img src 를 바꿀 때 쓴다. 못 찾은 것은 빠진다. */
fun resolveEmbeddedImages(markdown: String, fromNote: String, paths: Collection<String>): Map<String, String> =
    EMBED.findAll(markdown)
        .map { it.groupValues[1].substringBefore('|').trim() }
        .filter { IMAGE_EXT.containsMatchIn(it) }
        .distinct()
        .mapNotNull { target -> resolveWikiTarget(target, fromNote, paths)?.let { target to it } }
        .toMap()

/**
 * `[글자](../a.md)` 같은 표준 상대 링크를 노트 폴더 기준으로 푼다.
 * `#제목` 은 떼고, 확장자가 없으면 `.md` 를 붙여 본다. 볼트 밖을 가리키면 null.
 */
fun resolveRelativeLink(href: String, fromNote: String, paths: Collection<String>): String? {
    val clean = href.substringBefore('#').substringBefore('?').trim()
    if (clean.isEmpty()) return null
    val noteDir = fromNote.substringBeforeLast('/', "")
    val joined = if (clean.startsWith("/")) clean.drop(1) else listOf(noteDir, clean).filter { it.isNotEmpty() }.joinToString("/")
    val normalized = normalizePath(joined) ?: return null

    val candidates = listOf(normalized, "$normalized.md")
    return candidates.firstNotNullOfOrNull { candidate -> paths.firstOrNull { it == candidate } }
}

/** `a/./b/../c` → `a/c`. 루트 위로 올라가면 null. */
private fun normalizePath(path: String): String? {
    val out = ArrayDeque<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (out.isEmpty()) return null else out.removeLast()
            else -> out.addLast(segment)
        }
    }
    return out.joinToString("/")
}

/** 두 경로가 앞에서부터 공유하는 폴더 수. */
private fun sharedFolderDepth(noteDir: String, candidate: String): Int {
    val a = noteDir.split('/').filter { it.isNotEmpty() }
    val b = candidate.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
    return a.zip(b).takeWhile { (x, y) -> x == y }.count()
}
