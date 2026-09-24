package com.hr.digitalhuman.ime;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 应用内中文拼音软键盘：拼音候选 + 上屏后联想补全（类搜狗）。
 */
public class SoftImePanel extends LinearLayout {

    public interface Listener {
        void onHideRequested();
    }

    private static final String[][] ROWS_CN = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"},
            {"123", "中/英", "，", "空格", "。", "换行", "收起"}
    };
    private static final String[][] ROWS_EN = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"},
            {"123", "中/英", ",", "空格", ".", "换行", "收起"}
    };
    private static final String[][] ROWS_NUM = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"-", "/", ":", ";", "(", ")", "￥", "@", "\"", "⌫"},
            {".", ",", "?", "!", "'", "空格", "ABC", "收起"}
    };

    private enum Mode {CN, EN, NUM}

    private final PinyinDictionary dictionary;
    private final HorizontalScrollView candidateHost;
    private final LinearLayout candidateRow;
    private final TextView composingView;
    private final LinearLayout keysContainer;
    private EditText target;
    private Listener listener;
    private Mode mode = Mode.CN;
    private boolean shift;
    private final StringBuilder composing = new StringBuilder();
    private final List<String> candidates = new ArrayList<>();
    private final List<PinyinDictionary.Match> candidateMatches = new ArrayList<>();
    private int candidatePage;
    /** 当前是否展示「联想」而非拼音候选 */
    private boolean associationMode;
    private String lastCommittedWord = "";

    public SoftImePanel(Context context) {
        this(context, null);
    }

    public SoftImePanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        dictionary = PinyinDictionary.get(context);
        setOrientation(VERTICAL);
        // 半透明，让页面背景透出来，同时保留按键对比度
        setBackgroundColor(0x66101828);
        int pad = dp(4);
        setPadding(pad, pad, pad, pad);
        setElevationCompat(4f);

        composingView = new TextView(context);
        composingView.setTextColor(0xFF8EBEFF);
        composingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        composingView.setPadding(dp(6), 0, dp(6), dp(2));
        composingView.setVisibility(GONE);
        addView(composingView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        candidateHost = new HorizontalScrollView(context);
        candidateHost.setHorizontalScrollBarEnabled(false);
        candidateHost.setVisibility(GONE);
        candidateRow = new LinearLayout(context);
        candidateRow.setOrientation(HORIZONTAL);
        candidateRow.setGravity(Gravity.CENTER_VERTICAL);
        candidateHost.addView(candidateRow);
        LayoutParams candLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(28));
        candLp.bottomMargin = dp(2);
        addView(candidateHost, candLp);

        keysContainer = new LinearLayout(context);
        keysContainer.setOrientation(VERTICAL);
        addView(keysContainer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        rebuildKeys();
        // 防止点击面板时失焦
        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return true;
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void attachTarget(EditText editText) {
        this.target = editText;
        composing.setLength(0);
        lastCommittedWord = "";
        associationMode = false;
        candidatePage = 0;
        refreshCandidates();
    }

    public EditText getTarget() {
        return target;
    }

    private void rebuildKeys() {
        keysContainer.removeAllViews();
        String[][] rows = mode == Mode.NUM ? ROWS_NUM : (mode == Mode.EN ? ROWS_EN : ROWS_CN);
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            LayoutParams lineLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(30));
            lineLp.bottomMargin = dp(2);
            line.setLayoutParams(lineLp);
            for (String label : row) {
                TextView key = buildKey(label);
                float weight = keyWeight(label);
                LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
                lp.leftMargin = dp(2);
                lp.rightMargin = dp(2);
                key.setLayoutParams(lp);
                line.addView(key);
            }
            keysContainer.addView(line);
        }
    }

    private float keyWeight(String label) {
        if ("空格".equals(label)) {
            return 3.2f;
        }
        if ("换行".equals(label) || "收起".equals(label) || "中/英".equals(label)
                || "123".equals(label) || "ABC".equals(label) || "⇧".equals(label)
                || "⌫".equals(label)) {
            return 1.6f;
        }
        return 1f;
    }

    private TextView buildKey(final String label) {
        TextView tv = new TextView(getContext());
        tv.setText(displayLabel(label));
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, isSpecial(label) ? 11 : 14);
        if ("空格".equals(label) || "换行".equals(label) || "收起".equals(label)) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        tv.setBackground(keyBg(isSpecial(label)));
        tv.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onKey(label);
            }
        });
        return tv;
    }

    private String displayLabel(String label) {
        if (mode == Mode.EN && label.length() == 1 && label.charAt(0) >= 'a' && label.charAt(0) <= 'z') {
            return shift ? label.toUpperCase(Locale.US) : label;
        }
        if ("⇧".equals(label)) {
            return shift ? "⇪" : "⇧";
        }
        return label;
    }

    private boolean isSpecial(String label) {
        return "空格".equals(label) || "换行".equals(label) || "收起".equals(label)
                || "中/英".equals(label) || "123".equals(label) || "ABC".equals(label)
                || "⇧".equals(label) || "⌫".equals(label);
    }

    private GradientDrawable keyBg(boolean special) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(special ? 0xB31B4A7A : 0xB3243A55);
        bg.setStroke(dp(1), 0x551B6BDB);
        return bg;
    }

    private void onKey(String label) {
        if ("收起".equals(label)) {
            if (listener != null) {
                listener.onHideRequested();
            }
            return;
        }
        if ("中/英".equals(label)) {
            mode = mode == Mode.CN ? Mode.EN : Mode.CN;
            composing.setLength(0);
            lastCommittedWord = "";
            associationMode = false;
            shift = false;
            rebuildKeys();
            refreshCandidates();
            return;
        }
        if ("123".equals(label)) {
            mode = Mode.NUM;
            composing.setLength(0);
            lastCommittedWord = "";
            associationMode = false;
            rebuildKeys();
            refreshCandidates();
            return;
        }
        if ("ABC".equals(label)) {
            mode = Mode.CN;
            rebuildKeys();
            return;
        }
        if ("⇧".equals(label)) {
            shift = !shift;
            rebuildKeys();
            return;
        }
        if ("⌫".equals(label)) {
            handleDelete();
            return;
        }
        if ("空格".equals(label)) {
            handleSpace();
            return;
        }
        if ("换行".equals(label)) {
            if (finishRimeBeforeSymbol()) {
                commitText("\n");
                refreshCandidates();
                return;
            }
            lastCommittedWord = "";
            associationMode = false;
            commitText("\n");
            refreshCandidates();
            return;
        }
        if ("，".equals(label) || "。".equals(label) || ",".equals(label) || ".".equals(label)
                || "-".equals(label) || "/".equals(label) || ":".equals(label) || ";".equals(label)
                || "(".equals(label) || ")".equals(label) || "￥".equals(label) || "@".equals(label)
                || "\"".equals(label) || "?".equals(label) || "!".equals(label) || "'".equals(label)) {
            if (finishRimeBeforeSymbol()) {
                commitText(label);
                refreshCandidates();
                return;
            }
            if (mode == Mode.CN && composing.length() > 0) {
                commitFirstCandidateOrComposing();
            }
            lastCommittedWord = "";
            associationMode = false;
            commitText(label);
            refreshCandidates();
            return;
        }
        if (label.length() == 1 && Character.isDigit(label.charAt(0))) {
            int digit = label.charAt(0) - '0';
            // 拼音或联想：数字键 1-9 选候选
            if (mode == Mode.CN && digit >= 1 && digit <= 9
                    && (composing.length() > 0 || associationMode)) {
                int idx = candidatePage * 9 + (digit - 1);
                if (idx < candidates.size()) {
                    pickCandidateAt(idx);
                    return;
                }
            }
            if (mode == Mode.CN && composing.length() > 0) {
                commitFirstCandidateOrComposing();
            }
            lastCommittedWord = "";
            associationMode = false;
            commitText(label);
            refreshCandidates();
            return;
        }
        if (label.length() == 1 && Character.isLetter(label.charAt(0))) {
            char ch = label.charAt(0);
            if (mode == Mode.EN) {
                lastCommittedWord = "";
                associationMode = false;
                commitText(String.valueOf(shift ? Character.toUpperCase(ch) : Character.toLowerCase(ch)));
                if (shift) {
                    shift = false;
                    rebuildKeys();
                }
            } else if (mode == Mode.CN) {
                associationMode = false;
                composing.append(Character.toLowerCase(ch));
                candidatePage = 0;
                refreshCandidates();
            } else {
                commitText(label);
            }
        }
    }

    /** 已组好的拼音先上屏，再继续处理标点。 */
    private boolean finishRimeBeforeSymbol() {
        if (mode == Mode.CN && composing.length() > 0) {
            commitFirstCandidateOrComposing();
            return true;
        }
        return false;
    }

    private void handleDelete() {
        if (mode == Mode.CN && composing.length() > 0) {
            composing.deleteCharAt(composing.length() - 1);
            candidatePage = 0;
            refreshCandidates();
            return;
        }
        if (target == null) {
            return;
        }
        Editable ed = target.getText();
        int start = target.getSelectionStart();
        int end = target.getSelectionEnd();
        if (start < 0) {
            start = ed == null ? 0 : ed.length();
            end = start;
        }
        if (ed == null) {
            return;
        }
        if (start != end) {
            ed.delete(Math.min(start, end), Math.max(start, end));
        } else if (start > 0) {
            ed.delete(start - 1, start);
        }
        lastCommittedWord = "";
        associationMode = false;
        if (mode == Mode.CN) {
            refreshCandidates();
        }
    }

    private void handleSpace() {
        if (mode == Mode.CN && composing.length() > 0) {
            commitFirstCandidateOrComposing();
            return;
        }
        if (mode == Mode.CN && associationMode && !candidates.isEmpty()) {
            pickCandidateAt(0);
            return;
        }
        lastCommittedWord = "";
        associationMode = false;
        commitText(" ");
        refreshCandidates();
    }

    private void commitFirstCandidateOrComposing() {
        if (!candidates.isEmpty()) {
            pickCandidateAt(0);
        } else if (composing.length() > 0) {
            commitText(composing.toString());
            composing.setLength(0);
            lastCommittedWord = "";
            associationMode = false;
            candidatePage = 0;
            refreshCandidates();
        }
    }

    private void pickCandidate(String word) {
        if (word == null || word.isEmpty()) {
            return;
        }
        int idx = candidates.indexOf(word);
        if (idx >= 0) {
            pickCandidateAt(idx);
            return;
        }
        commitText(word);
        composing.setLength(0);
        lastCommittedWord = word;
        associationMode = false;
        candidatePage = 0;
        refreshCandidates();
    }

    private void pickCandidateAt(int index) {
        if (index < 0 || index >= candidates.size()) {
            return;
        }
        String word = candidates.get(index);
        if (associationMode) {
            commitText(word);
            lastCommittedWord = word;
            composing.setLength(0);
            candidatePage = 0;
            refreshCandidates();
            return;
        }
        int consume = composing.length();
        if (index < candidateMatches.size()) {
            consume = candidateMatches.get(index).consume;
        } else {
            consume = dictionary.consumeLength(composing.toString(), word);
        }
        if (consume <= 0 || consume > composing.length()) {
            consume = composing.length();
        }
        commitText(word);
        composing.delete(0, consume);
        lastCommittedWord = word;
        candidatePage = 0;
        refreshCandidates();
    }

    private void commitText(String text) {
        if (target == null || text == null) {
            return;
        }
        Editable ed = target.getText();
        if (ed == null) {
            target.setText(text);
            target.setSelection(text.length());
            return;
        }
        int start = target.getSelectionStart();
        int end = target.getSelectionEnd();
        if (start < 0) {
            start = ed.length();
            end = start;
        }
        int a = Math.min(start, end);
        int b = Math.max(start, end);
        ed.replace(a, b, text);
        target.setSelection(a + text.length());
    }

    private void refreshCandidates() {
        candidateRow.removeAllViews();
        candidates.clear();
        candidateMatches.clear();
        if (mode != Mode.CN) {
            composingView.setVisibility(GONE);
            composingView.setText("");
            candidateHost.setVisibility(GONE);
            associationMode = false;
            return;
        }
        // 无拼音时：展示联想补全
        if (composing.length() == 0) {
            showAssociationCandidates();
            return;
        }
        associationMode = false;
        composingView.setVisibility(VISIBLE);
        composingView.setText(dictionary.formatComposing(composing.toString()));
        List<PinyinDictionary.Match> matches = dictionary.suggestMatches(composing.toString());
        candidateMatches.addAll(matches);
        for (PinyinDictionary.Match m : matches) {
            candidates.add(m.word);
        }
        renderCandidateChips(false);
    }

    private void showAssociationCandidates() {
        String seed = resolveAssociationSeed();
        List<String> assoc = seed.isEmpty()
                ? Collections.<String>emptyList()
                : dictionary.associate(seed);
        if (assoc.isEmpty()) {
            associationMode = false;
            composingView.setVisibility(GONE);
            composingView.setText("");
            candidateHost.setVisibility(GONE);
            return;
        }
        associationMode = true;
        composingView.setVisibility(VISIBLE);
        composingView.setText("联想：" + seed);
        candidates.addAll(assoc);
        renderCandidateChips(true);
    }

    private String resolveAssociationSeed() {
        if (lastCommittedWord != null && !lastCommittedWord.isEmpty()) {
            return lastCommittedWord;
        }
        if (target == null || target.getText() == null) {
            return "";
        }
        Editable ed = target.getText();
        int cursor = target.getSelectionStart();
        if (cursor < 0) {
            cursor = ed.length();
        }
        CharSequence before = ed.subSequence(0, Math.min(cursor, ed.length()));
        return dictionary.extractAssociationSeed(before);
    }

    private void renderCandidateChips(boolean association) {
        candidateHost.setVisibility(VISIBLE);
        if (candidates.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(association ? "暂无联想" : "无候选，空格上屏拼音");
            empty.setTextColor(0xFF9BB0C9);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setPadding(dp(10), 0, dp(10), 0);
            candidateRow.addView(empty);
            return;
        }
        int pageSize = 9;
        int totalPages = (candidates.size() + pageSize - 1) / pageSize;
        if (candidatePage >= totalPages) {
            candidatePage = Math.max(0, totalPages - 1);
        }
        int from = candidatePage * pageSize;
        int to = Math.min(from + pageSize, candidates.size());
        for (int d = 0; d < to - from; d++) {
            final int index = from + d;
            TextView num = new TextView(getContext());
            num.setText(String.valueOf(d + 1));
            num.setGravity(Gravity.CENTER);
            num.setTextColor(association ? 0xFFFFB74D : 0xFF5B9BFF);
            num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            num.setPadding(dp(8), dp(4), dp(8), dp(4));
            GradientDrawable nbg = new GradientDrawable();
            nbg.setCornerRadius(dp(6));
            nbg.setColor(association ? 0x33FF9800 : 0x221B6BDB);
            num.setBackground(nbg);
            LayoutParams nlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            nlp.rightMargin = dp(4);
            num.setLayoutParams(nlp);
            num.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickCandidateAt(index);
                }
            });
            candidateRow.addView(num);
        }
        if (totalPages > 1) {
            TextView prev = pageBtn("〈", candidatePage > 0);
            prev.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (candidatePage > 0) {
                        candidatePage--;
                        refreshCandidates();
                    }
                }
            });
            candidateRow.addView(prev);
        }
        for (int i = from; i < to; i++) {
            final int index = i;
            final String word = candidates.get(i);
            TextView chip = new TextView(getContext());
            chip.setText((i - from + 1) + "." + word);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            if (association) {
                bg.setColor(i == from ? 0xFFE65100 : 0xFF5D4037);
                bg.setStroke(dp(1), 0x66FFB74D);
            } else {
                bg.setColor(i == from ? 0xFF1B6BDB : 0xFF163A6B);
                bg.setStroke(dp(1), 0x665B9BFF);
            }
            chip.setBackground(bg);
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickCandidateAt(index);
                }
            });
            candidateRow.addView(chip);
        }
        if (totalPages > 1) {
            TextView next = pageBtn("〉", candidatePage < totalPages - 1);
            next.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (candidatePage < totalPages - 1) {
                        candidatePage++;
                        refreshCandidates();
                    }
                }
            });
            candidateRow.addView(next);
        }
    }

    private TextView pageBtn(String label, boolean enabled) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextColor(enabled ? Color.WHITE : 0xFF6A7A90);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(0xFF1B4A7A);
        tv.setBackground(bg);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setEnabled(enabled);
        return tv;
    }

    private void setElevationCompat(float dpVal) {
        if (Build.VERSION.SDK_INT >= 21) {
            setElevation(dp(dpVal));
        }
    }

    private int dp(float v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    private int dp(int v) {
        return dp((float) v);
    }
}
