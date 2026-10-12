package com.aharou.feature.browser

import com.aharou.R

/**
 * All browser actions available to the agent, mirroring iOS BrowserAction.
 */
enum class BrowserAction(val value: String) {
    NAVIGATE("navigate"),
    SCREENSHOT("screenshot"),
    CLICK("click"),
    TYPE("type"),
    GET_TEXT("get_text"),
    SCROLL("scroll"),
    GET_PAGE_INFO("get_page_info"),
    EXECUTE_JS("execute_js"),
    FIND_ELEMENTS("find_elements"),
    HOVER("hover"),
    GET_READABLE("get_readable"),
    SET_USER_AGENT("set_user_agent"),
    SET_VIEWPORT("set_viewport"),
    GET_BACKBONE("get_backbone"),
    FETCH("fetch"),
    NEW_TAB("new_tab"),
    CLOSE_TAB("close_tab"),
    LIST_TABS("list_tabs"),
    GET_COOKIES("get_cookies"),
    SET_COOKIES("set_cookies"),
    SCROLL_AND_COLLECT("scroll_and_collect"),
    WAIT_FOR_DOM_STABLE("wait_for_dom_stable"),
    CONSOLE("console"),
    WAIT("wait"),
    PRESS("press"),
    SELECT("select");

    /**
     * [T-browser-readaction-follow-tab-and-yolo-android] True when this action
     * opens / replaces the page (navigate) and may therefore fan out to
     * a fresh tab under the implicit-tab grace window. False for
     * operate-current-page actions (execute_js, get_text, click, …) which must
     * follow selectedTabId.
     */
    val opensNewPage: Boolean get() = this in opensNewPageActions

    companion object {
        private val map = entries.associateBy { it.value }
        fun fromString(s: String): BrowserAction? = map[s]

        /** Actions that visually change the page and warrant an auto-snapshot. */
        val visualChangeActions: Set<BrowserAction> = setOf(
            NAVIGATE, CLICK, SCROLL, HOVER, TYPE
        )

        /**
         * [T-browser-readaction-follow-tab-and-yolo-android] Actions that open
         * or replace the page in a tab — these are the ONLY actions that should
         * fan out to a fresh tab when an implicit (tab_id-less) call lands while
         * the previously-used tab is still inside its inUse grace window. The
         * grace-based fan-out exists so N back-to-back *navigates* open N
         * distinct tabs instead of trampling tab 0 (issue #595).
         *
         * Every other action operates on the CURRENT page (read DOM, click,
         * scroll, screenshot, …). For those, fanning out to a fresh/other tab
         * is exactly the #612/#614 bug: `navigate A → execute_js` ran the JS on
         * a different (blank or stale) tab because navigate's tab was still in
         * grace. Those actions must follow `selectedTabId` (the most-recently
         * navigated tab), never fan out. See [opensNewPage].
         *
         * NEW_TAB / CLOSE_TAB / LIST_TABS / SET_VIEWPORT are handled at the pool
         * level before tab acquisition, so they're intentionally absent here.
         */
        val opensNewPageActions: Set<BrowserAction> = setOf(
            NAVIGATE
        )

        val allValues: List<String> = entries.map { it.value }
    }
}

enum class ScrollDirection(val value: String) {
    UP("up"), DOWN("down");
    companion object {
        fun fromString(s: String): ScrollDirection? = entries.find { it.value == s }
    }
}

/**
 * User-Agent 分档。除 7 个可选择档位外，保留 [MOBILE_CHROME] / [DESKTOP_CHROME] 两个旧
 * 枚举名——它们可能已经被写进 SharedPreferences（`browser_prefs.user_agent_profile`），
 * 删掉会让旧选择在重启后解析失败。旧值在选择界面通过 [migratedForUi] 映射到最接近的新档位。
 *
 * [DEFAULT] 的 [userAgentString] 为 null，表示「沿用系统默认 UA」——由 [BrowserUseManager]
 * 在改写 UA 之前缓存设备的原始 UA 字符串并在切回该档位时还原。
 */
enum class UserAgentProfile(
    val value: String,
    val labelRes: Int,
    val descRes: Int,
    val userAgentString: String? = null,
    val viewportSize: Pair<Int, Int> = 412 to 915,
    /** 桌面档位：用于视口宽度与 UA 是否匹配的提示（见 BrowserSettingsSheet）。 */
    val isDesktop: Boolean = false,
) {
    DEFAULT(
        "default", R.string.browser_ua_default, R.string.browser_ua_default_desc,
    ),
    MOBILE_CHROME(
        "mobile_chrome", R.string.browser_settings_ua_mobile_chrome, R.string.browser_ua_mobile_chrome_desc,
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36",
    ),
    ANDROID_PHONE(
        "android_phone", R.string.browser_ua_android_phone, R.string.browser_ua_android_phone_desc,
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
    ),
    ANDROID_TABLET(
        "android_tablet", R.string.browser_ua_android_tablet, R.string.browser_ua_android_tablet_desc,
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        800 to 1280,
    ),
    DESKTOP_CHROME(
        "desktop_chrome", R.string.browser_settings_ua_desktop_chrome, R.string.browser_ua_desktop_chrome_desc,
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        1280 to 800, true,
    ),
    DESKTOP_PC(
        "desktop_pc", R.string.browser_ua_desktop_pc, R.string.browser_ua_desktop_pc_desc,
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        1280 to 800, true,
    ),
    IPHONE(
        "iphone", R.string.browser_ua_iphone, R.string.browser_ua_iphone_desc,
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
        390 to 844,
    ),
    IPAD(
        "ipad", R.string.browser_ua_ipad, R.string.browser_ua_ipad_desc,
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
        820 to 1180,
    ),
    CUSTOM(
        "custom", R.string.browser_settings_ua_custom, R.string.browser_ua_custom_desc,
    );

    companion object {
        fun fromString(s: String): UserAgentProfile? = entries.find { it.value == s }

        /** 选择界面展示的 7 个档位（旧枚举名不进入列表）。 */
        val selectableProfiles: List<UserAgentProfile> = listOf(
            DEFAULT, ANDROID_PHONE, ANDROID_TABLET, DESKTOP_PC, IPHONE, IPAD, CUSTOM
        )

        /** 旧持久化值映射到最接近的新档位，仅供选择界面预选。 */
        fun migratedForUi(profile: UserAgentProfile): UserAgentProfile = when (profile) {
            MOBILE_CHROME -> ANDROID_PHONE
            DESKTOP_CHROME -> DESKTOP_PC
            else -> profile
        }
    }
}
