# Logs & Troubleshooting

When something goes wrong with the app, logs are the most direct clue. "Settings → Logs" lets you read them right inside the app.

## Viewing Logs in the App

Opening it lands on today's log by default. On this page you can:

- **Log level**: the setting row at the top shows the current level; tap it to pick one from a bottom sheet (Verbose / Debug / Info / Warn / Error / None). When chasing a bug, set it to `Debug`; keep `Info` for everyday use. Official builds default to `Info`, filtering out debug noise.
- **Switching files**: tap the "Log file" row to bring up a date list from the bottom; today is selected by default.
- **Search & copy**: keywords filter live, and the button at the top-right copies the current filtered results in one tap.
- **Color-coded levels**: Verbose grey, Debug blue, Info green, Warn orange, Error red.
- **Copying a single line**: tap any line to copy that complete log entry (with time, level and source); long-press for a menu that lets you choose the scope — this line, this line plus its stack trace, or just the message.
- **Live scrolling**: on by default, refreshing every 3 seconds and following the newest entries; turn it off to stop refreshing and stay wherever you scrolled to.

To keep large logs from causing lag, the app only shows the tail end of the current file under the active filters — open the file on the file system for the complete content.

## Where the Log Files Are

```
/storage/emulated/0/Android/data/com.aharou.agent/files/logs/
```

No root access is needed — your phone's built-in file manager can open it.

Workspace project files and AI configuration live in the app's private directory, which ordinary file managers cannot see; see [File Browsing & Code Editing](/en/guide/files#where-workspace-files-live-on-your-phone) for how to get to them.

## Filtering by MCP Name

When troubleshooting an MCP server that will not connect, go to "Settings → MCP Servers", edit a server and tap the log button at the top-right — it jumps to the log page with that server's name already applied as a filter (a filter badge shows at the top of the page, clearable in one tap). The log button at the top-right of the list page shows all logs instead.

## What to Do When It Crashes

An uncaught exception **does not make the app force-quit**; instead it shows a full-screen crash page:

- The page shows the version, device and time of the crash, plus the full stack trace.
- Tapping "Copy error info" copies the complete crash report (including the stack trace and the log file path) — paste it straight to the developer, no need to dig through logs yourself.
- You can choose "Restart app" to go back to the main screen, or "Quit app" to exit completely.

Crash logs are written to the same `logs/` directory as above.

## Is It Lagging? Check the Performance Monitor

If the device feels hot, laggy or drains battery fast, "Settings → Data & Diagnostics → Performance Monitor" shows live data directly — no third-party tools needed:

| Metric | Description |
| --- | --- |
| App CPU | Usage of this process, normalized by core count (on an 8-core device a full load reads 100%, not 800%) |
| Device CPU | Overall device usage. **On some system versions it reads "—"** — see below |
| Threads | Total thread count of this process (it rises while the AI is running a task, which is normal) |
| App memory (PSS) | Actual memory used by this process, including child processes such as the container |
| Device memory | Total capacity and available amount |
| App traffic / Device traffic | Up and down rates; the former is counted by UID |

Data refreshes once per second and is sampled **only while the page is open** — leave the page and it stops, so it never keeps draining battery in the background. The chart shows the trend over the recent period, with its range scaling to the actual peak.

**A device CPU reading of "—" is normal.** Since Android 10, the system no longer lets ordinary apps read `/proc/stat` (permission denied). The app shows "—" honestly rather than faking a 0%, because "cannot read it" and "genuinely idle" are two different things.

## Long Tasks Killed When Switching to the Background

If the AI is running a long task (such as compiling or a deep code review) or the terminal is running a time-consuming command, and it often gets killed when you switch away, go to "Settings → System Permissions" and enable **background keep-alive**, **ignore battery optimization** and **autostart management**. See [System Permissions](/en/guide/app-permissions) for details.
