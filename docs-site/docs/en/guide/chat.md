# Chat Interface Guide

This page explains what every button and area on the main screen does.

## Top Title Bar

- **Menu button** (top-left): opens the sidebar, where you manage your session list and browse files.
- **New session**: starts a new conversation.
- **Git entry**: jumps to the Git version management page.
- **Terminal entry**: jumps to the terminal page.
- **Browser entry**: opens the built-in browser (a floating panel on phones, a right-hand pane on large screens).
- **Sandbox entry**: opens the sandbox files.
- **Shared area entry**: opens the shared area (files shared across workspaces).

(The current model is not shown in the title bar but on the model button in the toolbar below the input box; the session title is no longer shown in the title bar either.)

In remote SSH mode an extra line appears below the title bar: the SSH connection status on the left (a dot plus text), and the token totals for the current session on the right (up arrow for input, down arrow for output). The line is not shown in local mode.

## Sidebar

Tap the menu button at the top-left, or swipe right from the left edge of the screen. At the top there are two tabs: "Sessions" and "Files".

### Sessions Tab

Sessions are grouped by **workspace folder**: folders are ordered with the current one first and then by most recent activity, and inside a folder pinned sessions come first, followed by last reply time (newest first). A pinned session's card has a light (theme-colour) background, and there is no separate "Pinned" group at the top.

A short press switches to a session; **a long press opens an action menu**:

- **Pin / Unpin**: a pinned session stays at the front.
- **Rename**: change the session title; ordering is not affected.
- **Export**: export this session.
- **Batch select**: enter multi-select mode, where you can check several sessions, use one-tap "Select all" and "Delete selected" (with a second confirmation). Leave multi-select with the × at the top-left or the back key.
- **Delete**: a confirmation dialog appears; confirming deletes the session and all its messages.

For a session that has spawned subagents, the row shows the subagent count and an expand arrow at the end; tap it to see its subagents listed below (**since v1.11.0-dev**). See [subagents](/guide/subagent).

Below the sidebar's tab bar is **chat search** (**since 1.12.0**): type a keyword to search message content across all sessions in the current workspace, and the list area shows the matches in place (session title plus the matching snippet, with the keyword highlighted). Tapping a result switches to that session and jumps to that message; tap the × on the right of the search box or clear the text to return to the session list. Search covers the current workspace only, and matches only the body text of your messages and the AI's — tool execution output is not included.

### Files Tab

Browse the files of the current workspace; tapping a file opens the code editing page (**since v1.11.0-dev**). See [File browsing & code editing](/guide/files).

At the bottom of the sidebar is the "Settings" entry.

## Toolbar Below the Input Box

From left to right:

- **Mode button**: cycles between BUILD, PLAN and AUTO — see [the three modes](/guide/modes).
- **Model button**: shows the current model; tapping it opens the picker. A search box at the top filters models fuzzily, models are grouped by vendor, and tapping a group header collapses or expands it (the collapsed state is remembered, and kept when you reopen the dialog or restart the app; while searching, matching groups expand automatically). Each row is labelled with capability tags (`Image`, `Tools`, input and output length) — one tap switches the model.
- **Workspace button**: shows the current workspace name; tapping it opens the workspace management panel (see below).
- **Reasoning effort button**: a lightning icon with the current level next to it; tapping it opens a bottom sheet with seven levels (`None` / `Default` / `Low` / `Medium` / `High` / `Extra high` / `Max`) — see [AI Vendors & Models](/guide/providers).
- **Voice button**: a mic icon; a light tap starts or stops a voice call — see [Voice calls & reading aloud](/guide/voice).
- **"+" button**: upload attachments. It opens a bottom sheet with **Upload File / Upload Image / Take photo**: a file is attached for the AI to read; an image is picked from the system gallery (multiple selection allowed, and the current model must support image input); Take photo opens the camera directly.
- **Send button**: a plain send button while the AI is idle. While the AI is working it becomes a stop button if the input box is empty, or an orange send button if there is content (the message is queued).

Once the text you type grows taller than the input box, an **expand button** appears at the top-right of the box: tap it to edit this draft on a full screen, and leave with "Collapse" at the top-right or the system back key. Edits made on the full screen sync back to the input box in real time — both are the same draft — so you can collapse and hit send.

Uploaded attachments are shown as preview cards above the input box and can be removed before sending. Uploading a large file takes a moment; while it is in progress a hint is shown above the input box, and it turns into a preview card automatically once the upload finishes. Tap an image thumbnail to [view it full screen](#full-screen-image-viewing).

### Pasting Long Text

When you **paste more than a thousand words (or 1,200 Chinese characters)**, the raw text does not fill the input box — it collapses into a small `[Pasted#1]` token with the original kept alongside. The label above the input box shows the character count, and it makes no difference where the cursor is.

This is so that pasting a chunk of logs doesn't blow up the input box and leave you unable to see what you actually wrote. **On send it expands back into the full original text** for the AI — it never sends just the token.

Three things are enough to remember:

- **Delete the token text** → the matching block is recycled automatically and the label disappears with it.
- **Tap the × on the label** → the token text is removed from the input box as well; both are cleared together.
- **The block shares the draft's lifetime** → after the process is killed and you reopen the app the draft is still there and so is the block, so it never becomes a dead token that can't be expanded.

### Sharing Files In from Other Apps

In the gallery, QQ, a file manager or another app, tap "Share" on a file and pick this app, or tap "Open with → this app": the file is first copied into the `.aharou/attachments/` directory of the current workspace, and then a dialog asks you **what to do with this file**:

- **Preview**: open the file and look at its content right away (only offered when a single file is shared).
- **Insert into input box**: add it to the input box as an attachment, then add a note and send.
- **Cancel**: don't insert it. The file is already in the workspace, so you can still find it on the "Files" tab of the sidebar.

Files ending in a dashboard script extension (such as `.py`) are not treated as ordinary attachments: they are imported straight into the dashboard script directory with a hint, and can then be used in vendor settings.

Draft text that has not been sent is saved per session: type something in one session, switch to another, and the draft is still there when you come back. Drafts are persisted locally, so they survive killing and reopening the app (a process restart).

While you scroll the message list, the input box area fades to 40% opacity to reduce distraction, and returns to normal once scrolling stops.

If you leave to open the editing page, terminal page or settings page while reading back through history, you return to the same position instead of being pulled back to the newest message; if you were already at the bottom before leaving, you keep following new messages on return.

## Workspace Management

The panel opened by the workspace button lets you:

- **Switch workspace**: one tap switches — no confirmation dialog, and nothing is interrupted. A running AI session keeps going (each session reads and writes its own workspace), and terminal tabs stay as they are (a terminal's working directory is fixed when it is opened, so it does not follow the switch).
- **New workspace**: enter a name to create one; it maps to a separate folder on the phone.
- **Rename workspace** (**since 1.14.3**): tap the pencil icon to the right of the workspace row to rename it. For an internal workspace the directory under `projects/` is renamed along with it and session records move to the new path; for an external workspace only the name shown in the list changes, and **the directory on the device is not renamed**. The name can't be empty or duplicate an existing workspace. Renaming the current workspace stops running AI sessions and terminals first.
- **Add local directory** (local mode): use a directory that already exists on the device as a workspace. The first tap shows a risk confirmation dialog (the AI will read and write your real files), with a "Don't show again" checkbox. Only folders from "Internal storage" or "SD card" can be picked; other file providers such as cloud drives are not supported. On Android 11 and above you also need to enable "All files access" in "Settings → App permissions → Storage access" first, otherwise adding one reports that the permission is missing.
- **Delete workspace**: an internal workspace is deleted together with its files; an external workspace added with "Add local directory" is only unlinked, and **its contents are not deleted**. Chat history is deleted along with internal and remote workspaces; for an external workspace, whether chat history is deleted depends on a switch in "Preferences".

A workspace is the project root the AI works in. **Each session is bound to its own workspace**: the AI's file reads and writes and command execution all land in that session's workspace, and do not move elsewhere just because you switched the workspace in the UI.

## Slash Commands

Typing `/` in the input box opens the command menu, with **built-in commands first (alphabetical) and skills after them (also alphabetical)**.

Built-in commands:

- `/compress`: manually trigger context compression for the current session.
- `/init`: ask the AI to analyze the current workspace's codebase and generate or improve the project rules file `AGENTS.md` (if the project root has a `CLAUDE.md` but no `AGENTS.md`, it improves `CLAUDE.md`). You can pass arguments to name a focus, e.g. `/init focus on tests and release flow`.
- `/usage`: see today's and cumulative call counts, token usage and estimated cost (global stats, across sessions).

Skills:

- Every enabled skill can be triggered directly with `/skill-name`, and the AI runs the skill's body as this turn's instruction; in the menu, skills carry a "skill" tag on the right.
- Skills can take arguments, e.g. `/pdf-report a4`: the arguments replace the `$ARGUMENTS` placeholder in the skill body; if the body has no placeholder, the arguments are appended at the end.

While the AI is busy, commands join the queue and run once it is idle. Command text is shown in the conversation as a user message and enters the context.

## Message Queue

The input box stays usable while the AI works. Typing something and tapping the orange send button does not interrupt the current task — the message joins the **pending queue**:

- The queue panel is shown above the input box, listing the index and summary of queued messages.
- When the current turn finishes, the next queued message is sent automatically, and several of them run in order.
- Tapping "Stop" skips the current turn and immediately runs the next queued message.
- The × on the right of each message removes it from the queue.

::: warning The queue lives in memory only
Switching workspaces or having the process killed by the system clears the queue — don't keep anything important only in the queue.
:::

## Replies & Tool Cards

The AI's reply is typed out character by character, and the speed follows the model's output speed automatically: when the model produces fast, the display speeds up too, staying just a short piece behind instead of falling further and further back. The thinking preview line and live tool output keep updating in real time, unaffected by the typewriter; when generation ends, the small remainder is finished quickly (in a fraction of a second) instead of the whole thing jumping in at once.

If you leave mid-generation and come back (switching to the terminal or settings, or to another session that is also streaming), the part already produced is shown at its current progress rather than being retyped from the start, and new content keeps typing out from there.

The message area uses a **flat document flow**: what you send is a light bubble on the right with the send time below it, and AI replies lie directly on the page, no longer wrapped in a bordered card.

Every AI reply is wrapped in a **task folding** header (**since 1.13.0**) that gathers this whole turn's process:

- The folding header shows the state of the turn: "Running" while it is going, and "Completed in Xs" (X is the turn's duration) once it finishes; tap it to expand or collapse.
- **The task collapses automatically once it finishes**, leaving only the final answer; expanded, you can look back over the turn's process (thinking, tool calls, intermediate content).
- After you expand or collapse it manually it stays the way you chose (switching sessions, or scrolling away and back, doesn't change it); the automatic collapse only happens if you never touched it.
- If the AI answered in one go, with neither thinking nor tool calls, no folding header is shown at all, as before.

**Every message you send** keeps the send time and the "Copy / Revert / More" buttons below it, so you can copy or revert any one of them at any time. **AI replies carry "Copy / More" only on the newest one in the whole conversation** (a turn may take several steps with tool calls in between, so the buttons hang on the last step only, rather than every past reply carrying its own duplicate row of toolbar-style buttons that chops the chat log into pieces; and they **appear only when the turn completed normally** — an active pause or an error doesn't leave them on half-finished output). To copy an earlier reply, scroll it to the newest position first. Each turn's usage (`↑input ↓output`), cache hit rate and duration count as information, and are aggregated per turn below that turn's last reply, turn by turn.

The AI's thinking process takes a line of its own and is **collapsed by default**; collapsed, it is a single line: a thinking icon at the start plus a one-line preview — while thinking is in progress it shows the line being written (scrolling with the content), and once thinking ends it stays on the first line of the thinking, truncated with `…` if it doesn't fit. Tap that line to expand the full text at any time, and tap again to collapse. Expanded, the thinking content scrolls inside a fixed-height window (so it doesn't stretch the whole page at once), with a vertical line on the left marking this as thinking.

While waiting for the model, a status line shows at the bottom of the message area: a white highlight sweeps back and forth across the text, followed by three bouncing dots (the dots don't take part in the highlight). When the model is preparing a tool call (the tool name arrives first while the arguments are still streaming in, which can take several seconds), this line names what is about to happen instead of always saying "Thinking" — for example `Editing file`, `Running command`, `Searching the web`.

Tool calls are folded into one line per "consecutive batch":

- **`N tool calls`**: several consecutive tool calls in one turn merge into this single line and are **collapsed by default** — they don't auto-expand while running either, so tap to open it if you want details. As long as any tool in the batch is still running, **the line itself** carries a sweeping highlight. Tapping the line expands or collapses it manually at any time, and **your manual choice sticks** — it is not overridden by the automatic rules, nor reset to the default by scrolling away or switching to the terminal or settings and back.
- Expanded, each call takes one row and the whole group is **indented by one level**, so you can tell at a glance that these rows belong to the group above rather than being independent content on the same level as the group header.
- For the tool that is running, the end of the row shows **what it is doing** (visible when collapsed too), instead of a vague "Running": `Running command`, `Editing file`, `Reading file`, `Searching the web`, `Generating image`, `Updating todo list`, `Starting subagent`, `Reading/writing memory`; tools whose action can't be recognized (MCP tools and the like) show `Calling tool`.
- Tapping a single row shows its "Instruction" and "Result"; when the content is too long it scrolls inside a fixed-height window, with a fade at the bottom hinting that there is more. A single row's expand/collapse state is remembered as well, so scrolling away and back doesn't close it by itself.
- Tools that modify files (write file, edit file) are **collapsed by default like any other tool**; opened, they show a **diff card**: the file path at the top-left, "Copy" at the top-right to copy this diff, line-numbered colored `+ / −` changes as the body, and a summary of this change at the bottom (e.g. `+3 −0 · 1 file`).

If the bottom of a tool card shows a "Background task / subagent finished" hint (a green dot for success, a red dot for failure or manual termination), it means the background task or subagent finished during this turn and was picked up by the current session, so the AI carries on automatically.

## Full-Screen Image Viewing

Every image in the chat can be opened full screen, in all four places:

- pending image attachments above the input box;
- image attachments in sent message bubbles;
- images shown in the AI's reply body (charts it generated, screenshots, or images it read out of the workspace);
- images the AI sends you with the send tool (or image generation).

Once full screen: pinch with two fingers to zoom, drag with one finger to look at details while zoomed, and double-tap to switch back and forth between the original size and the zoom. Tap the image, press the back key or tap the × at the top-right to close it (tapping the image while zoomed does not close it, so you don't dismiss it by accident while inspecting details). On tablets the large image fills the whole screen whether the chat area takes half the screen or the full screen.

If an image file has been moved or deleted it won't open, and "Failed to load image" is shown. GIFs show the first frame only.

## Files Sent by the AI

Files the AI sends with `sendFile` (and the images it generates) are shown in the chat area **one file per row**: a thumbnail or type icon on the left, the file name with "size · path" in the middle, and a `›` on the right indicating that it can be tapped.

- Tap an image row → full-screen image viewing inside the app; tap another type → it is opened by the matching system app (for an installer you first confirm "Allow installing unknown apps").
- If the file has been moved or deleted, tapping it says "The file doesn't exist or has been moved"; when the location doesn't support sharing with other apps, a matching explanation is given. Files inside an external workspace added with "Add local directory", or inside directories mounted into a custom image, can be opened the same way.
- Each row sits at **the position of the tool call that sent it**, so when reading back a conversation you can see which step the AI sent this file at. Tool calls that carry files **don't take part in the "N tool calls" folding**, and show whether or not the group is collapsed.
- One call sends at most 10 files, with a default limit of 100MB per file (adjustable in "Preferences → Tools"); if even one file is missing or over the limit, the whole batch is not sent, and the AI receives the failure reason and retries after fixing it.

## Previewing Code Artifacts

When the AI's reply contains a **complete HTML document or SVG graphic**, a **"Preview"** pill with an eye icon appears above the code block. Tap it to open it full screen and see the rendered result (charts, page layouts and vector graphics all render).

The preview page has three buttons at the top-right:

- **View code / Preview**: switch back and forth between the rendered result and the highlighted source.
- **Copy**: copy the whole code block to the clipboard.
- **Save**: save it to the root of the current workspace — HTML as `artifact.html`, SVG as `artifact.svg`; name clashes get a number (e.g. `artifact-2.html`), and the save path is shown on success.

The pill only appears when the block is a **complete document** (HTML must start with `<!doctype` or `<html` and contain `</html>`; SVG must start with `<svg` and contain `</svg>`). Ordinary snippets, truncated fragments, blocks in other languages and over-long blocks don't get it.

While previewing, the page cannot reach any local file, and links inside it don't navigate (the preview can't be sent elsewhere). Network resources the page needs (chart libraries on a CDN, remote images) are still loaded over the network. If rendering fails you'll see "Content failed to render — switch to View code to see the source"; the source is still viewable, copyable and savable.

## Ghost Conversations

To keep a turn completely off the record, turn on **Ghost mode**: a "Ghost mode" pill sits at the top of the chat page — tap it to turn it on, tap again to turn it off. Once on, the pill reads "On" and an amber banner reminds you that this session writes no history and no memory, and that its content disappears when it is turned off or you switch away.

While Ghost mode is on:

- **Messages live only in memory**: this turn's conversation is not written to the chat history database or any archive. Kill the app, or switch to another session and come back, and it is gone.
- **No memory writes**: the AI is refused if it tries to write memory in this session, and automatic memory curation skips it. Read-only memory lookups still work.
- **Not searchable**: ghost messages don't enter the chat history, so the sidebar's chat search won't find them.

A few notes:

- The toggle is **per session**, affecting only the session you turned it on in; new sessions default to off.
- The toggle itself is remembered, so after restarting the app that session is still in Ghost mode (but its earlier content has been cleared).
- Turning the toggle off, switching to another session, or deleting the session clears that session's ghost content immediately and **irreversibly**.
- Nothing being written also means very long tool output is only visible in truncated form in later turns; if you need a record, don't use Ghost mode.

## Usage & Timing Below Replies

For messages you send, every bubble shows the send time below it (e.g. `17:55`) along with the copy, revert and more buttons.

The line under an AI reply's body: **stats are counted per turn and hang only under that turn's last reply** (the copy and more buttons appear only on the newest reply in the conversation; the order is "Copy → stats → More options", with the `···` action entry at the end of the row):

- **Token usage**: `↑` is this turn's input tokens and `↓` the output tokens. The model may be called several times within one turn (every tool call sends another request), so this is the sum for the whole turn, not the number for one step.
- **Cache hit rate** (**since v1.11.0-dev**): the percentage next to the database icon is the share of this turn's input that hit the server-side cache, out of the turn's total input. The higher the hit rate, the cheaper and faster it is. It is not shown when nothing hit the cache, or when the vendor doesn't return cache data. This field has been recorded since v1.11.0-dev, so older messages don't show it.
- **Turn duration** (**since v1.11.0-dev**): the time next to the clock icon, shown only under a turn's last reply. It counts from the moment you hit send until the AI wraps up, including tool execution and the time spent waiting for you to approve something. Under a minute it shows as `12s`, and longer as `2:05` (minutes:seconds) or `1:02:05` (hours:minutes:seconds). It is not shown while the task is still running — it appears once the turn is done.

Cumulative usage and cache hit rate for a whole conversation or a whole vendor are on [Token stats](/guide/token-stats).
