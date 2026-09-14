package com.maxim.healthconnectsleep

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setPadding(32, 32, 32, 32)
            textSize = 18f
            text = "This diagnostic app reads Sleep, Heart Rate and SpO2 from Health Connect. It does not upload data in this test version."
        })
    }
}
