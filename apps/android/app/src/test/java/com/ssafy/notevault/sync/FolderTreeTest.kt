package com.ssafy.notevault.sync

import com.ssafy.notevault.github.RemoteEntry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderTreeTest {

    private fun r(path: String, size: Long = 10) = RemoteEntry(path, "sha-$path", size)

    private val tree = buildFolderTree(
        listOf(
            r("root.md"),
            r("spring/a.md", 100),
            r("spring/AOP/b.md", 200),
            r("spring/AOP/attachments/c.png", 1000),
            r("알고리즘/x.md"),
            r(".obsidian/app.json"), // 점 경로는 트리에 나오지 않는다
            r("알고리즘/.claude/skill.md"),
        ),
    )

    private fun FolderNode.child(name: String) = children.single { it.name == name }

    @Test
    fun `폴더만 노드가 되고 이름순으로 정렬된다`() {
        assertEquals(listOf("spring", "알고리즘"), tree.children.map { it.name })
        assertEquals(listOf("AOP"), tree.child("spring").children.map { it.name })
        assertEquals("spring/AOP", tree.child("spring").child("AOP").path)
    }

    @Test
    fun `개수와 용량은 하위 폴더까지 합산한다`() {
        val spring = tree.child("spring")
        assertEquals(3, spring.fileCount)
        assertEquals(2, spring.noteCount) // .md 만
        assertEquals(1300, spring.totalBytes)
    }

    @Test
    fun `루트는 볼트 전체이고 점 경로를 세지 않는다`() {
        assertEquals("", tree.path)
        assertEquals(5, tree.fileCount)
    }

    @Test
    fun `부모를 고르면 자식 선택은 정리된다`() {
        assertEquals(setOf("spring"), normalizeSubscriptions(setOf("spring", "spring/AOP", "spring/AOP/attachments")))
    }

    @Test
    fun `볼트 전체를 고르면 나머지는 필요 없다`() {
        assertEquals(setOf(""), normalizeSubscriptions(setOf("", "spring", "알고리즘")))
    }

    @Test
    fun `이름이 비슷한 형제 폴더는 정리하지 않는다`() {
        assertEquals(setOf("spring", "springSecurity"), normalizeSubscriptions(setOf("spring", "springSecurity")))
    }

    @Test
    fun `조상이 선택되면 덮인 것으로 본다`() {
        assertTrue(isCovered("spring/AOP", setOf("spring")))
        assertTrue(isCovered("spring", setOf("spring")))
        assertTrue(isCovered("spring", setOf("")))
        assertFalse(isCovered("springSecurity", setOf("spring")))
        assertFalse(isCovered("spring", setOf("spring/AOP")))
    }
}
