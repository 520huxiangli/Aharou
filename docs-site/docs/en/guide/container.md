# Containers & Images

In Aharou, the place where the AI runs commands is called the "container". The entry point is "Settings → Container & Images"; the list is single-select — whichever one you pick is where the AI's commands, file reads and writes, and terminal all run.

Backends come in two kinds: a **local container** (runs a Linux system on your phone) and **remote SSH** (runs on your own server). You must pick one of them. For remote mode, see [Remote SSH mode](/guide/remote-ssh).

## Container List

Each row is one container config, with a source badge after the name: Built-in, Custom, SSH, told apart by color.

Three operations work the same for every container:

- **Edit**: the pencil icon at the end of the row.
- **Reset**: the refresh icon at the top-right of the edit dialog. This is destructive — it deletes the tools and configs installed in the container and asks for confirmation; as with switching containers, all running AI sessions and terminal tabs close once you confirm. Deleting a large container with a lot installed takes tens of seconds; during that time "Cleaning Container Data" appears and shows the number of items removed in real time, then closes automatically, so the UI never freezes.
- **Delete**: swipe the row left to reveal the red delete button. Deleting a local image also clears its rootfs to free space (with the same progress display); deleting a remote SSH config only removes the config and leaves the data on the server alone.

The built-in Alpine can be deleted too. If you delete everything, the list shows an empty state with a button that restores the built-in Alpine in one tap, so there's no need to worry about deleting it by mistake.

Once a container is running, the app automatically detects which system is installed inside and shows the matching system icon on the left of the row (Alpine, Ubuntu, Debian and CentOS are supported for now); if it can't tell, it shows a default icon.

## Built-in Alpine Container

The app ships with an Alpine image; on first launch it is added to the list and selected automatically, the shell path is chosen for you, and nothing needs configuring.

::: tip Enter the terminal once first to finish initialization
Local container preparation (extracting the rootfs and installing base tools) is triggered the first time you enter the **terminal page**. So on first use, open the terminal page and complete initialization; after that the AI can execute commands, manage Git and use local MCP normally.
:::

## First-Terminal Initialization Menu

Whether it's the built-in Alpine or an image you imported yourself, the first time you enter the terminal after the rootfs is extracted a menu pops up with four options:

**1. Auto-install dependencies (recommended)**

It first lists what will be installed (base tools such as bash, curl, ripgrep and git, plus Node.js and Python 3). Type `y` to confirm, pick a package mirror, and installation starts.

**2. Environment setup**

Opens the "Environment setup" menu, where you choose the development environment to install by scenario (see the next section). A scenario is a bundle of runtimes: pick one, confirm the list, and it installs; the menu also has "Custom install" to tick items one by one and specify a major version.

**3. Manual install (don't ask again)**

Skips installation — the menu won't pop up in the terminal again and you install tools yourself. Reset the container to bring the menu back.

**4. Exit**

Nothing is installed this time; you'll be asked again the next time you enter the terminal.

A failed package install won't keep you out of the shell — re-enter the terminal and try again.

### Package Mirror Selection

When switching mirrors you can choose:

- `1) Auto-detect (recommended)`: actually tests how reachable each mirror is under the current package manager, and automatically skips dead or blocked ones.
- `2~6)` A specific mirror: Huawei Cloud, Tsinghua TUNA, USTC, Tencent Cloud, Aliyun.
- `0)` Keep the default mirror.

The original config is backed up before switching and restored automatically on failure, so later installs aren't affected. The built-in Alpine rootfs keeps its official mirror; use this menu if you want a domestic one.

## Installing Development Dependencies Anytime (aicode)

The initialization menu only pops up the first time you enter the terminal. To install other runtimes later (say you need Java or Go after a while), there are two entry points, both opening the same "Environment setup" menu:

- Run `aicode` in the terminal;
- Tap the **Tools** icon at the top-right of the terminal and pick "Re-run environment setup" — the same tool opens in a new tab.

`aicode` installs by scenario: pick a scenario, confirm the list, and you're done:

| Scenario | Installs |
| --- | --- |
| General development | Base tools + Node.js + Python 3 |
| Python development | Base tools + Python 3 + pip |
| Node.js / frontend development | Base tools + Node.js + npm |
| Kotlin / Android development | Base tools + JDK 17 + Android SDK |
| Flutter development | Base tools + JDK + Android SDK + Flutter SDK |
| Java development | Base tools + JDK |
| Go development | Base tools + Go |
| Rust development | Base tools + rustc + cargo |
| PHP development | Base tools + PHP + Composer |
| Base tools only | bash, curl, ripgrep, git |

Base tools (bash, curl, ripgrep, git) are installed along with any scenario. Scenarios install the latest major version from the package repository by default; to pin a version, use "Custom install" in the menu and tick items one by one. The menu also lets you switch the package mirror on its own and see which runtimes are already installed.

"Kotlin / Android development" and "Flutter development" are heavy scenarios: they download the JDK, the Android SDK (including build-tools and platform-tools) and the Flutter SDK from domestic mirrors, which takes a long time and uses a lot of storage. Install them in a Debian / Ubuntu image (the built-in Alpine uses musl libc and needs extra handling); on ARM64 devices native binaries such as aapt2 are automatically replaced with runnable ones. Note that an ARM64 container can only build Flutter debug APKs — release packages need remote building. Once installed, newly opened terminals automatically carry environment variables such as `JAVA_HOME` and `ANDROID_HOME`; for the exact build steps see "Building Android Apps in the Container" and "Building Flutter Apps in the Container" in the Advanced Tutorials.

The settings icon at the top-right of the terminal is now a **Tools** icon; besides the environment tools entry, the panel also lets you adjust the color theme, font size, font and cursor style.

## Environment Check

Under "Settings → Container & Images" there is an **Environment Check** block. Tap "Run Check" and it probes each item in turn, laying the conclusions out directly:

| Item | What it probes | If it fails |
| --- | --- | --- |
| Shared storage access | Whether "All files access" has been granted | Files on the phone can't be read |
| Sandbox basic tools | Whether `sh` / `git` / `curl` / `tar` / `xz` are all present | The features that need them stop working |
| Container DNS | Whether `github.com` resolves | Downloads and clones inside the container all fail |
| Container network | Whether `api.github.com` is reachable (by HTTP status code) | The AI hangs when downloading dependencies or fetching models |

Status dots come in four colors: **green** all good, **yellow** needs attention, **red** error, **grey** couldn't be probed (the sandbox is busy or not started). Grey is not a failure — getting no result and finding a real problem are two different things, and it isn't counted as an item needing attention.

Every item states the **specific reason** rather than a vague "something is misconfigured". The base tools item, for example, names things outright: "Ready 4/5, missing: xz".

### One-Tap Install for Missing Base Tools

If the base tools item is missing something, an **"Install"** button appears below it — one tap installs them, and the check re-runs by itself afterwards.

- **Package manager auto-detected**: it probes whether the container has `apk` or `apt-get`, so you don't have to tell it which distribution you're on
- **Package names mapped automatically**: on Debian / Ubuntu systems `xz` lives in the `xz-utils` package, and the correct name is substituted for you
- **Installed in the container, not in the image**: writing it into the image would mean minutes of rebuilding, which isn't worth it for one `xz`. The trade-off is that **you have to install it again after rebuilding the image**, as the UI notes
- **Failures come with a reason**: when installation fails, the tail of the command output is shown (usually a broken mirror or a wrong package name), so you don't have to dig through logs

> To make it permanent (so it survives an image rebuild), add the package to the base dependency list in that image's build script. The environment check only makes clear what's missing and how to fix it — it doesn't modify the image.

## Custom Images

The built-in Alpine is enough for most cases, but if you want Debian / Ubuntu, or an environment with a particular toolchain preinstalled, you can add images yourself. Two ways.

### Option 1: Built-in Image Download (Recommended)

1. Open "Settings → Container & Images" and tap the download icon at the top-right.
2. The page lists the downloadable images (Alpine, Ubuntu, Debian and more); tap a row for details and download.
3. The top-right lets you switch the download source; the default is "Aharou (self-hosted)", alongside Official / Huawei Cloud / Aliyun / Tencent Cloud. On Chinese networks, prefer the self-hosted or a domestic cloud source — the official one can be extremely slow or even unreachable on some networks.
4. When the download finishes, tap "Import" and the image joins the container list — select it to use it.

Downloaded images can be imported again; swipe left to delete the downloaded image file.

### Option 2: Manually Import an Image File

When you need a specific version or distribution, you can prepare a rootfs image yourself and import it.

**Image requirements**:

- Compression formats: `.tar.gz`, `.tgz`, `.tar.xz`, `.txz`
- The content must be a complete Linux rootfs (a filesystem root containing `/bin/sh` or another shell)
- The architecture must match your device: aarch64 images on ARM64 devices, x86_64 images on x86 devices

**Where to get images**:

- Alpine (small and fast to start): `https://alpine.linuxhub.cn/alpine/edge/releases/` — in the aarch64 directory pick `alpine-minirootfs-*-aarch64.tar.gz`
- Ubuntu: `https://cdimage.ubuntu.com/ubuntu-base/releases/` — pick `ubuntu-base-*-base-arm64.tar.gz`
- Debian: export it from the official image on Docker Hub (e.g. `arm64v8/debian`)

If you have a Docker environment, export a rootfs from any image:

```bash
docker pull debian:bookworm
docker create --name temp-rootfs debian:bookworm
docker export temp-rootfs | gzip > debian-rootfs.tar.gz
docker rm temp-rootfs
```

You can also build one yourself in an existing Linux environment with `debootstrap` (Debian / Ubuntu) or `alpine-make-rootfs` (Alpine).

**Import steps**:

1. Open "Settings → Container & Images", tap + at the top-right and pick "Local Image".
2. Fill in the config:
   - **Name**: an alias for this image config.
   - **Shell**: the shell inside the container, such as `/bin/sh` or `/bin/bash`.
   - **Image file**: pick the prepared rootfs file. On save a copy is placed in the app's private directory, and every later container reset re-extracts from that copy instead of depending on the original file; deleting the image clears the copy as well.
   - **Mounts** (optional): mount phone directories into the container — see "Mounting Phone Directories for the AI" below.
   - **Proot args** (optional): add them one by one; they're appended verbatim to the PRoot startup arguments.
   - **Environment variables** (optional): key-value pairs injected into processes inside the container.
3. After saving, select this config in the list and you've switched to a custom container.

On first use of a custom image the app extracts the rootfs into its private directory; how long that takes depends on the image size. Custom images don't come with a domestic package mirror by default — you can switch it in the initialization menu the first time you enter the terminal.

### Troubleshooting Common Issues

**Errors when installing packages**: caused by hard links being unavailable inside the container. Edit the image → Proot args → add `--link2symlink` → save, then re-enter the terminal. Images imported through the built-in download feature already include this argument by default.

**Arrow keys showing as `^[[A`**: the current shell is `sh`, which has no line editing. Edit the container and change Shell to `/bin/bash`, which brings arrow keys, history and completion.

## Built-in Directories in the Container

Every time the app starts a container it automatically mounts the directories below, ready for the AI and the terminal to use right away:

| Path in container | What's in it |
| --- | --- |
| `~/workspace` | The current workspace. Switching workspace swaps what's here |
| `~/shared` | Cross-workspace shared area, one copy shared by all workspaces (see the next section) |
| `~/.aharou` | AI config directory: `skills/`, `docs/`, `mcp.json` and more |
| `~/.aharou/memory` | Memory: SOUL.md, core profile, logs |

(`~` expands to `/root`; `~/.aicode` is a legacy path alias for `~/.aharou` — both hold exactly the same content, so you can ignore it.)

These directories live in the app's private storage, separate from container images: switching images or resetting a container never touches them, and deleting a container doesn't affect them either. To put things in them you can work directly in the terminal or let the AI do it.

## Mounting Phone Directories for the AI

The AI runs inside the container and by default only sees the workspace, not the phone's shared storage. To give it access, mount a phone directory into the container:

1. First make sure storage permission is granted. A prompt appears on first use; if you denied it before, go to system settings → Apps → Aharou → Permissions → Storage and enable it.
2. Open "Settings → Container & Images" and edit the container you're currently using.
3. Add a row under "Mounts": **Local path** is the path on the phone (for example `/sdcard/Download`), **Container path** is the path inside the container (for example `/mnt/Download`).
4. After saving, restart the terminal session and the AI can reach it through the container path.

::: warning Mount only what you need
Mount only the subdirectories you need — don't just mount the whole `/sdcard` to save effort, since that exposes every file on your phone to the AI and risks leaking private data.
:::

Example: to let the AI read the app's own logs, mount the local directory `/storage/emulated/0/Android/data/com.aharou.agent/` at `/mnt/aharou` in the container, and it can read the logs under `/mnt/aharou/files/logs/`. Note that under `Android/data/` only Aharou's own directory is accessible; other apps' directories are blocked by scoped storage.

If you only need to handle a single file, the simpler route is to copy it into the workspace, where the AI can reach it by default.

## Cross-Workspace Shared Directory

Each workspace has its own directory, and switching workspace moves you elsewhere. If you want the same set of files (common scripts, assets, templates) across all workspaces, put them in the **shared area**:

- Path in container: `~/shared` (that is, `/root/shared`)
- Entry in the app: the "Open shared folder" button in the chat title bar — tap it to browse and transfer files
- Location on the phone: mounted inside the app's private directory, visible after mounting Aharou in the system "Files" app (see "File Browsing & Code Editing")

Example usage: put your usual build script in the shared area, then tell the AI "build the current project with `~/shared/build.sh`"; or drop asset images in there and reference them from any workspace.

::: tip Difference from a workspace
`~/workspace` is the directory of the **current workspace** and changes when you switch workspaces; `~/shared` is **shared** by all workspaces and doesn't change. Files belonging to one project still go in `~/workspace` — don't pile them into the shared area.
:::

::: warning The shared area is not in backups
"Backup & Restore" does not include files in the shared area; keep a separate copy of anything important.
:::

## What Happens If You Delete Everything

Even if you delete every container (including the built-in Alpine), the app stays usable — when the AI executes commands it falls back to the built-in Alpine as the default execution environment. Reopen the "Container & Images" page and the empty state lets you restore the built-in Alpine or add a new image in one tap.
