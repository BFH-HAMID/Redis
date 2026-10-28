package dev.hamid.redisj.store;

/**
 * Redis-style glob matching for {@code KEYS}, {@code SCAN MATCH} and pub/sub patterns.
 *
 * <p>Supported syntax: {@code *} (any sequence), {@code ?} (any one character), {@code [abc]},
 * {@code [^abc]}, ranges such as {@code [a-z]}, and {@code \} to escape the next character.
 */
public final class GlobMatcher {

    private GlobMatcher() {
    }

    /**
     * Tests whether a whole string matches a glob pattern.
     *
     * @param pattern the glob pattern
     * @param text    the string to test
     * @return {@code true} if the pattern matches the entire text
     */
    public static boolean matches(String pattern, String text) {
        return match(pattern, 0, text, 0);
    }

    private static boolean match(String p, int pi, String s, int si) {
        int plen = p.length();
        int slen = s.length();
        int pIndex = pi;
        int sIndex = si;
        while (pIndex < plen) {
            char c = p.charAt(pIndex);
            if (c == '*') {
                while (pIndex < plen && p.charAt(pIndex) == '*') {
                    pIndex++;
                }
                if (pIndex == plen) {
                    return true;
                }
                for (int k = sIndex; k <= slen; k++) {
                    if (match(p, pIndex, s, k)) {
                        return true;
                    }
                }
                return false;
            }
            if (sIndex >= slen) {
                return false;
            }
            if (c == '?') {
                pIndex++;
                sIndex++;
            } else if (c == '[') {
                int end = classEnd(p, pIndex);
                boolean closed = end < plen;
                if (!classMatches(p, pIndex, end, s.charAt(sIndex))) {
                    return false;
                }
                pIndex = closed ? end + 1 : plen;
                sIndex++;
            } else {
                char literal = c;
                if (c == '\\' && pIndex + 1 < plen) {
                    literal = p.charAt(pIndex + 1);
                    pIndex += 2;
                } else {
                    pIndex++;
                }
                if (s.charAt(sIndex) != literal) {
                    return false;
                }
                sIndex++;
            }
        }
        return sIndex == slen;
    }

    /** Index of the closing bracket for the class starting at {@code start}, or the pattern length. */
    private static int classEnd(String p, int start) {
        int i = start + 1;
        if (i < p.length() && p.charAt(i) == '^') {
            i++;
        }
        while (i < p.length() && p.charAt(i) != ']') {
            if (p.charAt(i) == '\\' && i + 1 < p.length()) {
                i++;
            }
            i++;
        }
        return i;
    }

    private static boolean classMatches(String p, int start, int end, char ch) {
        boolean negate = start + 1 < end && p.charAt(start + 1) == '^';
        int i = start + 1 + (negate ? 1 : 0);
        boolean matched = false;
        while (i < end) {
            char c = p.charAt(i);
            if (c == '\\' && i + 1 < end) {
                if (p.charAt(i + 1) == ch) {
                    matched = true;
                }
                i += 2;
            } else if (i + 2 < end && p.charAt(i + 1) == '-') {
                char lo = c;
                char hi = p.charAt(i + 2);
                if (lo > hi) {
                    char tmp = lo;
                    lo = hi;
                    hi = tmp;
                }
                if (ch >= lo && ch <= hi) {
                    matched = true;
                }
                i += 3;
            } else {
                if (c == ch) {
                    matched = true;
                }
                i++;
            }
        }
        return matched != negate;
    }
}
