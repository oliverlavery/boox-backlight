package dev.shadow.booxbacklight

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LearnService.start(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 120, 60, 60)
            addView(TextView(this@MainActivity).apply {
                text = "BacklightLearn: observation service started.\nLogs: files/observations.jsonl"
                textSize = 16f
            })
        })
    }
}
