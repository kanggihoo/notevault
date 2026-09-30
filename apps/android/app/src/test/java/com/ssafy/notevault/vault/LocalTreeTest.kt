package com.ssafy.notevault.vault

import org.junit.Test
import kotlin.test.assertEquals

class LocalTreeTest {

    private val paths = listOf(
        "spring/AOP/Advice.md",
        "spring/빈 생명주기.md",
        "spring/attachments/Pasted image 1.png",
        "README.md",
        "알고리즘/CSES/Dashboard.md",
        "learning/lessons/kafka.html",
    )
    private val root = buildLocalTree(paths)

    private fun LocalNode.child(name: String) = children.single { it.name == name }

    // ── 트리 ───────────────────────────────────────────────────

    @Test
    fun `폴더가 파일보다 먼저, 각각 이름순이다`() {
        assertEquals(listOf("learning", "spring", "알고리즘", "README.md"), root.children.map { it.name })
        assertEquals(listOf("AOP", "attachments", "빈 생명주기.md"), root.child("spring").children.map { it.name })
    }

    @Test
    fun `노드는 전체 경로와 폴더 여부를 안다`() {
        val advice = root.child("spring").child("AOP").child("Advice.md")
        assertEquals("spring/AOP/Advice.md", advice.path)
        assertEquals(false, advice.isFolder)
        assertEquals(true, root.child("spring").isFolder)
    }

    @Test
    fun `펼친 폴더의 자식만 보인다`() {
        val collapsed = visibleLocalRows(root, expanded = emptySet()).map { it.node.path }
        assertEquals(listOf("learning", "spring", "알고리즘", "README.md"), collapsed)

        val rows = visibleLocalRows(root, expanded = setOf("spring"))
        assertEquals(
            listOf("learning", "spring", "spring/AOP", "spring/attachments", "spring/빈 생명주기.md", "알고리즘", "README.md"),
            rows.map { it.node.path },
        )
        assertEquals(1, rows.single { it.node.path == "spring/AOP" }.depth)
    }

    // ── 검색 ───────────────────────────────────────────────────

    @Test
    fun `파일 이름으로 찾고 대소문자를 가리지 않는다`() {
        assertEquals(listOf("spring/AOP/Advice.md"), searchByName(paths, "advice"))
    }

    @Test
    fun `경로가 아니라 파일 이름만 본다`() {
        assertEquals(emptyList(), searchByName(paths, "AOP")) // 폴더 이름은 매칭하지 않는다
    }

    @Test
    fun `여러 단어는 모두 포함해야 한다`() {
        assertEquals(listOf("spring/빈 생명주기.md"), searchByName(paths, "생명 빈"))
    }

    @Test
    fun `정확히 일치 → 앞부분 일치 → 포함 순으로 정렬한다`() {
        val candidates = listOf("a/kafka 정리.md", "b/카프카 kafka.md", "c/kafka.md")
        assertEquals(listOf("c/kafka.md", "a/kafka 정리.md", "b/카프카 kafka.md"), searchByName(candidates, "kafka"))
    }

    @Test
    fun `빈 검색어는 결과가 없다`() {
        assertEquals(emptyList(), searchByName(paths, "  "))
    }

    // ── 이름·종류 ──────────────────────────────────────────────

    @Test
    fun `노트는 확장자 없이 보여준다`() {
        assertEquals("빈 생명주기", displayName("spring/빈 생명주기.md"))
        assertEquals("kafka.html", displayName("learning/lessons/kafka.html"))
    }

    @Test
    fun `확장자로 파일 종류를 판별한다`() {
        assertEquals(FileKind.Markdown, fileKind("a/B.MD"))
        assertEquals(FileKind.Html, fileKind("x.html"))
        assertEquals(FileKind.Image, fileKind("attachments/Pasted image 1.png"))
        assertEquals(FileKind.Image, fileKind("a.webp"))
        assertEquals(FileKind.Text, fileKind("script.py"))
        assertEquals(FileKind.Other, fileKind("book.pdf"))
        assertEquals(FileKind.Other, fileKind("noext"))
    }
}
