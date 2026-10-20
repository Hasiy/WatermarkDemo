package com.example.watermarkdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.watermarkdemo.ui.MainScreen
import com.example.watermarkdemo.ui.MainViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface {
                    val viewModel: MainViewModel = viewModel()
                    MainScreen(viewModel)
                }
            }
        }
    }
}
