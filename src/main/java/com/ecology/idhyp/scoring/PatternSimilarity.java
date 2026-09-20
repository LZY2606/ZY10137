package com.ecology.idhyp.scoring;

import java.util.HashSet;
import java.util.Set;

/**
 * Similarity of pattern summaries: summaries are whitespace-free descriptive
 * tokens; each token is split into character bigrams and similarity is the
 * Jaccard index of the bigram sets.
 */
public final class PatternSimilarity {
    private PatternSimilarity() {
    }

    public static double score(String observed, String known) {
        if (observed == null || known == null) {
            return 0;
        }
        Set<String> a = bigrams(observed);
        Set<String> b = bigrams(known);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private static Set<String> bigrams(String text) {
        String t = text.replaceAll("\\s+", "").toLowerCase();
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        if (t.length() == 1) {
            out.add(t);
        }
        return out;
    }
}
