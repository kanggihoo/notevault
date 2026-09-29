package com.ssafy.notevault

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 1단계 자리표시자. 화면은 3단계(Compose)에서 만든다.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "NoteVault (Kotlin) — 1단계" })
    }
}
