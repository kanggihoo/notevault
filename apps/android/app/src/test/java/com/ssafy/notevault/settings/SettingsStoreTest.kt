package com.ssafy.notevault.settings

import com.ssafy.notevault.github.RepoRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Android Keystore 는 JVM 에 없으므로 암호화는 가짜(바이트 뒤집기)로 바꿔 끼운다
 * — 그래서 TokenCipher 를 인터페이스로 뺐다. DataStore 도 메모리 버전을 쓴다.
 */
class SettingsStoreTest {

    private val fakeCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(data: ByteArray) = data.reversedArray()
    }

    private val dataStore = InMemoryDataStore()
    private val store = SettingsStore(dataStore, fakeCipher)

    @Test
    fun `토큰은 없으면 null, 저장하면 돌려받는다`() = runTest {
        assertNull(store.token())
        store.saveToken("github_pat_ABCDEFGH1234")
        assertEquals("github_pat_ABCDEFGH1234", store.token())
    }

    @Test
    fun `저장되는 값에는 평문 토큰이 없다`() = runTest {
        store.saveToken("github_pat_SECRET")
        val persisted = dataStore.data.value.asMap().values.joinToString()
        assertFalse("github_pat_SECRET" in persisted, persisted)
    }

    @Test
    fun `화면에는 끝 4자리 힌트만 노출한다`() = runTest {
        store.saveToken("github_pat_ABCDEFGH1234")
        assertEquals("1234", store.settings.first().tokenHint)
    }

    @Test
    fun `토큰을 지우면 힌트와 만료 정보도 지운다`() = runTest {
        store.saveToken("github_pat_x")
        store.saveTokenExpiresAt(Instant.parse("2026-10-29T12:00:00Z"))
        store.clearToken()

        val settings = store.settings.first()
        assertNull(store.token())
        assertNull(settings.tokenHint)
        assertNull(settings.tokenExpiresAt)
    }

    @Test
    fun `저장소 설정과 만료 시각을 저장한다`() = runTest {
        assertNull(store.settings.first().repo)
        store.saveRepo(RepoRef("kanggihoo", "obsidian", "main"))
        store.saveTokenExpiresAt(Instant.parse("2026-10-29T12:00:00Z"))

        val settings = store.settings.first()
        assertEquals(RepoRef("kanggihoo", "obsidian", "main"), settings.repo)
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), settings.tokenExpiresAt)
    }

    @Test
    fun `복호화에 실패하면 토큰이 없는 것으로 본다 — 재설치로 키가 사라진 경우`() = runTest {
        store.saveToken("github_pat_x")
        val broken = SettingsStore(
            InMemoryDataStore(),
            object : TokenCipher {
                override fun encrypt(plain: ByteArray) = plain
                override fun decrypt(data: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException()
            },
        )
        broken.saveToken("anything")
        assertNull(broken.token())
    }
}
