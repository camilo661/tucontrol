import com.android.build.api.variant.BuildConfigField
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Properties

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
        buildConfigField("String", "DEV_LAN_SUBNETS", "\"\"")
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
 * The development machine's private IPv4 /24 prefixes (e.g. "192.168.1"). The Wear OS
 * emulator sits behind NAT and cannot see LAN multicast, but it can reach these hosts,
 * so debug builds sweep them to find real TVs. Re-evaluated on every build.
 */
abstract class HostLanSubnets : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress?.substringBeforeLast('.') }
        .distinct()
        .joinToString(",")
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.buildConfigFields?.put(
            "DEV_LAN_SUBNETS",
            providers.of(HostLanSubnets::class) {}.map { subnets ->
                BuildConfigField("String", "\"$subnets\"", "Host LAN subnets reachable from the emulator")
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
