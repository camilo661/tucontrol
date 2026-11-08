import com.android.build.api.variant.BuildConfigField
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Properties
import java.util.concurrent.Executors

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.tucontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tucontrol.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        val apiKey = secrets.getProperty("ANTHROPIC_API_KEY")
            ?: System.getenv("ANTHROPIC_API_KEY")
            ?: ""
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$apiKey\"")
        buildConfigField("String", "DEV_LAN_HOSTS", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/*.kotlin_module",
            )
        }
    }
}

/**
 * Hosts on the development machine's LAN that expose a TV control port
 * (e.g. "192.168.1.20,192.168.1.31").
 *
 * The Wear OS emulator has no Wi‑Fi and sits behind NAT: it cannot see LAN multicast, and
 * sweeping a whole subnet from inside it exhausts the emulator's NAT sockets. So debug
 * builds look for TVs here, on the host, and the emulator probes only those.
 * Re-evaluated on every build; a TV that was off at build time needs a rebuild.
 */
abstract class HostLanTvs : ValueSource<String, ValueSourceParameters.None> {
    private val tvPorts = listOf(6466, 8060, 8001, 8002, 3000)

    override fun obtain(): String {
        val ownAddresses = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
        val candidates = ownAddresses
            .map { it.substringBeforeLast('.') }
            .distinct()
            .flatMap { prefix -> (1..254).map { "$prefix.$it" } }
            .filterNot { it in ownAddresses }

        val pool = Executors.newFixedThreadPool(256)
        return try {
            candidates
                .flatMap { host -> tvPorts.map { port -> host to pool.submit<Boolean> { isOpen(host, port) } } }
                .filter { (_, open) -> open.get() }
                .map { it.first }
                .distinct()
                .joinToString(",")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 400) }
    }.isSuccess
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.buildConfigFields?.put(
            "DEV_LAN_HOSTS",
            providers.of(HostLanTvs::class) {}.map { hosts ->
                BuildConfigField("String", "\"$hosts\"", "LAN TVs the emulator can reach through NAT")
            },
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.input)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.wear.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.anthropic.java)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
