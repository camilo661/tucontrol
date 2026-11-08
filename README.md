# TuControl

**Control remoto universal por voz para Wear OS.** Habla con tu reloj y TuControl controla tu televisor por Wi‑Fi: *"sube el volumen 5"*, *"abre Netflix"*, *"apaga la tele"*.

The UI is in Spanish; source code, identifiers and comments are in English.

---

## Features

| | |
|---|---|
| 🎙️ **Voice + AI** | Spanish speech recognition. Simple commands are parsed offline in milliseconds; free-form phrases are interpreted by Claude (`claude-opus-5-5`) with a strict JSON schema. |
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
2. *(Optional, enables the AI assistant)* create `secrets.properties` in the project root:
   ```properties
   ANTHROPIC_API_KEY=sk-ant-...
   ```
   The file is git-ignored. Without a key, TuControl still works with the offline command parser.
3. Run the `app` configuration on a Wear OS device or emulator.

> **Emulator:** the Wear OS emulator has no Wi‑Fi and sits behind NAT, so multicast discovery cannot see the LAN. Debug builds fall back to the emulator's network and sweep the development machine's subnet instead, so real TVs on your network still show up.

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:testDebugUnitTest    # unit tests
```

> **Production note:** a key bundled in an APK can be extracted. For a public release, route Claude requests through your own backend and keep the API key server-side.

## Usage

1. Open **Televisores**: TVs on your network appear automatically. Tap one to link it.
2. Follow the on-screen step (accept on the TV or type the code).
3. Back on **Inicio**, tap the microphone and speak. Examples:
   - "Sube el volumen 5" · "Baja el volumen" · "Silencio"
   - "Siguiente canal" · "Baja dos canales"
   - "Abre YouTube y luego sube el volumen"
   - "Pausa" · "Adelanta" · "Atrás" · "Inicio"
4. Open **Control** for the full remote; swipe between pages and turn the crown for volume.

## License

Copyright © 2026 camilo661. All rights reserved.
