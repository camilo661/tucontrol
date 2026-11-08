<p align="center"><img src="app/src/main/res/drawable-nodpi/tucontrol_logo.png" width="160" alt="TuControl"></p>

# TuControl

**Control remoto universal por voz para Wear OS.** Habla con tu reloj y TuControl controla tu televisor por Wi‑Fi: *"sube el volumen 5"*, *"abre Netflix"*, *"apaga la tele"*.

The UI is in Spanish; source code, identifiers and comments are in English.

---

## Descarga e instalación en el reloj

**Descarga directa:** [TuControl.apk](https://github.com/camilo661/tucontrol/releases/latest/download/TuControl.apk) · [todas las versiones](https://github.com/camilo661/tucontrol/releases)

Requisitos: reloj con **Wear OS 3 o superior** (Galaxy Watch 4 o posterior, Pixel Watch, TicWatch…) y reloj y TV en la **misma red Wi‑Fi**.

### 1. Activa el modo desarrollador en el reloj
1. En el reloj abre **Ajustes → Sistema → Información** (Samsung: **Ajustes → Información del reloj → Información de software**).
2. Toca **Número de compilación** (Samsung: **Versión de software**) **7 veces**, hasta que diga que eres desarrollador.
3. Vuelve a Ajustes, entra a **Opciones para desarrolladores** y activa **Depuración ADB** y **Depuración inalámbrica** (o "Depurar por Wi‑Fi").

### 2. Instala desde una computadora (Windows, Mac o Linux)
1. Descarga [Android SDK Platform‑Tools](https://developer.android.com/tools/releases/platform-tools) y descomprímelo.
2. Descarga **TuControl.apk** con el enlace de arriba y ponlo en la misma carpeta.
3. En el reloj, entra a **Depuración inalámbrica → Vincular nuevo dispositivo**: verás una IP, un puerto y un código.
4. En la computadora abre una terminal en esa carpeta y escribe (con los datos de tu reloj):
   ```bash
   adb pair 192.168.1.50:37123        # IP:puerto de "Vincular"; escribe el código cuando lo pida
   adb connect 192.168.1.50:41234     # IP:puerto que muestra "Depuración inalámbrica"
   adb install TuControl.apk
   ```
5. Acepta en el reloj si pregunta **"¿Permitir depuración?"**. Al terminar verás **Success** y TuControl aparecerá en la lista de apps.

> **Sin computadora:** desde un teléfono Android puedes usar la app **Wear Installer 2** (Play Store): descarga el APK en el teléfono, escribe la IP del reloj y pulsa instalar. El reloj también necesita la depuración inalámbrica activada.

### 3. Primer uso
1. Abre **TuControl**: verás la guía de bienvenida.
2. Entra a **Televisores**, elige tu TV y vincúlala (acepta en el TV o escribe el código que aparece).
3. Toca el micrófono y habla. En **Android TV** activa también **Depuración por USB** en el televisor (Ajustes → Sistema → Opciones para desarrolladores) y acepta el aviso la primera vez: así la IA puede ver la pantalla y hacer cualquier petición.

Cuando termines puedes desactivar la depuración del reloj; la app sigue instalada.

## Features

| | |
|---|---|
| 🎙️ **Voice, free** | Tap and speak: words appear live and the request runs when you stop talking. Uses the watch's speech service, or an offline Spanish model (Vosk, downloaded once) on watches without one. |
| 👁️ **Screen-aware agent** | With network debugging enabled on an Android TV (one-time approval on the TV), TuControl reads the screen and acts like a person with the remote: opens any installed app, jumps to any settings page and picks items by name, scrolling lists when needed — *"conéctate al Kalley por bluetooth"*, *"pon el modo deportes"*. |
| 🗣️ **TV assistant relay** | On Android / Google TV, anything the planner cannot do on its own (opening sideloaded apps, "prende el bluetooth", any other request) is handed, in the user's own voice, to the TV's Google Assistant — exactly like the microphone button on the TV remote. |
| 🧠 **Free step planner** | The built-in assistant (offline, no API key, no cost) splits a request into steps and runs them on its own, showing progress: *"entra a YouTube, busca Despacito y ponla"* → open YouTube → search → play the first result. Optionally, Claude (`claude-opus-5-5`, paid API key) can complement it for free-form conversation. |
| 🧭 **Beyond buttons** | Tune channels by number, search content (YouTube, Netflix on Android TV; Roku universal search), open 12 streaming apps, media and menu keys. |
| 📡 **Automatic discovery** | Finds TVs on the same Wi‑Fi network using SSDP/UPnP, DIAL and mDNS, plus a direct subnet sweep for routers that block multicast. TVs can also be added by IP address. |
| 📺 **Multi-brand** | Samsung (Tizen), LG (webOS), Sony (Bravia), Roku, and Android TV / Google TV (Sony, TCL, Hisense, Philips, Xiaomi, Chromecast with Google TV…). |
| 🎮 **Full remote** | Circular D‑pad, volume and channel rockers, media controls. Turn the crown/bezel to change volume. |
| 🔐 **One-time pairing** | TV-side approval (Samsung, LG) or on-screen PIN (Android TV); credentials are remembered. |
| ⌚ **Built for the wrist** | Material 3 Expressive for Wear OS, round-screen layouts, haptic feedback, swipe-to-dismiss navigation. |

## Supported TVs

| Platform | Protocol | Pairing |
|---|---|---|
| Android TV / Google TV | Android TV Remote v2 (TLS, ports 6466/6467) | 6-character code shown on the TV |
| Samsung Tizen (2016+) | WebSocket `samsung.remote.control` (8002/8001) | Accept the prompt on the TV |
| LG webOS | SSAP WebSocket (3001/3000) | Accept the prompt on the TV |
| Roku | External Control Protocol (HTTP 8060) | None ("Control by mobile apps" must be enabled) |
| Sony Bravia (non-Google TV) | IRCC-IP (HTTP) | Pre-shared key, configured in *Ajustes* |

> TVs without a network control API (older non-smart TVs) cannot be controlled over Wi‑Fi. Turning a TV **on** only works when the TV keeps its network active in standby.

## Architecture

Clean architecture in a single module, organized by layer:

```
com.tucontrol
├── domain/            Pure Kotlin: models (TvDevice, RemoteKey, CommandPlan…) and contracts
├── data/
│   ├── network/       Wi‑Fi binding (Wear OS defaults to Bluetooth), LAN HTTP & WebSocket clients
│   ├── discovery/     SSDP + mDNS discovery, UPnP description parsing, brand classification
│   ├── tv/            One TvController per protocol (roku/, samsung/, lg/, sony/, androidtv/)
│   ├── ai/            LocalCommandInterpreter, ClaudeCommandInterpreter, HybridCommandInterpreter
│   ├── storage/       DataStore persistence for devices, pairing credentials and settings
│   └── session/       RemoteSession: owns the active connection and paces command execution
├── di/                AppContainer (manual dependency graph)
└── presentation/      Compose for Wear OS: theme, components, screens + ViewModels, navigation
```

**Voice pipeline**

```
Speech (system recognizer, es) ─► HybridCommandInterpreter
                                     ├─ fully understood offline? ─► execute instantly
                                     └─ otherwise ─► Claude (structured output) ─► CommandPlan
CommandPlan ─► RemoteSession ─► TvController (brand protocol) ─► TV
```

## Getting started

Requirements: Android Studio (latest), JDK 17+, a Wear OS 3+ watch (API 30+) on the same Wi‑Fi network as the TV.

1. Clone the repository and open it in Android Studio.
2. *(Optional, paid)* to let Claude complement the free built-in assistant, create `secrets.properties` in the project root:
   ```properties
   ANTHROPIC_API_KEY=sk-ant-...
   ```
   The file is git-ignored. Without a key, TuControl uses only its free offline assistant.
3. Run the `app` configuration on a Wear OS device or emulator.

> **Emulator:** the Wear OS emulator has no Wi‑Fi and sits behind NAT, so multicast discovery cannot see the LAN. Debug builds look for TVs from the development machine at build time and the emulator probes those addresses, so real TVs on your network still show up. If a TV was off when you built, rebuild.

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:testDebugUnitTest    # unit tests
```

> **Production note:** a key bundled in an APK can be extracted. For a public release, route Claude requests through your own backend and keep the API key server-side.

### Testing against a real TV

Debug builds include a test hook that runs a phrase exactly as if it had been spoken (planner + execution), and log what the TV confirms (foreground app, volume):

```bash
adb shell am broadcast -a com.tucontrol.debug.COMMAND -p com.tucontrol.app --es text "'sube el volumen 2'"
adb logcat -s TuControlTest AndroidTv
```

### Screen-aware agent (Android TV)

On the TV: *Settings → System → About* → select *Android TV OS build* 7 times, then *Developer options → USB debugging* on. The first time the watch connects, accept *"Allow debugging?"* with *Always allow*. Without it, TuControl falls back to the TV's Google Assistant and fixed menu paths.

## Usage

1. Open **Televisores**: TVs on your network appear automatically. Tap one to link it.
2. Follow the on-screen step (accept on the TV or type the code).
3. Back on **Inicio**, tap the microphone and speak. Examples:
   - "Sube el volumen 5" · "Baja el volumen" · "Silencio"
   - "Siguiente canal" · "Baja dos canales"
   - "Abre YouTube y luego sube el volumen"
   - "Pausa" · "Adelanta" · "Atrás" · "Inicio"
   - "Pon el canal 7" · "Busca Stranger Things en Netflix" · "Siguiente capítulo" · "Subtítulos"
   - "Abre ajustes y prende el bluetooth" · "Sube el brillo" · "Cambia el modo de imagen" · "Abre Xuper TV"
4. Open **Control** for the full remote; swipe between pages and turn the crown for volume.

## Author

Created by **Camilo Andrés Castro Acosta** ([@camilo661](https://github.com/camilo661)).

## License

Copyright © 2026 Camilo Andrés Castro Acosta. All rights reserved.
