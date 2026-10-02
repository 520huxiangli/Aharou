# Feature Overview

This page is a quick index of the user manual, mapping the main UI workflows and settings entries to their documentation.

This index groups entries by **feature topic**, which does not map exactly onto the groups in the settings page: for example, "Accessibility" and "Floating window" are two separate rows under "Permissions & Background", "Config changes" and "Personality (Soul)" sit under the "Aharou" group, "Memory" lives under "Data & Diagnostics", and "Custom prompts" has no settings entry of its own — it is configured through files.

::: tip Version notes
Entries marked with a version (e.g. "since v1.11.0-dev") were introduced in that version.
:::

## Core Workflows & Main UI

| Feature | Description |
| --- | --- |
| Chat & workspace | Title bar, sidebar, toolbar, message queue, folding long pastes, workspace switching |
| Modes (three modes) | Permission control and use cases for Build / Plan / Auto |
| Checkpoints & undo | Automatic snapshot before AI edits, one-tap safe rollback |
| Terminal | Multi-tab sessions, auxiliary key bar, color and font settings |
| Voice calls & reading aloud | Hands-free voice calls with floating-window subtitles, AI reply reading and auto read-aloud (voice input has been retired; the mic in the input bar only toggles calls) |
| Built-in browser | WebView browser, with AI automation of web pages (navigate, click, fill forms, screenshot, and more) |
| File browsing & code editing | Indented tree view, syntax highlighting, built-in code editor (since v1.11.0-dev), plus where workspace files live on the phone and how to reach them |
| Git version management | Visual status management, branch switching, commit history, revert and delete (since v1.11.0-dev) |
| Tablet & large screen | Responsive split panes, persistent sidebar, side-by-side workbench (since v1.11.0-dev) |

## Settings & Extension Index

### General

| Entry | Description |
| --- | --- |
| Preferences | Global preferences: invalid model cleanup, startup session, enter to send, first-byte and interval timeouts, retry count, auto-compaction threshold, sendFile per-file limit |
| Appearance, background image, language | Light/dark theme, preset color schemes, Monet color extraction, background image and multiple languages |
| Terminal settings | Terminal colors, font size and cursor style |
| Accessibility & floating window | Accessibility service (screen reading, tapping, screenshots) and the status-capsule floating window |
| Config changes | A record of the AI changing its own settings through the config channel, with one-tap revert to the previous value |

### AI Configuration & Extensions

| Entry | Description |
| --- | --- |
| [AI Vendors](/en/guide/providers) | Connect model services, manage model lists, multi-key (since v1.11.0-dev), reasoning effort |
| Default & dedicated models | Default model for new sessions, plus dedicated models for image recognition, compaction, title summarization and image generation |
| Model groups | Bundle several models into one group so it can be picked as a whole wherever a model is chosen (currently used for the four default-model roles) |
| MCP servers | Connect external tools, global and project-level configuration |
| Skills | On-demand specialist extension packs |
| Subagents | Spawn independent sessions to run tasks in parallel; create, edit, enable and disable in settings, with custom models and tool sets (since v1.11.0-dev) |
| Custom prompts | Override and customize AI system prompt fragments |
| Memory & project rules | Cross-session long-term memory, plus AGENTS.md / CLAUDE.md project rules |
| Personality (Soul) | The assistant's name, icon, style and personality text, injected into the system prompt |

### Environment

| Entry | Description |
| --- | --- |
| Container & images | Local Linux container, environment health check with one-tap install, custom images, mounting phone directories, remote SSH backend |
| Network proxy | Global proxy and vendor-level proxy (since v1.11.0-dev) |
| Connections & sync | SFTP / FTP channels, workspace sync, built-in FTP server |
| Host execution backend | Run system commands and read/write `/sdcard` — as root (uid 0) when root is granted, otherwise as adb shell via Shizuku (uid 2000) |
| Shadow screen | A virtual display that is not shown on the phone screen; the agent can launch apps, take screenshots and tap inside it |
| Environment variables | Global environment variables injected into every process in the container (values stored encrypted) |

### Permissions & Background

| Entry | Description |
| --- | --- |
| Tool authorization | Rules for which tools the AI may call |
| App permissions | System permissions such as installing unknown apps, storage, battery optimization, autostart, root and Shizuku |
| Background | Background keepalive, keep screen on, agent completion notification |

### Data & Diagnostics

| Entry | Description |
| --- | --- |
| Token stats | Usage, cost estimation, call details |
| Storage | Per-category usage of chats, containers, workspaces, and cleanup of temporary data |
| Backup & restore | Encrypted export/import of configuration and workspaces |
| Logs | View runtime logs, crash reports, performance monitor (CPU / memory / traffic) |
| Common error messages | What each retry bubble category means, common HTTP error codes and troubleshooting |

### Help & About

| Entry | Description |
| --- | --- |
| About | Version info and update checks |

## Advanced Tutorials

| Topic | Description |
| --- | --- |
| Building Android apps in the container | Set up JDK and Android SDK, build an APK from source |
| Building Flutter apps in the container | Install JDK, Android SDK and Flutter SDK, build a Flutter debug APK |
| Installing Playwright browser automation | Install Chromium inside the container and connect Playwright MCP so the AI can operate web pages |
| Wireless adb debugging of other devices | Use adb from inside the container to connect another phone or this device, install apps, grab logs and take screenshots |
| Custom dashboard cards | Draw balance or usage cards above the input box with scripts |
