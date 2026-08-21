package com.dt.manager.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.dt.manager.R

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.title_about)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
