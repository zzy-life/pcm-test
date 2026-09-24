package com.hr.digitalhuman.ui.display;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.hr.digitalhuman.ime.SoftImeController;
import com.hr.digitalhuman.model.agent.DisplaySection;
import com.hr.digitalhuman.ui.UiDecor;

import java.util.List;

/** 面部屏幕内容区：自上而下渲染 sections + footnote。 */
public class DisplayCanvas extends LinearLayout {

    private SectionFactory.FormListener formListener;
    private SectionFactory.ActionListener actionListener;

    public DisplayCanvas(Context context) {
        super(context);
        init();
    }

    public DisplayCanvas(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DisplayCanvas(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
    }

    public void setFormListener(SectionFactory.FormListener listener) {
        this.formListener = listener;
    }

    public void setActionListener(SectionFactory.ActionListener listener) {
        this.actionListener = listener;
    }

    public void render(List<DisplaySection> sections, String footnote) {
        render(sections, footnote, true);
    }

    public void render(List<DisplaySection> sections, String footnote, boolean animate) {
        setGravity(Gravity.TOP);
        setVisibility(VISIBLE);
        removeAllViews();
        if (sections != null) {
            int delay = 0;
            SectionStylePicker picker = new SectionStylePicker();
            for (DisplaySection section : sections) {
                if (section == null || section.isEmpty()) {
                    continue;
                }
                View v = SectionFactory.create(getContext(), section, formListener, actionListener, picker);
                if (v != null) {
                    addView(v);
                    if (animate) {
                        UiDecor.playEnter(v, delay);
                        delay += 50;
                    }
                }
            }
        }
        if (footnote != null && !footnote.trim().isEmpty()) {
            addView(DisplayStyle.footnote(getContext(), footnote.trim()));
        }
        SoftImeController.get().bindTree(this);
    }

    /** 思考中：光效 + 「名字正在努力思考中」。 */
    public void showThinking(String line) {
        setGravity(Gravity.CENTER);
        setVisibility(VISIBLE);
        removeAllViews();
        addView(new ThinkingScene(getContext(), line));
    }

    public void showWelcome(String robotName, String slogan) {
        setGravity(Gravity.TOP);
        setVisibility(VISIBLE);
        removeAllViews();
        String name = robotName == null || robotName.isEmpty() ? "人社小助手" : robotName;
        WelcomeScene scene = new WelcomeScene(getContext(), name, slogan);
        addView(scene, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    public void showWelcome(String robotName) {
        showWelcome(robotName, null);
    }

    public boolean isEmpty() {
        return getChildCount() == 0;
    }
}
