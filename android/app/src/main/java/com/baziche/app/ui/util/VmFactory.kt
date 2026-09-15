package com.baziche.app.ui.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

@Suppress("UNCHECKED_CAST")
fun <T : ViewModel> vmFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <M : ViewModel> create(modelClass: Class<M>): M = create() as M
    }
