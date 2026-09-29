package com.ssafy.notevault

import android.app.Application

/** 프로세스당 하나. 화면들은 여기서 [container] 를 꺼내 쓴다. */
class NoteVaultApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
