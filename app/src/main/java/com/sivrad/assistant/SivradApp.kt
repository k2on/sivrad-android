package com.sivrad.assistant

import android.app.Application
import com.sivrad.assistant.models.ModelCatalog
import com.sivrad.assistant.models.ModelDownloader
import com.sivrad.assistant.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide objects. Sivrad runs in a single process: the assistant
 * services, the overlay and the setup screen all share this one engine.
 */
class SivradApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: AppSettings
        private set
    lateinit var catalog: ModelCatalog
        private set
    lateinit var downloader: ModelDownloader
        private set
    lateinit var engine: AssistantEngine
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        catalog = ModelCatalog(this, settings)
        downloader = ModelDownloader(catalog, scope)
        engine = AssistantEngine(catalog, settings, scope)
    }
}

val android.content.Context.sivrad: SivradApp get() = applicationContext as SivradApp
