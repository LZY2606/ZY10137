package com.ecology.idhyp.scoring;

/**
 * Best-window Levenshtein similarity between a partial code fragment and a
 * known marking. The fragment is slid over the marking in windows of its own
 * length (including partial windows at both ends), and the best normalised
 * edit ratio is returned. Returns 0 when either side is blank.
 */
public final class MarkSimilarity {
    private MarkSimilarity() {
    }

    public static double score(String fragment, String known) {
        if (fragment == null || known == null) {
            return 0;
        }
        String f = fragment.trim().toUpperCase();
        String k = known.trim().toUpperCase();
        if (f.isEmpty() || k.isEmpty()) {
            return 0;
        }
        if (f.equals(k)) {
            return 1;
        }
        int flen = f.length();
        int klen = k.length();
        if (flen >= klen) {
            return 1 - (double) levenshtein(f, k) / Math.max(flen, klen);
        }
        double best = 0;
        for (int start = -(flen - 1); start <= klen - 1; start++) {
            int begin = Math.max(0, start);
            int end = Math.min(klen, start + flen);
            String window = k.substring(begin, end);
            double s = 1 - (double) levenshtein(f, window) / Math.max(f.length(), window.length());
            best = Math.max(best, s);
        }
        return best;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
