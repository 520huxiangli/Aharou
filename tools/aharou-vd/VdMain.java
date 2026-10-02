package com.aharou.vd;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * aharou-vd —— 无头虚拟显示屏小工具（参考并改编自 Genymobile/scrcpy 的 NewDisplay 机制，
 * Apache-2.0；创建路径与 flags 与 scrcpy `--new-display` 一致）。
 *
 * 作用：以 shell/root 身份创建一个**不在设备屏幕上显示**的虚拟显示屏；配合：
 *   - 启动应用：`am start --display <id> -n pkg/act`
 *   - 截图：见下方「自带截图」
 *   - 触控：`input -d <id> tap x y`
 * 即可全程离屏运行/操作任意 App，主屏零打扰。
 *
 * 自带截图：Android 13 的 screencap 只认物理屏 token，对虚拟屏会静默写出 0 字节文件
 * （14+ 才支持虚拟屏）。因此由本进程自己出图——启动时多传「截图输出 截图请求」两个路径，
 * App 侧 touch 请求文件即触发把最近一帧编码成 PNG 写到输出路径（输出路径须落在 App 可读处）。
 *
 * 运行（shell，经 Shizuku 或 root）：
 *   CLASSPATH=/data/local/tmp/aharou-vd.jar app_process / com.aharou.vd.VdMain [宽] [高] [dpi] [截图输出] [截图请求]
 * 停止：touch /data/local/tmp/aharou-vd.stop  （或直接杀进程）
 */
public final class VdMain {

    private static final String PKG = "com.android.shell";
    private static final String STOP_FILE = "/data/local/tmp/aharou-vd.stop";

    /** 帧拷贝的最小间隔：画面在动时没必要每帧都拷，省内存带宽。 */
    private static final long FRAME_COPY_MIN_INTERVAL_MS = 100L;

    public static void main(String[] args) {
        try {
            // ActivityThread 的构造会创建 Handler，必须先给主线程装上 Looper
            Looper.prepareMainLooper();
            int w = args.length > 0 ? Integer.parseInt(args[0]) : 1080;
            int h = args.length > 1 ? Integer.parseInt(args[1]) : 1920;
            int dpi = args.length > 2 ? Integer.parseInt(args[2]) : 440;
            String shotOut = args.length > 3 ? args[3] : null;
            String shotReq = args.length > 4 ? args[4] : null;

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
            // 最近一帧另存一份紧凑拷贝：画面静止时系统不再提交新帧，截图就靠它兜住。
            byte[] latest = new byte[w * h * 4];
            boolean latestValid = false;
            long lastCopyAt = 0L;
            while (!new File(STOP_FILE).exists()) {
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
