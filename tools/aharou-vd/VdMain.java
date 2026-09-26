package com.aharou.vd;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * aharou-vd —— 无头虚拟显示屏小工具（参考并改编自 Genymobile/scrcpy 的 NewDisplay 机制，
 * Apache-2.0；创建路径与 flags 与 scrcpy `--new-display` 一致）。
 *
 * 作用：以 shell 身份创建一个**不在设备屏幕上显示**的虚拟显示屏；配合：
 *   - 启动应用：`am start --display <id> -n pkg/act`
 *   - 截图：    `screencap -d <id> -p /sdcard/xxx.png`
 *   - 触控：    `input -d <id> tap x y`
 * 即可全程离屏运行/操作任意 App，主屏零打扰。
 *
 * 运行（shell，经 Shizuku）：
 *   CLASSPATH=/data/local/tmp/aharou-vd.jar app_process / com.aharou.vd.VdMain [宽] [高] [dpi]
 * 停止：touch /data/local/tmp/aharou-vd.stop  （或直接杀进程）
 */
public final class VdMain {

    private static final String PKG = "com.android.shell";
    private static final String STOP_FILE = "/data/local/tmp/aharou-vd.stop";

    public static void main(String[] args) {
        try {
            // ActivityThread 的构造会创建 Handler，必须先给主线程装上 Looper
            Looper.prepareMainLooper();
            int w = args.length > 0 ? Integer.parseInt(args[0]) : 1080;
            int h = args.length > 1 ? Integer.parseInt(args[1]) : 1920;
            int dpi = args.length > 2 ? Integer.parseInt(args[2]) : 440;

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

            VirtualDisplay vd = dm.createVirtualDisplay("aharou-vd", w, h, dpi, surface, flags);
            if (vd == null) {
                System.out.println("VD_FAILED (null)");
                return;
            }
            int id = vd.getDisplay().getDisplayId();
            System.out.println("VD_READY id=" + id + " size=" + w + "x" + h + " dpi=" + dpi);
            System.out.flush();

            new File(STOP_FILE).delete();
            // 保持进程存活（显示屏随之存活）；同时持续排空帧缓冲，避免 BufferQueue 堵塞。
            while (!new File(STOP_FILE).exists()) {
                Image img = reader.acquireLatestImage();
                if (img != null) {
                    img.close();
                }
                Thread.sleep(40);
            }
            vd.release();
            reader.close();
            System.out.println("VD_STOPPED");
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("VD_ERROR " + t);
        }
    }

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
