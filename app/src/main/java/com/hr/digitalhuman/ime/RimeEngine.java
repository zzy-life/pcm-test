package com.hr.digitalhuman.ime;

import android.content.Context;
import android.content.res.AssetManager;

import com.hr.digitalhuman.debug.DebugLog;
import com.osfans.trime.core.Rime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把 librime 接到现有软键盘：整段拼音由引擎组词，界面仍用 SoftImePanel。
 */
public final class RimeEngine {

    private static final String TAG = "RimeEngine";
    private static final String STAMP = "hr-pinyin-3";
    private static final int XK_BACKSPACE = 0xff08;

    private static final Object LOCK = new Object();
    private static List<Integer> lastCandidateMap = new ArrayList<Integer>();
    private static boolean starting;
    private static boolean ready;

    private RimeEngine() {
    }

    public static void warmUp(Context context) {
        if (context == null || !Rime.isLoaded()) {
            return;
        }
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (ready || starting) {
                return;
            }
            starting = true;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ok = deployAndStart(app);
                } catch (Throwable t) {
                    DebugLog.w(TAG, "rime start failed: " + t.getMessage());
                }
                synchronized (LOCK) {
                    ready = ok;
                    starting = false;
                }
                DebugLog.i(TAG, ok ? "rime ready" : "rime unavailable, keep builtin dict");
            }
        }, "rime-deploy");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    public static boolean isReady() {
        synchronized (LOCK) {
            return ready;
        }
    }

    public static boolean hasComposition() {
        synchronized (LOCK) {
            return ready && Rime.isComposing();
        }
    }

    public static String preedit() {
        synchronized (LOCK) {
            return ready ? Rime.preedit() : "";
        }
    }

    public static List<String> candidates() {
        synchronized (LOCK) {
            if (!ready) {
                return Collections.emptyList();
            }
            String[] texts = Rime.candidateTexts();
            List<String> list = new ArrayList<String>(texts.length);
            List<Integer> map = new ArrayList<Integer>(texts.length);
            for (int i = 0; i < texts.length; i++) {
                if (SimplifiedHan.isInputHan(texts[i])) {
                    list.add(texts[i]);
                    map.add(i);
                }
            }
            lastCandidateMap = map;
            return list;
        }
    }

    /** @return 本次新上屏的文字；引擎未就绪时返回 null，调用方走旧词库 */
    public static String onLetter(char ch) {
        if (ch < 'a' || ch > 'z') {
            return null;
        }
        return press(ch);
    }

    public static String onBackspace() {
        return press(XK_BACKSPACE);
    }

    public static String onSpace() {
        return press(' ');
    }

    public static String onSelect(int index) {
        synchronized (LOCK) {
            if (!ready) {
                return null;
            }
            int raw = index;
            if (index >= 0 && index < lastCandidateMap.size()) {
                raw = lastCandidateMap.get(index);
            }
            Rime.selectOnPage(raw);
            return Rime.takeCommit();
        }
    }

    public static String commitAll() {
        synchronized (LOCK) {
            if (!ready) {
                return null;
            }
            if (!Rime.isComposing()) {
                return "";
            }
            Rime.commitComposition();
            String text = Rime.takeCommit();
            if (text.length() == 0 && Rime.isComposing()) {
                Rime.selectOnPage(0);
                text = Rime.takeCommit();
            }
            return text;
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            if (!ready) {
                return;
            }
            Rime.clearComposition();
        }
    }

    private static String press(int keycode) {
        synchronized (LOCK) {
            if (!ready) {
                return null;
            }
            Rime.press(keycode);
            return Rime.takeCommit();
        }
    }

    private static boolean deployAndStart(Context context) throws Exception {
        File root = new File(context.getFilesDir(), "rime");
        File shared = new File(root, "shared");
        File user = new File(root, "user");
        if (!shared.exists() && !shared.mkdirs()) {
            return false;
        }
        if (!user.exists() && !user.mkdirs()) {
            return false;
        }
        File stamp = new File(user, STAMP);
        boolean full = !stamp.exists();
        if (full) {
            copyAssetDir(context.getAssets(), "rime", shared);
            deleteDir(user);
            if (!user.mkdirs()) {
                return false;
            }
        }
        boolean ok = Rime.startup(shared.getAbsolutePath(), user.getAbsolutePath(), full);
        String schema = Rime.get_current_schema();
        DebugLog.i(TAG, "schema=" + schema + " full=" + full);
        boolean schemaOk = ok && "hr_pinyin".equals(schema);
        if (schemaOk && !stamp.exists()) {
            new FileOutputStream(stamp).close();
        }
        return schemaOk;
    }

    private static void copyAssetDir(AssetManager assets, String assetPath, File dest) throws Exception {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetFile(assets, assetPath, dest);
            return;
        }
        if (!dest.exists() && !dest.mkdirs()) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            copyAssetDir(assets, assetPath + "/" + children[i], new File(dest, children[i]));
        }
    }

    private static void copyAssetFile(AssetManager assets, String assetPath, File dest) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        InputStream in = assets.open(assetPath);
        FileOutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                if (children[i].isDirectory()) {
                    deleteDir(children[i]);
                } else {
                    children[i].delete();
                }
            }
        }
        dir.delete();
    }
}
