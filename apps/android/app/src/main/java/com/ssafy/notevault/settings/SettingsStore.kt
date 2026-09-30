package com.ssafy.notevault.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ssafy.notevault.github.RepoRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.GeneralSecurityException
import java.time.Instant
import java.util.Base64

/** 토큰 암호화. 앱에서는 [KeystoreTokenCipher], 테스트에서는 가짜를 쓴다. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/** 화면에 보여줄 설정. 토큰 원문은 담지 않는다 — 끝 4자리 힌트만. */
data class Settings(val tokenHint: String?, val tokenExpiresAt: Instant?, val repo: RepoRef?)

/**
 * 앱 설정 저장소. DataStore 는 SharedPreferences 의 후계로, 파일 IO 를 코루틴으로 비동기 처리한다.
 * 동기화 상태(last_commit_sha 등)는 Room meta 테이블에, 사용자가 입력하는 설정은 여기에 둔다.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>, private val cipher: TokenCipher) {

    val settings: Flow<Settings> = dataStore.data.map { prefs ->
        val owner = prefs[OWNER]
        val repo = prefs[REPO]
        Settings(
            tokenHint = prefs[TOKEN_HINT],
            tokenExpiresAt = prefs[TOKEN_EXPIRES_AT]?.let(Instant::ofEpochSecond),
            repo = if (owner != null && repo != null) RepoRef(owner, repo, prefs[BRANCH] ?: "main") else null,
        )
    }

    /** 복호화할 수 없으면(재설치·백업 복원으로 Keystore 키가 바뀐 경우) 토큰이 없는 것으로 본다. */
    suspend fun token(): String? {
        val encoded = dataStore.data.first()[TOKEN] ?: return null
        return try {
            cipher.decrypt(Base64.getDecoder().decode(encoded)).decodeToString()
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    suspend fun saveToken(token: String) {
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(token.encodeToByteArray()))
        dataStore.edit {
            it[TOKEN] = encrypted
            it[TOKEN_HINT] = token.takeLast(4)
            it.remove(TOKEN_EXPIRES_AT) // 새 토큰의 만료 시각은 다음 응답이 알려준다
        }
    }

    suspend fun clearToken() {
        dataStore.edit {
            it.remove(TOKEN)
            it.remove(TOKEN_HINT)
            it.remove(TOKEN_EXPIRES_AT)
        }
    }

    suspend fun saveRepo(repo: RepoRef) {
        dataStore.edit {
            it[OWNER] = repo.owner
            it[REPO] = repo.repo
            it[BRANCH] = repo.branch
        }
    }

    suspend fun saveTokenExpiresAt(at: Instant?) {
        dataStore.edit { if (at == null) it.remove(TOKEN_EXPIRES_AT) else it[TOKEN_EXPIRES_AT] = at.epochSecond }
    }

    /** 모든 설정을 지운다. E2E 테스트가 매번 빈 상태에서 시작하려고 쓴다. */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val TOKEN = stringPreferencesKey("token_encrypted")
        val TOKEN_HINT = stringPreferencesKey("token_hint")
        val TOKEN_EXPIRES_AT = longPreferencesKey("token_expires_at")
        val OWNER = stringPreferencesKey("owner")
        val REPO = stringPreferencesKey("repo")
        val BRANCH = stringPreferencesKey("branch")
    }
}
