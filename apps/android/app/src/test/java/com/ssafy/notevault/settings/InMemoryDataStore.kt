package com.ssafy.notevault.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 테스트용 메모리 DataStore.
 * 파일 DataStore 는 Windows 에서 "임시 파일 → 원본 덮어쓰기" 이름 변경이 막혀 두 번째 쓰기부터 실패한다
 * (Android 에선 생기지 않는다). 테스트가 개발 PC 운영체제에 좌우되지 않도록 메모리로 대체한다.
 */
class InMemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override val data: StateFlow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
