package com.ssafy.notevault.vault

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Obsidian 식 링크 해석. RN 판은 `![[Pasted image.png]]` 를 노트 폴더에서만 찾아
 * attachments/ 에 있는 이미지가 "이미지 없음" 으로 나왔다 — 그 문제를 여기서 고친다.
 */
class LinkResolverTest {

    private val paths = listOf(
        "spring/AOP/Advice.md",
        "spring/AOP/attachments/Pasted image 1.png",
        "spring/빈 생명주기.md",
        "spring/attachments/diagram.png",
        "springSecurity/Filter.md",
        "springSecurity/attachments/diagram.png",
        "알고리즘/CSES/Dashboard.md",
        "README.md",
    )

    // ── 위키링크 [[...]] ───────────────────────────────────────

    @Test
    fun `이름만 있으면 볼트 어디에 있든 찾는다 — 확장자 없는 건 노트`() {
        assertEquals("spring/AOP/Advice.md", resolveWikiTarget("Advice", fromNote = "README.md", paths))
    }

    @Test
    fun `대소문자는 가리지 않는다`() {
        assertEquals("spring/AOP/Advice.md", resolveWikiTarget("advice", fromNote = "README.md", paths))
    }

    @Test
    fun `제목·블록 참조는 떼고 찾는다`() {
        assertEquals("spring/빈 생명주기.md", resolveWikiTarget("빈 생명주기#초기화 콜백", "README.md", paths))
        assertEquals("spring/빈 생명주기.md", resolveWikiTarget("빈 생명주기#^abc123", "README.md", paths))
    }

    @Test
    fun `경로가 있으면 그 경로를 쓴다`() {
        assertEquals("알고리즘/CSES/Dashboard.md", resolveWikiTarget("알고리즘/CSES/Dashboard", "README.md", paths))
    }

    @Test
    fun `같은 이름이 여럿이면 지금 노트와 가까운 쪽을 고른다`() {
        assertEquals("spring/attachments/diagram.png", resolveWikiTarget("diagram.png", "spring/빈 생명주기.md", paths))
        assertEquals("springSecurity/attachments/diagram.png", resolveWikiTarget("diagram.png", "springSecurity/Filter.md", paths))
    }

    @Test
    fun `받지 않은 파일이면 null`() {
        assertNull(resolveWikiTarget("없는 노트", "README.md", paths))
    }

    // ── ![[이미지]] ─────────────────────────────────────────────

    @Test
    fun `노트 속 이미지 임베드를 볼트 전체에서 찾는다 — attachments 폴더 포함`() {
        val markdown = """
            # AOP
            ![[Pasted image 1.png|718]]
            ![[diagram.png]]
            ![[없는 그림.png]]
            [[Advice]] 는 이미지가 아니라 무시
            ![](상대경로.png) 는 표준 문법이라 무시
        """.trimIndent()

        assertEquals(
            mapOf(
                "Pasted image 1.png" to "spring/AOP/attachments/Pasted image 1.png",
                "diagram.png" to "spring/attachments/diagram.png",
            ),
            resolveEmbeddedImages(markdown, fromNote = "spring/AOP/Advice.md", paths),
        )
    }

    // ── 상대 링크 [x](../a.md) ──────────────────────────────────

    @Test
    fun `상대 링크는 노트 폴더 기준으로 푼다`() {
        assertEquals("spring/빈 생명주기.md", resolveRelativeLink("../빈 생명주기.md", fromNote = "spring/AOP/Advice.md", paths))
        assertEquals("spring/AOP/Advice.md", resolveRelativeLink("./Advice.md", fromNote = "spring/AOP/x.md", paths))
    }

    @Test
    fun `확장자가 없으면 md 를 붙여 본다, 제목 조각은 뗀다`() {
        assertEquals("spring/빈 생명주기.md", resolveRelativeLink("../빈 생명주기#초기화", "spring/AOP/Advice.md", paths))
    }

    @Test
    fun `볼트 밖을 가리키거나 없으면 null`() {
        assertNull(resolveRelativeLink("../../../etc/passwd", "spring/AOP/Advice.md", paths))
        assertNull(resolveRelativeLink("없음.md", "README.md", paths))
    }
}
