package com.osfans.trime.core;

/**
 * JNI 入口，类名和字段必须与同文输入法 3.2.9 的 librime_jni.so 一致。
 * 只保留启动、按键、候选、上屏，不依赖同文界面。
 */
public class Rime {

    public static class RimeComposition {
        int length;
        int cursor_pos;
        int sel_start;
        int sel_end;
        String preedit;
        byte[] bytes;
    }

    public static class RimeCandidate {
        public String text;
        public String comment;

        public RimeCandidate() {
        }

        public RimeCandidate(String text, String comment) {
            this.text = text;
            this.comment = comment;
        }
    }

    public static class RimeMenu {
        int page_size;
        int page_no;
        boolean is_last_page;
        int highlighted_candidate_index;
        int num_candidates;
        RimeCandidate[] candidates;
        String select_keys;
    }

    public static class RimeCommit {
        int data_size;
        String text;
    }

    public static class RimeContext {
        int data_size;
        RimeComposition composition;
        RimeMenu menu;
        String commit_text_preview;
        String[] select_labels;
    }

    public static class RimeStatus {
        int data_size;
        String schema_id;
        String schema_name;
        boolean is_disabled;
        boolean is_composing;
        boolean is_ascii_mode;
        boolean is_full_shape;
        boolean is_simplified;
        boolean is_traditional;
        boolean is_ascii_punct;
    }

    private static final RimeCommit COMMIT = new RimeCommit();
    private static final RimeContext CONTEXT = new RimeContext();
    private static boolean loaded;

    static {
        try {
            System.loadLibrary("rime_jni");
            loaded = true;
        } catch (Throwable ignored) {
            loaded = false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /** 原生通知回调，部署过程中会调用，不能删。 */
    public static void handleRimeNotification(String messageType, String messageValue) {
    }

    public static boolean startup(String sharedDir, String userDir, boolean fullCheck) {
        if (!loaded) {
            return false;
        }
        setup(sharedDir, userDir);
        initialize(sharedDir, userDir);
        if (fullCheck && start_maintenance(true) && is_maintenance_mode()) {
            join_maintenance_thread();
        }
        set_notification_handler();
        if (!find_session() && create_session() == 0) {
            return false;
        }
        String schema = get_current_schema();
        if (schema == null || schema.length() == 0 || ".default".equals(schema)) {
            if (!select_schema("hr_pinyin")) {
                return false;
            }
        }
        set_option("ascii_mode", false);
        set_option("zh_hans", true);
        return true;
    }

    public static void shutdown() {
        if (!loaded) {
            return;
        }
        try {
            destroy_session();
            finalize1();
        } catch (Throwable ignored) {
        }
    }

    public static boolean press(int keycode) {
        boolean handled = process_key(keycode, 0);
        refresh();
        return handled;
    }

    public static void refresh() {
        COMMIT.text = null;
        get_commit(COMMIT);
        get_context(CONTEXT);
    }

    public static String takeCommit() {
        String text = COMMIT.text;
        COMMIT.text = null;
        return text == null ? "" : text;
    }

    public static boolean isComposing() {
        String input = get_input();
        return input != null && input.length() > 0;
    }

    public static String preedit() {
        if (CONTEXT.composition != null && CONTEXT.composition.preedit != null
                && CONTEXT.composition.preedit.length() > 0) {
            return CONTEXT.composition.preedit;
        }
        String input = get_input();
        return input == null ? "" : input;
    }

    public static String[] candidateTexts() {
        if (CONTEXT.menu == null || CONTEXT.menu.candidates == null) {
            return new String[0];
        }
        RimeCandidate[] raw = CONTEXT.menu.candidates;
        int n = CONTEXT.menu.num_candidates;
        if (n <= 0 || n > raw.length) {
            n = raw.length;
        }
        String[] out = new String[n];
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (raw[i] != null && raw[i].text != null && raw[i].text.length() > 0) {
                out[w++] = raw[i].text;
            }
        }
        if (w == out.length) {
            return out;
        }
        String[] trimmed = new String[w];
        System.arraycopy(out, 0, trimmed, 0, w);
        return trimmed;
    }

    public static boolean selectOnPage(int index) {
        boolean ok = select_candidate_on_current_page(index);
        refresh();
        return ok;
    }

    public static boolean commitComposition() {
        boolean ok = commit_composition();
        refresh();
        return ok;
    }

    public static void clearComposition() {
        clear_composition();
        COMMIT.text = null;
        refresh();
    }

    public static native void setup(String sharedDataDir, String userDataDir);

    public static native void set_notification_handler();

    public static native void initialize(String sharedDataDir, String userDataDir);

    public static native void finalize1();

    public static native boolean start_maintenance(boolean fullCheck);

    public static native boolean is_maintenance_mode();

    public static native void join_maintenance_thread();

    public static native boolean process_key(int keycode, int mask);

    public static native boolean commit_composition();

    public static native void clear_composition();

    public static native boolean get_commit(RimeCommit commit);

    public static native boolean get_context(RimeContext context);

    public static native void set_option(String option, boolean value);

    public static native boolean find_session();

    public static native int create_session();

    public static native boolean destroy_session();

    public static native String get_current_schema();

    public static native boolean select_schema(String schemaId);

    public static native String get_input();

    public static native boolean select_candidate_on_current_page(int index);
}
