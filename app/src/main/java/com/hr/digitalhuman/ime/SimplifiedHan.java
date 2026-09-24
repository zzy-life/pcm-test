package com.hr.digitalhuman.ime;

/**
 * 输入用汉字：保留《通用规范汉字》所在的基本区汉字，丢掉扩展区生僻字和注音符号。
 * 雾凇 8105 字表都在 U+4E00–U+9FFF，不再用过窄的 GB2312。
 */
final class SimplifiedHan {

    private static final String PUNCT = "，。？！、：；“”‘’《》…—·「」【】（）￥";

    private SimplifiedHan() {
    }

    static boolean isInputHan(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 128 || PUNCT.indexOf(cp) >= 0) {
                continue;
            }
            if (cp >= 0x4E00 && cp <= 0x9FFF) {
                continue;
            }
            return false;
        }
        return true;
    }
}
