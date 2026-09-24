package com.hr.digitalhuman.ime;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 拼音词典（雾凇拼音 rime-ice）：按词频出字/出词，并用整词切分做上屏后联想。
 */
public final class PinyinDictionary {

    private static final int MAX_WORDS_PER_KEY = 24;
    private static final int MAX_SUGGEST = 48;
    private static final int MAX_ASSOC = 16;

    private static PinyinDictionary instance;

    /** 拼音 → 候选词（已按权重降序） */
    private final Map<String, String[]> table = new HashMap<>();
    /** 有序拼音键，供二分前缀检索 */
    private final List<String> sortedKeys = new ArrayList<>();
    /** 单字音节，用于拼音分词显示 */
    private final Set<String> syllables = new HashSet<>();
    private final Map<String, Integer> wordWeight = new HashMap<>();
    /** 上屏后联想：已有词 → 后续词（按词频） */
    private final Map<String, List<String>> associations = new HashMap<>();
    private boolean loaded;

    public static final class Match {
        public final String word;
        /** 上屏该词应消耗的拼音长度 */
        public final int consume;
        public final int weight;

        public Match(String word, int consume) {
            this(word, consume, 0);
        }

        public Match(String word, int consume, int weight) {
            this.word = word;
            this.consume = consume;
            this.weight = weight;
        }
    }

    public static synchronized PinyinDictionary get(Context context) {
        if (instance == null) {
            instance = new PinyinDictionary();
            instance.load(context.getApplicationContext());
        }
        return instance;
    }

    private void load(Context context) {
        if (loaded) {
            return;
        }
        if (!loadFromAsset(context, "ime/pinyin_dict.txt.gz", true)
                && !loadFromAsset(context, "ime/pinyin_dict.txt", false)) {
            seedBuiltin();
        }
        sortedKeys.clear();
        sortedKeys.addAll(table.keySet());
        Collections.sort(sortedKeys);
        buildAssociations();
        loaded = true;
    }

    private boolean loadFromAsset(Context context, String path, boolean gzip) {
        try {
            InputStream raw = context.getAssets().open(path);
            InputStream in = gzip ? new GZIPInputStream(raw) : raw;
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"), 64 * 1024);
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = normalizeKey(line.substring(0, eq));
                if (key.isEmpty()) {
                    continue;
                }
                String[] parts = line.substring(eq + 1).split(",");
                List<String> words = new ArrayList<>(Math.min(parts.length, MAX_WORDS_PER_KEY));
                for (String part : parts) {
                    if (part == null) {
                        continue;
                    }
                    String token = part.trim();
                    if (token.isEmpty()) {
                        continue;
                    }
                    int colon = token.lastIndexOf(':');
                    String word = token;
                    int weight = 1;
                    if (colon > 0) {
                        String maybeWeight = token.substring(colon + 1);
                        if (isDigits(maybeWeight)) {
                            word = token.substring(0, colon);
                            weight = parseInt(maybeWeight, 1);
                        }
                    }
                    if (word.isEmpty() || !SimplifiedHan.isInputHan(word) || words.contains(word)) {
                        continue;
                    }
                    words.add(word);
                    Integer prev = wordWeight.get(word);
                    if (prev == null || weight > prev) {
                        wordWeight.put(word, weight);
                    }
                    if (word.length() == 1) {
                        syllables.add(key);
                    }
                    if (words.size() >= MAX_WORDS_PER_KEY) {
                        break;
                    }
                }
                if (!words.isEmpty()) {
                    table.put(key, words.toArray(new String[0]));
                }
            }
            br.close();
            return !table.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void seedBuiltin() {
        String[][] pairs = {
                {"wo", "我"}, {"ni", "你,尼"}, {"ta", "他,她,它"}, {"de", "的,得,地"},
                {"shi", "是,时,事,市"}, {"le", "了"}, {"zai", "在,再"}, {"you", "有,又"},
                {"he", "和,喝"}, {"yi", "一,以,已"}, {"bu", "不,步"}, {"ren", "人"},
                {"nihao", "你好"}, {"jianli", "简历"}, {"gongzuo", "工作"}, {"dianhua", "电话"}
        };
        for (String[] p : pairs) {
            if (!table.containsKey(p[0])) {
                String[] words = p[1].split(",");
                table.put(p[0], words);
                for (String w : words) {
                    wordWeight.put(w, 1000);
                    if (w.length() == 1) {
                        syllables.add(p[0]);
                    }
                }
            }
        }
    }

    /**
     * 用词库整词切分做联想：仅当左右都是独立词时才关联。
     * 例如「工作经验」→ 工作 推出 经验，而不会把「工」乱推「作经验」。
     */
    private void buildAssociations() {
        associations.clear();
        Map<String, Map<String, Integer>> scored = new HashMap<>();
        for (Map.Entry<String, Integer> e : wordWeight.entrySet()) {
            String word = e.getKey();
            int w = e.getValue() == null ? 0 : e.getValue();
            int n = word.length();
            if (n < 2 || n > 6) {
                continue;
            }
            for (int split = 1; split < n; split++) {
                if (split > 3 || n - split > 4) {
                    continue;
                }
                String left = word.substring(0, split);
                String right = word.substring(split);
                if (!wordWeight.containsKey(left) || !wordWeight.containsKey(right)) {
                    continue;
                }
                Map<String, Integer> bucket = scored.get(left);
                if (bucket == null) {
                    bucket = new HashMap<>();
                    scored.put(left, bucket);
                }
                Integer prev = bucket.get(right);
                if (prev == null || w > prev) {
                    bucket.put(right, w);
                }
            }
        }
        for (Map.Entry<String, Map<String, Integer>> e : scored.entrySet()) {
            List<Map.Entry<String, Integer>> items = new ArrayList<>(e.getValue().entrySet());
            Collections.sort(items, new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    int c = Integer.compare(b.getValue(), a.getValue());
                    return c != 0 ? c : a.getKey().compareTo(b.getKey());
                }
            });
            List<String> list = new ArrayList<>(Math.min(MAX_ASSOC, items.size()));
            for (int i = 0; i < items.size() && list.size() < MAX_ASSOC; i++) {
                list.add(items.get(i).getKey());
            }
            associations.put(e.getKey(), list);
        }
        addAssocMulti("我", "的", "是", "在", "有", "要", "想");
        addAssocMulti("你", "好", "的", "是");
        addAssocMulti("我的", "名字", "电话", "邮箱", "简历");
        addAssocMulti("工作", "经验", "岗位", "单位");
        addAssocMulti("社保", "查询", "缴纳", "明细");
        addAssocMulti("就业", "登记", "推荐", "服务");
        addAssocMulti("简历", "生成", "预览", "模板");
    }

    private void addAssocMulti(String key, String... vals) {
        if (key == null || vals == null) {
            return;
        }
        for (String v : vals) {
            addAssoc(key, v);
        }
    }

    private void addAssoc(String key, String next) {
        if (key == null || next == null) {
            return;
        }
        String k = key.trim();
        String v = next.trim();
        if (k.isEmpty() || v.isEmpty() || k.equals(v)) {
            return;
        }
        List<String> list = associations.get(k);
        if (list == null) {
            list = new ArrayList<>();
            associations.put(k, list);
        }
        if (!list.contains(v) && list.size() < MAX_ASSOC) {
            list.add(0, v);
        }
    }

    public List<String> associate(String previous) {
        if (previous == null) {
            return Collections.emptyList();
        }
        String seed = previous.trim();
        if (seed.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int len = Math.min(seed.length(), 4); len >= 1; len--) {
            String key = seed.substring(seed.length() - len);
            List<String> list = associations.get(key);
            if (list != null) {
                out.addAll(list);
            }
            if (out.size() >= MAX_ASSOC) {
                break;
            }
        }
        List<String> list = new ArrayList<>();
        for (String s : out) {
            if (s != null && !s.isEmpty() && s.length() <= 8) {
                list.add(s);
            }
            if (list.size() >= MAX_ASSOC) {
                break;
            }
        }
        return list;
    }

    public String extractAssociationSeed(CharSequence textBeforeCursor) {
        if (textBeforeCursor == null || textBeforeCursor.length() == 0) {
            return "";
        }
        int end = textBeforeCursor.length();
        int start = end;
        while (start > 0) {
            char c = textBeforeCursor.charAt(start - 1);
            if (isCjk(c)) {
                start--;
                if (end - start >= 4) {
                    break;
                }
            } else {
                break;
            }
        }
        if (start >= end) {
            return "";
        }
        return textBeforeCursor.subSequence(start, end).toString();
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        return key.trim().toLowerCase(Locale.US).replace(" ", "").replace("'", "");
    }

    public List<String> suggest(String composing) {
        List<Match> matches = suggestMatches(composing);
        List<String> out = new ArrayList<>();
        for (Match m : matches) {
            out.add(m.word);
        }
        return out;
    }

    public List<Match> suggestMatches(String composing) {
        if (composing == null) {
            return Collections.emptyList();
        }
        String py = normalizeKey(composing);
        if (py.isEmpty() || !isAlpha(py)) {
            return Collections.emptyList();
        }
        LinkedHashMap<String, Match> out = new LinkedHashMap<>();
        collectExact(py, out);
        if (out.size() < MAX_SUGGEST) {
            collectPrefix(py, out);
        }
        if (out.isEmpty()) {
            for (String variant : fuzzyVariants(py)) {
                if (variant.equals(py)) {
                    continue;
                }
                collectExact(variant, out);
                if (!out.isEmpty()) {
                    break;
                }
            }
        }
        if (out.isEmpty()) {
            for (int len = Math.min(py.length(), 6); len >= 1; len--) {
                String head = py.substring(0, len);
                String[] chars = table.get(head);
                if (chars == null) {
                    continue;
                }
                for (String s : chars) {
                    if (s.length() == 1 && !out.containsKey(s)) {
                        out.put(s, new Match(s, len, weightOf(s)));
                    }
                }
                if (!out.isEmpty()) {
                    break;
                }
            }
        }
        List<Match> list = new ArrayList<>(out.values());
        if (list.size() > MAX_SUGGEST) {
            return list.subList(0, MAX_SUGGEST);
        }
        return list;
    }

    public int consumeLength(String composing, String word) {
        if (composing == null || word == null) {
            return 0;
        }
        String py = normalizeKey(composing);
        List<Match> matches = suggestMatches(py);
        for (Match m : matches) {
            if (word.equals(m.word) && m.consume > 0) {
                return Math.min(m.consume, py.length());
            }
        }
        int chars = word.length();
        int consumed = 0;
        String rest = py;
        for (int i = 0; i < chars && !rest.isEmpty(); i++) {
            int best = 0;
            for (int len = Math.min(rest.length(), 6); len >= 1; len--) {
                String head = rest.substring(0, len);
                if (syllables.contains(head) || table.containsKey(head)) {
                    best = len;
                    break;
                }
            }
            if (best == 0) {
                best = Math.min(rest.length(), 1);
            }
            consumed += best;
            rest = rest.substring(best);
        }
        return consumed > 0 ? Math.min(consumed, py.length()) : py.length();
    }

    private void collectExact(String py, LinkedHashMap<String, Match> out) {
        String[] exact = table.get(py);
        if (exact == null) {
            return;
        }
        for (int i = 0; i < exact.length; i++) {
            String s = exact[i];
            if (!out.containsKey(s)) {
                out.put(s, new Match(s, py.length(), weightOf(s)));
            }
        }
    }

    private void collectPrefix(String py, LinkedHashMap<String, Match> out) {
        List<Match> extra = new ArrayList<>();
        int from = lowerBound(py);
        for (int i = from; i < sortedKeys.size(); i++) {
            String key = sortedKeys.get(i);
            if (!key.startsWith(py)) {
                break;
            }
            if (key.equals(py)) {
                continue;
            }
            String[] words = table.get(key);
            if (words == null || words.length == 0) {
                continue;
            }
            int take = Math.min(3, words.length);
            for (int j = 0; j < take; j++) {
                extra.add(new Match(words[j], py.length(), weightOf(words[j])));
            }
            if (extra.size() >= 96) {
                break;
            }
        }
        Collections.sort(extra, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                return Integer.compare(b.weight, a.weight);
            }
        });
        for (int i = 0; i < extra.size() && out.size() < MAX_SUGGEST; i++) {
            Match m = extra.get(i);
            if (!out.containsKey(m.word)) {
                out.put(m.word, m);
            }
        }
        for (int len = Math.min(py.length() - 1, 24); len >= 1 && out.size() < MAX_SUGGEST; len--) {
            String head = py.substring(0, len);
            String[] words = table.get(head);
            if (words == null) {
                continue;
            }
            for (String s : words) {
                if (!out.containsKey(s)) {
                    out.put(s, new Match(s, len, weightOf(s)));
                }
                if (out.size() >= MAX_SUGGEST) {
                    return;
                }
            }
        }
    }

    private int weightOf(String word) {
        Integer w = wordWeight.get(word);
        return w == null ? 0 : w;
    }

    private int lowerBound(String prefix) {
        int lo = 0;
        int hi = sortedKeys.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedKeys.get(mid).compareTo(prefix) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static List<String> fuzzyVariants(String py) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(py);
        addSwap(set, py, "zh", "z");
        addSwap(set, py, "ch", "c");
        addSwap(set, py, "sh", "s");
        List<String> list = new ArrayList<>(set);
        if (list.size() > 6) {
            return list.subList(0, 6);
        }
        return list;
    }

    private static void addSwap(LinkedHashSet<String> set, String py, String a, String b) {
        if (py.contains(a)) {
            set.add(py.replace(a, b));
        }
        if (py.contains(b) && !b.isEmpty()) {
            set.add(py.replace(b, a));
        }
    }

    public String formatComposing(String composing) {
        if (composing == null || composing.isEmpty()) {
            return "";
        }
        String py = normalizeKey(composing);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < py.length()) {
            int best = 1;
            for (int len = Math.min(6, py.length() - i); len >= 1; len--) {
                String head = py.substring(i, i + len);
                if (syllables.contains(head)) {
                    best = len;
                    break;
                }
            }
            if (sb.length() > 0) {
                sb.append('\'');
            }
            sb.append(py, i, i + best);
            i += best;
        }
        return sb.toString();
    }

    private static boolean isAlpha(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }
}
