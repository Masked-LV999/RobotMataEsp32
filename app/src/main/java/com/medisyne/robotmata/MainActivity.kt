package com.medisyne.robotmata

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Tahap 1 Berhasil! Siap pasang kamera & deteksi."
            textSize = 20f
            setPadding(40, 40, 40, 40)
        }
        setContentView(tv)
    }
}
