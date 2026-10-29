package com.aharou.vd;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * aharou-vd —— 无头虚拟显示屏小工具（参考并改编自 Genymobile/scrcpy 的 NewDisplay 机制，
 * Apache-2.0；创建路径与 flags 与 scrcpy `--new-display` 一致）。
 *
 * 作用：以 shell/root 身份创建一个**不在设备屏幕上显示**的虚拟显示屏，并在本进程里常驻：
 *   - 自带截图（Android 13 的 screencap 不认虚拟屏）；
 *   - 命令通道：App 往控制目录写 `cmd-<序号>.txt`，本进程执行后写回 `res-<序号>.txt`。
 *     点按/滑动/按键直接由本进程注入，不必每一下都经 Shizuku 拉起一个 `input` 进程；
 *     读控件与写文字走 shell 身份的 UiAutomation（uiautomator 用的同一条路），
 *     **不依赖用户开启任何无障碍服务**，也不碰输入法和剪贴板。
 *
 * 运行（shell，经 Shizuku 或 root）：
 *   CLASSPATH=<jar> app_process / com.aharou.vd.VdMain 宽 高 dpi 截图输出 截图请求 显示名 停止文件 [控制目录]
 */
public final class VdMain {

    private static final String PKG = "com.android.shell";

    /** 帧拷贝的最小间隔：画面在动时没必要每帧都拷，省内存带宽。 */
    private static final long FRAME_COPY_MIN_INTERVAL_MS = 100L;

    /** WindowManager.DISPLAY_IME_POLICY_HIDE：这块屏上永不显示输入法，也不回落到主屏。 */
    private static final int DISPLAY_IME_POLICY_HIDE = 2;

    /** dump 最多输出的节点数：再多模型也读不过来，还会把一次返回撑得很大。 */
    private static final int MAX_DUMP_NODES = 300;

    private static Object uiAutomation;
    private static String uiAutomationError;

    public static void main(String[] args) {
        try {
            // ActivityThread 的构造会创建 Handler，必须先给主线程装上 Looper
            Looper.prepareMainLooper();
            int w = args.length > 0 ? Integer.parseInt(args[0]) : 1080;
            int h = args.length > 1 ? Integer.parseInt(args[1]) : 1920;
            int dpi = args.length > 2 ? Integer.parseInt(args[2]) : 440;
            String shotOut = args.length > 3 ? args[3] : null;
            String shotReq = args.length > 4 ? args[4] : null;
            String displayName = args.length > 5 ? args[5] : "aharou-vd";
            String stopFile = args.length > 6 ? args[6] : "/data/local/tmp/aharou-vd.stop";
            String ctlDir = args.length > 7 ? args[7] : null;

            // flags：与 scrcpy 的 new-display 组合一致（13+ 与 14+ 的位全开）
            int flags = 0
                    | (1 << 0)   // PUBLIC
                    | (1 << 1)   // PRESENTATION
                    | (1 << 3)   // OWN_CONTENT_ONLY
                    | (1 << 6)   // SUPPORTS_TOUCH
                    | (1 << 7)   // ROTATES_WITH_CONTENT
                    | (1 << 10)  // TRUSTED
                    | (1 << 11)  // OWN_DISPLAY_GROUP
                    | (1 << 12)  // ALWAYS_UNLOCKED
                    | (1 << 13)  // TOUCH_FEEDBACK_DISABLED
                    | (1 << 14)  // OWN_FOCUS
                    | (1 << 15); // DEVICE_DISPLAY_GROUP

            Context ctx = new ShellContext(getSystemContext());

            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            DisplayManager dm = ctor.newInstance(ctx);

            ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
            Surface surface = reader.getSurface();

            VirtualDisplay vd = dm.createVirtualDisplay(displayName, w, h, dpi, surface, flags);
            if (vd == null) {
                System.out.println("VD_FAILED (null)");
                return;
            }
            int id = vd.getDisplay().getDisplayId();
            // 不设的话，没有系统装饰的虚拟屏会把输入法「回落」到主屏：AI 一点影子屏里的输入框，
            // 用户主屏就弹出键盘、正在打的字被打断。HIDE 让这块屏上的输入框永远不拉起键盘。
            String imePolicy = setImePolicyHidden(id);
            System.out.println("VD_READY id=" + id + " size=" + w + "x" + h + " dpi=" + dpi + " ime=" + imePolicy);
            System.out.flush();

            File ctl = ctlDir != null ? new File(ctlDir) : null;
            if (ctl != null) {
                ctl.mkdirs();
                connectUiAutomation();
                System.out.println("VD_CTL " + (uiAutomation != null ? "ok" : "no-uiautomation " + uiAutomationError));
                System.out.flush();
            }

            new File(stopFile).delete();
            // 保持进程存活（显示屏随之存活）；同时持续排空帧缓冲，避免 BufferQueue 堵塞。
            // 最近一帧另存一份紧凑拷贝：画面静止时系统不再提交新帧，截图就靠它兜住。
            byte[] latest = new byte[w * h * 4];
            boolean latestValid = false;
            long lastCopyAt = 0L;
            while (!new File(stopFile).exists()) {
                Image img = reader.acquireLatestImage();
                if (img != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastCopyAt >= FRAME_COPY_MIN_INTERVAL_MS) {
                        copyToCompact(img, w, h, latest);
                        latestValid = true;
                        lastCopyAt = now;
                    }
                    img.close();
                }
                if (shotReq != null && shotOut != null && new File(shotReq).exists()) {
                    if (latestValid) {
                        writePng(latest, w, h, shotOut);
                        System.out.println("VD_SHOT " + shotOut);
                    } else {
                        System.out.println("VD_SHOT_FAILED no-frame");
                    }
                    System.out.flush();
                    new File(shotReq).delete();
                }
                if (ctl != null) handleCommands(ctl, id);
                Thread.sleep(20);
            }
            disconnectUiAutomation();
            vd.release();
            reader.close();
            System.out.println("VD_STOPPED");
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("VD_ERROR " + t);
        }
    }

    // ------------------------------------------------------------------ 命令通道

    /**
     * 处理控制目录里的命令文件。一行一个参数：首行是动作，其余是参数（文字内容放最后一行之后的全部内容）。
     * 结果写 `res-<序号>.txt`：首行 OK / ERR，第二行起是说明或数据。先写 .tmp 再改名，App 不会读到半截。
     */
    private static void handleCommands(File ctl, int displayId) {
        File[] files = ctl.listFiles((dir, name) -> name.startsWith("cmd-") && name.endsWith(".txt"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files);
        for (File f : files) {
            String seq = f.getName().substring(4, f.getName().length() - 4);
            String reply;
            try {
                String body = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                reply = "OK\n" + execute(body, displayId);
            } catch (Throwable t) {
                reply = "ERR\n" + t;
            }
            f.delete();
            try {
                File tmp = new File(ctl, "res-" + seq + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(reply.getBytes(StandardCharsets.UTF_8));
                }
                tmp.renameTo(new File(ctl, "res-" + seq + ".txt"));
            } catch (Throwable t) {
                System.out.println("VD_CTL_WRITE_FAILED " + t);
            }
        }
    }

    private static String execute(String body, int displayId) throws Exception {
        String[] lines = body.split("\n", -1);
        String action = lines[0].trim();
        switch (action) {
            case "ping":
                return uiAutomation != null ? "uiautomation" : "inject-only " + uiAutomationError;
            case "tap": {
                int x = Integer.parseInt(lines[1].trim());
                int y = Integer.parseInt(lines[2].trim());
                tap(displayId, x, y);
                return "tapped";
            }
            case "swipe": {
                swipe(displayId,
                        Integer.parseInt(lines[1].trim()), Integer.parseInt(lines[2].trim()),
                        Integer.parseInt(lines[3].trim()), Integer.parseInt(lines[4].trim()),
                        Integer.parseInt(lines[5].trim()));
                return "swiped";
            }
            case "key": {
                key(displayId, Integer.parseInt(lines[1].trim()));
                return "keyed";
            }
            case "settext": {
                boolean append = "append".equals(lines[1].trim());
                String text = String.join("\n", Arrays.copyOfRange(lines, 2, lines.length));
                return setFocusedText(displayId, text, append);
            }
            case "dump":
                return dump(displayId);
            default:
                throw new IllegalArgumentException("unknown action: " + action);
        }
    }

    // ------------------------------------------------------------------ 输入注入

    private static void tap(int displayId, int x, int y) throws Exception {
        long down = SystemClock.uptimeMillis();
        inject(motion(down, down, MotionEvent.ACTION_DOWN, x, y), displayId);
        inject(motion(down, down + 50, MotionEvent.ACTION_UP, x, y), displayId);
    }

    private static void swipe(int displayId, int x1, int y1, int x2, int y2, int durationMs) throws Exception {
        long down = SystemClock.uptimeMillis();
        int steps = Math.max(2, durationMs / 16);
        inject(motion(down, down, MotionEvent.ACTION_DOWN, x1, y1), displayId);
        for (int i = 1; i < steps; i++) {
            float t = i / (float) steps;
            long at = down + (long) (durationMs * t);
            inject(motion(down, at, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t), displayId);
            SystemClock.sleep(Math.max(1, durationMs / steps));
        }
        inject(motion(down, down + durationMs, MotionEvent.ACTION_UP, x2, y2), displayId);
    }

    private static void key(int displayId, int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), displayId);
        inject(new KeyEvent(now, now + 20, KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), displayId);
    }

    private static MotionEvent motion(long down, long at, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, at, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return e;
    }

    /**
     * 把事件发到指定显示屏：InputEvent.setDisplayId 是隐藏方法，InputManager.injectInputEvent 也是，
     * 都走反射（scrcpy 同款）。有 UiAutomation 时也不用它注入——它注入的事件不带 displayId 时会落到主屏。
     */
    private static void inject(InputEvent event, int displayId) throws Exception {
        Method setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        setDisplayId.invoke(event, displayId);
        Object im = inputManager();
        // 2 = INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH：等目标窗口处理完再返回，下一个动作不会抢跑。
        Method m = im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        boolean ok = (Boolean) m.invoke(im, event, 2);
        if (!ok) throw new IllegalStateException("inject rejected");
    }

    private static Object inputManagerInstance;

    private static Object inputManager() throws Exception {
        if (inputManagerInstance != null) return inputManagerInstance;
        Class<?> cls = Class.forName("android.hardware.input.InputManager");
        Method getInstance;
        try {
            getInstance = cls.getDeclaredMethod("getInstance");
        } catch (NoSuchMethodException e) {
            // Android 14+ 把单例挪到了 InputManagerGlobal。
            cls = Class.forName("android.hardware.input.InputManagerGlobal");
            getInstance = cls.getDeclaredMethod("getInstance");
        }
        getInstance.setAccessible(true);
        inputManagerInstance = getInstance.invoke(null);
        return inputManagerInstance;
    }

    // ------------------------------------------------------------------ 输入法策略

    private static String setImePolicyHidden(int displayId) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "window");
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            Object wm = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            Method set = wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class);
            set.invoke(wm, displayId, DISPLAY_IME_POLICY_HIDE);
            return "hidden";
        } catch (Throwable t) {
            return "unchanged(" + t.getClass().getSimpleName() + ")";
        }
    }

    // ------------------------------------------------------------------ UiAutomation

    /**
     * 以 shell 身份连上 UiAutomation：这正是 `uiautomator dump` 用的路子，不需要用户开任何无障碍服务。
     * 构造器与 connect 都是隐藏 API，走反射；连不上就只保留输入注入能力，读控件/写文字报不可用。
     */
    private static void connectUiAutomation() {
        try {
            HandlerThread thread = new HandlerThread("aharou-vd-uia");
            thread.start();
            Class<?> connCls = Class.forName("android.app.UiAutomationConnection");
            Constructor<?> connCtor = connCls.getDeclaredConstructor();
            connCtor.setAccessible(true);
            Object connection = connCtor.newInstance();
            Class<?> iConn = Class.forName("android.app.IUiAutomationConnection");
            Constructor<?> uiaCtor = Class.forName("android.app.UiAutomation")
                    .getDeclaredConstructor(Looper.class, iConn);
            uiaCtor.setAccessible(true);
            Object uia = uiaCtor.newInstance(thread.getLooper(), connection);
            Class<?> uiaCls = uia.getClass();
            // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES：不压制用户已开的其他无障碍服务（读屏等）。
            int flag = (Integer) uiaCls.getField("FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES").get(null);
            Method connect = uiaCls.getDeclaredMethod("connect", int.class);
            connect.setAccessible(true);
            connect.invoke(uia, flag);
            uiAutomation = uia;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            uiAutomationError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    private static void disconnectUiAutomation() {
        if (uiAutomation == null) return;
        try {
            Method m = uiAutomation.getClass().getDeclaredMethod("disconnect");
            m.setAccessible(true);
            m.invoke(uiAutomation);
        } catch (Throwable ignored) {
        }
    }

    private static List<AccessibilityNodeInfo> rootsOn(int displayId) throws Exception {
        if (uiAutomation == null) throw new IllegalStateException("uiautomation unavailable: " + uiAutomationError);
        android.app.UiAutomation uia = (android.app.UiAutomation) uiAutomation;
        // 让服务把交互窗口也报上来（默认只给活动窗口），否则虚拟屏上的窗口可能拿不到。
        android.accessibilityservice.AccessibilityServiceInfo info = uia.getServiceInfo();
        if (info != null && (info.flags & android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0) {
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            uia.setServiceInfo(info);
        }
        SparseArray<List<AccessibilityWindowInfo>> all = uia.getWindowsOnAllDisplays();
        List<AccessibilityWindowInfo> windows = all.get(displayId);
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        if (windows == null) return roots;
        for (AccessibilityWindowInfo win : windows) {
            AccessibilityNodeInfo root = win.getRoot();
            if (root != null) roots.add(root);
        }
        return roots;
    }

    /**
     * 列出可交互或带文字的节点：`序号|文字|描述|resource-id|类名|标志|left,top,right,bottom`。
     * 字段里的 `|` 与换行替换掉，App 侧按行、按 `|` 切分即可。
     */
    private static String dump(int displayId) throws Exception {
        StringBuilder out = new StringBuilder();
        int[] count = {0};
        for (AccessibilityNodeInfo root : rootsOn(displayId)) {
            walk(root, out, count);
            if (count[0] >= MAX_DUMP_NODES) break;
        }
        return out.toString();
    }

    private static void walk(AccessibilityNodeInfo node, StringBuilder out, int[] count) {
        if (node == null || count[0] >= MAX_DUMP_NODES) return;
        if (node.isVisibleToUser()) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            boolean interesting = node.isClickable() || node.isEditable() || node.isCheckable()
                    || node.isScrollable() || (text != null && text.length() > 0)
                    || (desc != null && desc.length() > 0);
            if (interesting) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.width() > 0 && r.height() > 0) {
                    String flags = (node.isClickable() ? "c" : "") + (node.isEditable() ? "e" : "")
                            + (node.isCheckable() ? (node.isChecked() ? "X" : "x") : "")
                            + (node.isScrollable() ? "s" : "") + (node.isFocused() ? "f" : "")
                            + (node.isEnabled() ? "" : "-");
                    out.append(count[0]++).append('|')
                            .append(clean(text)).append('|')
                            .append(clean(desc)).append('|')
                            .append(clean(node.getViewIdResourceName())).append('|')
                            .append(clean(node.getClassName())).append('|')
                            .append(flags).append('|')
                            .append(r.left).append(',').append(r.top).append(',')
                            .append(r.right).append(',').append(r.bottom).append('\n');
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), out, count);
    }

    private static String clean(CharSequence s) {
        if (s == null) return "";
        String v = s.toString().replace('|', '/').replace('\n', ' ').replace('\r', ' ');
        return v.length() > 120 ? v.substring(0, 120) : v;
    }

    /**
     * 把文字写进影子屏当前获得焦点的输入框（ACTION_SET_TEXT）：不经输入法、不经剪贴板。
     * [append] 为真时接在原有内容后面。没有焦点输入框时退而找屏上第一个可编辑节点。
     */
    private static String setFocusedText(int displayId, String text, boolean append) throws Exception {
        AccessibilityNodeInfo target = null;
        for (AccessibilityNodeInfo root : rootsOn(displayId)) {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) {
                target = f;
                break;
            }
        }
        if (target == null) {
            for (AccessibilityNodeInfo root : rootsOn(displayId)) {
                target = firstEditable(root);
                if (target != null) break;
            }
        }
        if (target == null) throw new IllegalStateException("no editable field on display " + displayId);
        String value = text;
        if (append) {
            CharSequence old = target.getText();
            // 空输入框常把提示语当 text 报上来，hint 与 text 相同就当作是空的。
            CharSequence hint = target.getHintText();
            if (old != null && (hint == null || !old.toString().contentEquals(hint))) value = old + text;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw new IllegalStateException("set text rejected by " + target.getClassName());
        }
        return "set " + value.length();
    }

    private static AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = firstEditable(node.getChild(i));
            if (hit != null) return hit;
        }
        return null;
    }

    // ------------------------------------------------------------------ 截图

    /** 把 Image 的像素拷成紧凑 RGBA（处理 rowStride / pixelStride 的补齐）。 */
    private static void copyToCompact(Image img, int w, int h, byte[] out) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        if (pixelStride == 4 && rowStride == w * 4) {
            buf.position(0);
            buf.get(out, 0, w * h * 4);
            return;
        }
        for (int y = 0; y < h; y++) {
            int rowStart = y * rowStride;
            for (int x = 0; x < w; x++) {
                int src = rowStart + x * pixelStride;
                int dst = (y * w + x) * 4;
                out[dst] = buf.get(src);
                out[dst + 1] = buf.get(src + 1);
                out[dst + 2] = buf.get(src + 2);
                out[dst + 3] = buf.get(src + 3);
            }
        }
    }

    /** 编码 PNG 写到 [path]：先写 .tmp 再改名，避免 App 读到半截文件。 */
    private static void writePng(byte[] pixels, int w, int h, String path) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
        File target = new File(path);
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(path + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        bmp.recycle();
        if (target.exists()) target.delete();
        tmp.renameTo(target);
    }

    // ------------------------------------------------------------------ 进程环境

    /** 参照 scrcpy Workarounds：手动构造 ActivityThread 并注册为当前实例，再取其 system context。 */
    private static Context getSystemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> threadCtor = at.getDeclaredConstructor();
        threadCtor.setAccessible(true);
        Object thread = threadCtor.newInstance();
        java.lang.reflect.Field sCurrent = at.getDeclaredField("sCurrentActivityThread");
        sCurrent.setAccessible(true);
        sCurrent.set(null, thread);
        java.lang.reflect.Field mSystem = at.getDeclaredField("mSystemThread");
        mSystem.setAccessible(true);
        mSystem.setBoolean(thread, true);
        Method getSystemContext = at.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContext.invoke(thread);
    }

    /** 最小 ContextWrapper：包名/attribution 按 shell 记，让 display 服务的权限检查归到 shell 名下。 */
    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return PKG;
        }

        @Override
        public String getOpPackageName() {
            return PKG;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.SHELL_UID)
                    .setPackageName(PKG)
                    .build();
        }
    }
}
