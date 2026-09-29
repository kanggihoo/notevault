package com.ssafy.notevault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ssafy.notevault.ui.NoteVaultNavHost
import com.ssafy.notevault.ui.NoteVaultTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NoteVaultApp).container
        setContent {
            NoteVaultTheme {
                NoteVaultNavHost(container)
            }
        }
    }
}
