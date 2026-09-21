# VLosses

An all-in-one lobby plugin for **Paper 1.21.11** and **Java 21**.

VLosses combines core lobby features into one configurable plugin: TAB, scoreboard, protected lobby worlds, lobby items, announcements, spawn management, links, languages, and optional proxy support.

## Features

- TAB header, footer, and player-name formatting
- Animated and configurable scoreboard
- Lobby-world protection
  - Damage, fall damage, void damage, hunger
  - Block breaking and placing
  - Item dropping
  - Mob spawning, explosions, fire spread, and entity griefing
- Configurable lobby spawn with join and respawn teleportation
- Lobby items with custom materials, names, slots, permissions, and actions
- Clickable Discord, vote, and website links
- Chat, action bar, and title announcements
- Player language preferences and client-language detection
- Built-in languages: Turkish, English, German, Spanish, French, and Portuguese
- MiniMessage and legacy `&` color support
- Optional LuckPerms prefix, suffix, and group support
- Optional PlaceholderAPI support
- Optional BungeeCord / Velocity server transfer bridge
- `/vl diagnostics` command for TPS, MSPT, memory, integration, and conflict checks

## Requirements

- Paper **1.21.11**
- Java **21**
- Optional: LuckPerms
- Optional: PlaceholderAPI
- Optional: BungeeCord or Velocity for multi-server transfers

## Installation

1. Download the latest `VLosses-*.jar` from [Releases](https://github.com/Vozreth/VLosses/releases/latest).
2. Put the JAR file into your server's `plugins` folder.
3. Start or restart the server.
4. Configure files in `plugins/VLosses/`.
5. Add your lobby world name to `lobby.worlds` in `config.yml`.
6. Join the lobby world and run `/vl setspawn`.

> Do not use the server `/reload` command. Use `/vl reload` after changing VLosses configuration files.

## Commands

| Command | Aliases | Description |
| --- | --- | --- |
| `/vl help` | `/vlosses`, `/vl` | Shows available commands |
| `/lobby` | `/hub`, `/spawn` | Teleports to the configured lobby spawn |
| `/vl setspawn` | — | Sets the lobby spawn location |
| `/vl reload` | — | Reloads VLosses configuration safely |
| `/vl diagnostics` | `/vl doctor` | Shows plugin diagnostics |
| `/vl language <tr\|en\|de\|es\|fr\|pt\|auto>` | — | Changes personal language |
| `/discord` | `/dc`, `/discor` | Opens the configured Discord link |
| `/vote` | `/oy` | Opens the configured vote link |
| `/website` | `/web`, `/site` | Opens the configured website link |
| `/vl server <name>` | — | Connects to an allowed proxy server |
| `/vl announce <number>` | — | Sends a configured announcement |

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `vlosses.use` | Everyone | Access to VLosses commands |
| `vlosses.spawn` | Everyone | Use lobby spawn commands and items |
| `vlosses.language` | Everyone | Change personal language |
| `vlosses.admin` | OP | Reload, set spawn, diagnostics, and announcements |
| `vlosses.bypass` | Nobody | Bypass lobby protections |
| `vlosses.server.<server>` | OP | Connect to a configured proxy server |

## Configuration

Main settings are in `plugins/VLosses/config.yml`.

- `lobby.worlds`: Worlds managed as lobby worlds
- `lobby.protection`: Lobby protection settings
- `tab`: TAB header, footer, name format, and animation settings
- `scoreboard`: Scoreboard title and lines
- `announcements`: Automatic messages and display channels
- `links`: Discord, vote, and website URLs
- `bridge`: BungeeCord / Velocity transfer settings
- `integrations`: LuckPerms and PlaceholderAPI settings

Lobby items are configured in `items.yml`, and scoreboard/TAB/announcement displays are configured in `displays.yml`.

## Building from Source

```bat
gradlew.bat build
```

The compiled plugin JAR will be created in `build/libs`.

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
