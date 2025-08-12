package com.youngfeng.android.assistant.about

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import com.youngfeng.android.assistant.BuildConfig
import com.youngfeng.android.assistant.R

class AboutActivity : AppCompatActivity() {
    private val mVersionText by lazy { findViewById<AppCompatTextView>(R.id.text_version) }
    private val mCommitIdText by lazy { findViewById<AppCompatTextView>(R.id.text_commit_id) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        mVersionText.text = BuildConfig.VERSION_NAME
        mCommitIdText.text = "Commit: ${BuildConfig.GIT_COMMIT_ID}"
    }
}
