package com.ssafy.notevault.sync

import com.ssafy.notevault.github.RemoteEntry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RN 판 src/sync/planSync.test.ts 이식. */
class PlanSyncTest {

    private fun r(path: String, sha: String, size: Long = 100) = RemoteEntry(path, sha, size)
    private fun l(path: String, blobSha: String) = LocalFile(path, blobSha)
    private fun SyncPlan.downloadPaths() = download.map { it.path }

    // ── isDotPath ──────────────────────────────────────────────

    @Test
    fun `점으로 시작하는 세그먼트를 걸러낸다`() {
        assertTrue(isDotPath(".obsidian/app.json"))
        assertTrue(isDotPath("Dev/.claude/x.md"))
        assertTrue(isDotPath(".github/workflows/sync.yml"))
    }

    @Test
    fun `점이 세그먼트 시작이 아니면 통과시킨다`() {
        assertFalse(isDotPath("Dev/v1.2/note.md"))
        assertFalse(isDotPath("postgresql/01-shared_buffer.md"))
        assertFalse(isDotPath("기업조사/attachments/Pasted image 1.png"))
    }

    // ── isSubscribed ───────────────────────────────────────────

    @Test
    fun `부모 폴더를 구독하면 자식 전체가 포함된다`() {
        val subs = setOf("Dev")
        assertTrue(isSubscribed("Dev/K8s/pod.md", subs))
        assertTrue(isSubscribed("Dev/attachments/a.png", subs))
    }

    @Test
    fun `폴더 경계를 지킨다 (prefix 문자열 일치가 아니다)`() {
        assertFalse(isSubscribed("Development/x.md", setOf("Dev")))
    }

    @Test
    fun `구독하지 않은 폴더는 제외한다`() {
        assertFalse(isSubscribed("Archive/x.md", setOf("Dev")))
    }

    @Test
    fun `빈 문자열 구독은 볼트 전체다`() {
        assertTrue(isSubscribed("root.md", setOf("")))
        assertTrue(isSubscribed("a/b/c.md", setOf("")))
    }

    // ── planSync ───────────────────────────────────────────────

    @Test
    fun `신규 파일을 다운로드한다`() {
        val plan = planSync(listOf(r("Dev/a.md", "sha1")), emptyList(), listOf("Dev"))
        assertEquals(listOf("Dev/a.md"), plan.downloadPaths())
        assertEquals(emptyList(), plan.delete)
    }

    @Test
    fun `blob SHA 가 바뀌면 재다운로드한다`() {
        val plan = planSync(listOf(r("Dev/a.md", "new")), listOf(l("Dev/a.md", "old")), listOf("Dev"))
        assertEquals(listOf("Dev/a.md"), plan.downloadPaths())
    }

    @Test
    fun `blob SHA 가 같으면 스킵한다 — 증분의 핵심`() {
        val plan = planSync(listOf(r("Dev/a.md", "same")), listOf(l("Dev/a.md", "same")), listOf("Dev"))
        assertEquals(emptyList(), plan.download)
        assertEquals(emptyList(), plan.delete)
    }

    @Test
    fun `원격에서 삭제된 파일을 로컬에서 지운다`() {
        val plan = planSync(emptyList(), listOf(l("Dev/gone.md", "sha1")), listOf("Dev"))
        assertEquals(listOf("Dev/gone.md"), plan.delete)
    }

    @Test
    fun `구독 해제 시 해당 prefix 하위 전체를 지운다`() {
        val remote = listOf(r("Dev/a.md", "s1"), r("Archive/b.md", "s2"))
        val local = listOf(l("Dev/a.md", "s1"), l("Archive/b.md", "s2"))
        val plan = planSync(remote, local, listOf("Dev")) // Archive 구독 해제 상태
        assertEquals(listOf("Archive/b.md"), plan.delete)
        assertEquals(emptyList(), plan.download)
    }

    @Test
    fun `폴더 이름 변경을 삭제 + 신규로 처리한다`() {
        val plan = planSync(
            listOf(r("Dev/new/a.md", "same-content")),
            listOf(l("Dev/old/a.md", "same-content")),
            listOf("Dev"),
        )
        assertEquals(listOf("Dev/new/a.md"), plan.downloadPaths())
        assertEquals(listOf("Dev/old/a.md"), plan.delete)
    }

    @Test
    fun `중단 복구 — 47개 중 12개 완료 상태에서 35개만 요청한다`() {
        val remote = (0 until 47).map { r("Dev/f$it.md", "sha$it") }
        val local = remote.take(12).map { l(it.path, it.sha) }
        val plan = planSync(remote, local, listOf("Dev"))
        assertEquals(35, plan.download.size)
        assertEquals(emptyList(), plan.delete)
    }

    @Test
    fun `점 경로를 제외한다`() {
        val remote = listOf(r(".obsidian/app.json", "s1"), r("Dev/.claude/x.md", "s2"), r("Dev/note.md", "s3"))
        val plan = planSync(remote, emptyList(), listOf("Dev", ".obsidian"))
        assertEquals(listOf("Dev/note.md"), plan.downloadPaths())
    }

    @Test
    fun `이미 받은 점 경로 파일은 삭제 대상이 된다`() {
        val plan = planSync(listOf(r(".obsidian/app.json", "s1")), listOf(l(".obsidian/app.json", "s1")), listOf(""))
        assertEquals(listOf(".obsidian/app.json"), plan.delete)
    }

    @Test
    fun `blob API 제한(100MB) 초과 파일을 건너뛴다`() {
        val remote = listOf(r("Dev/huge.zip", "s1", MAX_BLOB_BYTES + 1), r("Dev/ok.md", "s2", MAX_BLOB_BYTES))
        val plan = planSync(remote, emptyList(), listOf("Dev"))
        assertEquals(listOf("Dev/ok.md"), plan.downloadPaths())
    }

    @Test
    fun `구독 폴더의 attachments 가 자동으로 포함된다`() {
        val remote = listOf(r("기업조사/note.md", "s1"), r("기업조사/attachments/Pasted image 1.png", "s2"))
        val plan = planSync(remote, emptyList(), listOf("기업조사"))
        assertEquals(2, plan.download.size)
    }
}
