package com.videostudio.app

import android.app.Application
import com.videostudio.app.data.ProjectStore
import com.videostudio.app.data.Settings
import com.videostudio.app.upload.PostAll
import com.videostudio.app.work.Jobs
import java.io.File

class App : Application() {
    lateinit var settings: Settings
    lateinit var store: ProjectStore
    lateinit var jobs: Jobs
    lateinit var postAll: PostAll

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        store = ProjectStore(File(filesDir, "projects"))
        jobs = Jobs(this, store, settings)
        postAll = PostAll(store)
    }
}
