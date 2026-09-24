package com.hr.digitalhuman.ui.display;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;

import com.hr.digitalhuman.R;
import com.hr.digitalhuman.ui.UiDecor;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音字幕：当前播报句高亮，其余句弱化。播报结束后整段保留。
 */
public class CaptionBarView extends AppCompatTextView {

    private final List<String> sentences = new ArrayList<>();
    private int currentIndex = -1;
    private boolean finished;

    public CaptionBarView(Context context) {
        super(context);
        init();
    }

    public CaptionBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CaptionBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setTextColor(ContextCompat.getColor(getContext(), R.color.text));
        setTextSize(17);
        setLineSpacing(UiDecor.dp(getContext(), 6), 1f);
        int p = UiDecor.dp(getContext(), 14);
        setPadding(p, p, p, p);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xCC0A1620);
        bg.setCornerRadius(UiDecor.dp(getContext(), 14));
        bg.setStroke(UiDecor.dp(getContext(), 1), 0x661B6BDB);
        setBackground(bg);
        setVisibility(GONE);
    }

    public void setSentences(List<String> list) {
        sentences.clear();
        if (list != null) {
            for (String s : list) {
                if (s != null && !s.trim().isEmpty()) {
                    sentences.add(s.trim());
                }
            }
        }
        currentIndex = sentences.isEmpty() ? -1 : 0;
        finished = false;
        if (sentences.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        render();
    }

    public void highlight(int index) {
        if (sentences.isEmpty()) {
            return;
        }
        currentIndex = Math.max(0, Math.min(index, sentences.size() - 1));
        finished = false;
        setVisibility(VISIBLE);
        render();
    }

    public void markFinished() {
        finished = true;
        render();
    }

    /** 用户手动停止：只保留已播到的句子，并标上已停止。 */
    public void markStopped() {
        if (sentences.isEmpty()) {
            return;
        }
        int keep = currentIndex < 0 ? 0 : Math.min(currentIndex, sentences.size() - 1);
        if (keep + 1 < sentences.size()) {
            sentences.subList(keep + 1, sentences.size()).clear();
        }
        String cur = sentences.get(keep);
        if (cur != null && !cur.endsWith("（已停止）")) {
            sentences.set(keep, cur + "（已停止）");
        }
        currentIndex = keep;
        finished = true;
        setVisibility(VISIBLE);
        render();
    }

    public void clearCaption() {
        sentences.clear();
        currentIndex = -1;
        finished = false;
        setText("");
        setVisibility(GONE);
    }

    public int sentenceCount() {
        return sentences.size();
    }

    private void render() {
        if (sentences.isEmpty()) {
            setText("");
            return;
        }
        int currentColor = ContextCompat.getColor(getContext(), R.color.primary_bright);
        int dimColor = ContextCompat.getColor(getContext(), R.color.text_dim);
        int doneColor = ContextCompat.getColor(getContext(), R.color.text);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            int start = sb.length();
            sb.append(sentences.get(i));
            int end = sb.length();
            if (i < sentences.size() - 1) {
                sb.append("  ");
            }
            if (finished) {
                sb.setSpan(new ForegroundColorSpan(doneColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (i == currentIndex) {
                sb.setSpan(new ForegroundColorSpan(currentColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (i < currentIndex) {
                sb.setSpan(new ForegroundColorSpan(doneColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.setSpan(new ForegroundColorSpan(dimColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        setText(sb);
    }

    public static List<String> splitSentences(String text) {
        List<String> list = new ArrayList<>();
        if (text == null) {
            return list;
        }
        String src = text.trim();
        if (src.isEmpty()) {
            return list;
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            buf.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                    || c == '!' || c == '?') {
                String part = buf.toString().trim();
                if (!part.isEmpty()) {
                    list.add(part);
                }
                buf.setLength(0);
            }
        }
        String tail = buf.toString().trim();
        if (!tail.isEmpty()) {
            list.add(tail);
        }
        if (list.isEmpty()) {
            list.add(src);
        }
        return list;
    }
}
