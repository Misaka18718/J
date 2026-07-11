package com.example.javaide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import com.example.javaide.ui.IDEScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm: IDEViewModel = ViewModelProvider(this)[IDEViewModel::class.java]
        setContent {
            MaterialTheme {
                IDEScreen(vm)
            }
        }
    }
}
