[中文](./README.md) | **English**

<div align="center">
<img src="safe-protect/red/public/logo-text.png" width="400" />

# RED TEAM
**MINECRAFT MOD EXPLOIT FRAMEWORK FOR SECURITY RESEARCH**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green)](https://minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-47.2.0-orange)](https://minecraftforge.net)
[![Java](https://img.shields.io/badge/Java-17-blue)](https://openjdk.org)
[![Modules](https://img.shields.io/badge/Modules-100%2B-red)](safe-protect/red/src/main/java/com/redblue/red/modules)

[Features](#-features) · [How It Works](#-how-it-works) · [Modules](#-exploit-modules) · [Quick Start](#-quick-start)
</div>

---

**RED TEAM** is a client-side Minecraft Forge mod demonstrating security vulnerabilities in popular mod ecosystems. 100+ modular exploits across 20+ mods — missing server-side validation, rate limiting bypasses, NBT injection, packet spoofing, and DoS vectors — all through a clean GUI with hotkey bindings and remote code loading.

## ✨ Features

- **100+ exploit modules** targeting 20+ popular Minecraft mods
- **Modular architecture** — each exploit is an independent, configurable `AttackModule`
- **In-game GUI** — category grid, module list, dynamic config screens, tutorial viewer
- **Hotkey system** — K to open menu, Shift+1–0 for 10 bindable action slots
- **Stealth layer** — removed from ModList, FML handshake, and network registry at runtime
- **Remote module loading** — download and run new exploits in-memory via `/rc load <token>`
- **Dual-mode packet sender** — raw `ServerboundCustomPayloadPacket` + reflection fallback
- **Zero config files** — all state is in-memory, no traces on disk
- **Per-module parameters** — typed config (int, float, string, enum, item, entity) with conditional visibility

## 🖼 How It Works

Phase 1 — Module Architecture

<div align="center"><img src="safe-protect/red/public/illus-module-architecture.en.png" width="600" /></div>

1. `RedMod` registers all 100+ `AttackModule` implementations into `ModuleRegistry` at startup
2. Modules grouped by target mod prefix (`lrt`, `tacz`, `ia`, `ftq`, etc.)
3. Each module declares its own `ConfigParam` list, availability check, and execution logic
4. `ModuleRegistry` provides lookup by ID and category-grouped access for the GUI

Phase 2 — Exploit Execution Flow

<div align="center"><img src="safe-protect/red/public/illus-exploit-flow.en.png" width="600" /></div>

1. Player opens GUI with **K** or triggers a hotkey slot (**Shift+1–0**)
2. `KeyDispatcher` routes input — opens `RedMainScreen` or calls `module.execute()` / `module.tick()`
3. Module constructs crafted packet payload targeting the vulnerable mod's channel
4. `PacketForge` sends the packet — raw mode first, reflection fallback if needed
5. Server-side mod processes packet without validation → exploit succeeds

Phase 3 — Stealth System

<div align="center"><img src="safe-protect/red/public/illus-stealth-system.en.png" width="600" /></div>

1. On `FMLLoadCompleteEvent`, `HideOnLoad` triggers both hiders
2. `ModHider` removes the mod from Forge's `indexedMods`, `modFiles`, `sortedList` via reflection
3. `HandshakeHider` cleans `NetworkRegistry.instances` and `NetworkRegistry.channels`
4. Remote modules load via `InMemoryClassLoader` — no JAR on disk, no file traces

## 🎯 Exploit Modules

| Category | Modules | Vulnerability Types |
|---|---|---|
| LR Tactical | LRT01–LRT04 | KillAura, DoS, death exploit |
| TACZ Firearms | TACZ01–TACZ02 | Attachment dupe, rapid fire |
| TACZ Addon | TACZADD01–03 | Slot swap, remote container, crash |
| Citadel | CTD01–CTD03 | Tag override, NBT bloat DoS, spoof |
| Alex's Mobs | AM01–AM07 | Item inject, teleport, hijack, biome corrupt |
| Corpse | CORPSE01–03 | Death history spy, page crash, I/O flood |
| KubeJS | KJS01–KJS03 | Data inject, click flood, NBT bloat |
| JourneyMap | JM01–JM05 | Teleport, admin config R/W, DoS |
| Open Parties & Claims | OPAC01–05 | Claim DoS, sync flood, memory pressure |
| CustomNPCs | CNPC01–07 | NBT overwrite, dialog forge, player data wipe |
| Curios API | CURIOS01–02 | Destroy all items, render toggle |
| ParCool! | PCOOL01–03 | Infinite stamina, action spoof, DoS |
| Limitless Vehicle | LV01–LV06 | Hijack, artillery abuse, remote craft |
| Immersive Aircraft | IA01–IA06 | Velocity inject, inventory spy, NaN crash |
| Cataclysm | CATA01–02 | Altar inject, race condition DoS |
| FTB Quests | FTQ01–06 | Item dupe, quest vandal, structure leak |
| ExtinctionZ | EXT01–04 | Remote container, backpack NBT inject, bypass |
| Simple Voice Chat | VC01–03 | Group brute force, flood DoS, audio amplify |

## 🚀 Quick Start

```bash
cd safe-protect/red
./gradlew build
# Output: build/libs/redteam-1.0.0.jar
```

Install into Minecraft 1.20.1 + Forge 47.2.0 client `mods/` folder.

**In-game controls:**
- `K` — open main menu
- `Shift+1` through `Shift+0` — trigger bound hotkey slots
- `/rc load <token>` — load remote exploit modules
- `/rc unload` — unload remote modules

## 🛠 Tech Stack

| Component | Technology |
|---|---|
| Minecraft | 1.20.1 |
| Mod Loader | Forge 47.2.0 |
| Language | Java 17 |
| Build | Gradle + ForgeGradle |
| Mappings | Official (Mojang) |
| Packet Layer | Netty + Forge SimpleChannel |
| Class Loading | Custom InMemoryClassLoader |
| Remote Loading | Java ServiceLoader + Bearer auth |
