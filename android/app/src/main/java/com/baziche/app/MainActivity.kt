package com.baziche.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.baziche.app.ui.navigation.NavGraph
import com.baziche.app.ui.theme.BazicheTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BazicheApp).container
        setContent {
            BazicheTheme {
                NavGraph(container)
            }
        }
    }
}
