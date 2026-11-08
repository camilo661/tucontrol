package com.tucontrol.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.tucontrol.BuildConfig
import com.tucontrol.data.ai.ClaudeCommandInterpreter
import com.tucontrol.data.ai.HybridCommandInterpreter
import com.tucontrol.data.ai.LocalCommandInterpreter
import com.tucontrol.data.discovery.AndroidTvMdnsDiscovery
import com.tucontrol.data.discovery.CompositeTvDiscovery
import com.tucontrol.data.discovery.SsdpTvDiscovery
import com.tucontrol.data.discovery.SubnetScanDiscovery
import com.tucontrol.data.discovery.TvProber
import com.tucontrol.data.network.LocalHttpClients
import com.tucontrol.data.network.WifiNetworkProvider
import com.tucontrol.data.session.RemoteSession
import com.tucontrol.data.speech.DeviceVoiceRecognizer
import com.tucontrol.data.speech.SystemVoiceRecognizer
import com.tucontrol.data.speech.VoskModelStore
import com.tucontrol.data.speech.VoskVoiceRecognizer
import com.tucontrol.data.storage.DataStoreDeviceRepository
import com.tucontrol.data.storage.DataStoreSettingsRepository
import com.tucontrol.data.tv.TvControllerFactory
import com.tucontrol.data.tv.androidtv.AssistantPhrases
import com.tucontrol.domain.repository.DeviceRepository
import com.tucontrol.domain.repository.SettingsRepository
import com.tucontrol.domain.repository.TvDiscovery
import com.tucontrol.domain.repository.VoiceRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Context.dataStore by preferencesDataStore(name = "tucontrol")

/**
 * Manual dependency graph. The app is small enough that a DI framework would add build
 * time and indirection without real benefit; every dependency is wired here, once.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClients = LocalHttpClients()
    val wifi = WifiNetworkProvider(appContext, allowLanFallback = BuildConfig.DEBUG)
    val prober = TvProber(httpClients.plain)

    val deviceRepository: DeviceRepository = DataStoreDeviceRepository(appContext.dataStore)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(appContext.dataStore)

    val discovery: TvDiscovery = CompositeTvDiscovery(
        listOf(
            SsdpTvDiscovery(appContext, wifi, httpClients.plain),
            AndroidTvMdnsDiscovery(appContext, wifi),
            SubnetScanDiscovery(
                context = appContext,
                wifi = wifi,
                prober = prober,
                fallbackHosts = BuildConfig.DEV_LAN_HOSTS.split(',').filter { it.isNotBlank() },
            ),
        ),
    )

    val interpreter = HybridCommandInterpreter(
        local = LocalCommandInterpreter(),
        remote = BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }?.let(::ClaudeCommandInterpreter),
        settings = settingsRepository,
    )

    val voiceRecognizer: VoiceRecognizer = DeviceVoiceRecognizer(
        system = SystemVoiceRecognizer(appContext),
        offline = VoskVoiceRecognizer(VoskModelStore(appContext, httpClients.plain)),
    )

    val session = RemoteSession(
        factory = TvControllerFactory(httpClients, deviceRepository, settingsRepository, AssistantPhrases(appContext.assets)),
        devices = deviceRepository,
        wifi = wifi,
        scope = appScope,
    )
}
