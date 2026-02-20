package com.java2smali

import android.app.Application
import com.java2smali.ui.MainViewModel

class Java2SmaliApp : Application() {
    
    companion object {
        lateinit var instance: Java2SmaliApp
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
