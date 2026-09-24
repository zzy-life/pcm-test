package com.hr.digitalhuman.ui.display;

import java.util.Random;

/**
 * 同一页内为各区块抽一种外观；相邻同类区块错开，避免连着两张长得一样。
 */
public final class SectionStylePicker {

    private final Random rng = new Random();
    private String lastKind;
    private int lastVariant = -1;

    public int next(String kind, int count) {
        if (count <= 1) {
            lastKind = kind;
            lastVariant = 0;
            return 0;
        }
        int v = rng.nextInt(count);
        if (kind != null && kind.equals(lastKind) && v == lastVariant) {
            v = (v + 1 + rng.nextInt(count - 1)) % count;
        }
        lastKind = kind;
        lastVariant = v;
        return v;
    }

    public DisplayStyle.Skin skin() {
        DisplayStyle.Skin[] all = DisplayStyle.Skin.values();
        return all[rng.nextInt(all.length)];
    }
}
