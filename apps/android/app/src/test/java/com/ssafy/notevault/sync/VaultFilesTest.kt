package com.ssafy.notevault.sync

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 순수 JVM 파일 IO 라 Robolectric 이 필요 없다. JUnit 이 테스트마다 임시 폴더를 만들고 지운다. */
class VaultFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val files by lazy { VaultFiles(tmp.root) }

    @Test
    fun `중간 폴더를 만들며 쓰고, 임시 파일을 남기지 않는다`() = runTest {
        files.writeAtomic("기업조사/attachments/Pasted image 1.png", byteArrayOf(1, 2, 3))

        val dir = tmp.root.resolve("기업조사/attachments")
        assertEquals(listOf("Pasted image 1.png"), dir.list()!!.toList())
        assertTrue(files.exists("기업조사/attachments/Pasted image 1.png"))
    }

    @Test
    fun `같은 경로에 다시 쓰면 덮어쓴다`() = runTest {
        files.writeAtomic("a.md", "old".encodeToByteArray())
        files.writeAtomic("a.md", "new".encodeToByteArray())
        assertEquals("new", files.readText("a.md"))
    }

    @Test
    fun `없는 파일 삭제는 조용히 넘어간다`() = runTest {
        files.writeAtomic("a.md", byteArrayOf(1))
        files.delete("a.md")
        files.delete("a.md")
        assertFalse(files.exists("a.md"))
    }

    @Test
    fun `볼트 밖으로 나가는 경로는 거부한다`() = runTest {
        assertFailsWith<IllegalArgumentException> { files.writeAtomic("../escape.md", byteArrayOf(1)) }
    }
}
