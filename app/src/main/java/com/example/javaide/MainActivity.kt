package com.example.javaide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.example.javaide.ui.IDEScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm: IDEViewModel = ViewModelProvider(this)[IDEViewModel::class.java]
        setContent {
            val night by vm.nightMode
            MaterialTheme(
                colorScheme = if (night) darkColorScheme() else lightColorScheme()
            ) {
                IDEScreen(vm)
            }
        }
    }
}
