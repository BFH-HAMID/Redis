package dev.hamid.redisj.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GlobMatcherTest {

    @Test
    void starMatchesAnySequenceIncludingEmpty() {
        assertTrue(GlobMatcher.matches("*", ""));
        assertTrue(GlobMatcher.matches("*", "anything"));
        assertTrue(GlobMatcher.matches("user:*", "user:42"));
        assertFalse(GlobMatcher.matches("user:*", "post:42"));
        assertTrue(GlobMatcher.matches("a*b*c", "aXXbYYc"));
        assertFalse(GlobMatcher.matches("a*b*c", "aXXbYY"));
    }

    @Test
    void questionMarkMatchesExactlyOneCharacter() {
        assertTrue(GlobMatcher.matches("h?llo", "hello"));
        assertTrue(GlobMatcher.matches("h?llo", "hallo"));
        assertFalse(GlobMatcher.matches("h?llo", "hllo"));
        assertFalse(GlobMatcher.matches("h?llo", "heello"));
    }

    @Test
    void bracketClassesAndRanges() {
        assertTrue(GlobMatcher.matches("h[ae]llo", "hallo"));
        assertTrue(GlobMatcher.matches("h[ae]llo", "hello"));
        assertFalse(GlobMatcher.matches("h[ae]llo", "hillo"));
        assertTrue(GlobMatcher.matches("h[^e]llo", "hallo"));
        assertFalse(GlobMatcher.matches("h[^e]llo", "hello"));
        assertTrue(GlobMatcher.matches("h[a-c]llo", "hbllo"));
        assertFalse(GlobMatcher.matches("h[a-c]llo", "hdllo"));
    }

    @Test
    void backslashEscapesSpecialCharacters() {
        assertTrue(GlobMatcher.matches("h\\*llo", "h*llo"));
        assertFalse(GlobMatcher.matches("h\\*llo", "hello"));
        assertTrue(GlobMatcher.matches("a\\?", "a?"));
        assertFalse(GlobMatcher.matches("a\\?", "ab"));
    }

    @Test
    void emptyPatternMatchesOnlyEmptyString() {
        assertTrue(GlobMatcher.matches("", ""));
        assertFalse(GlobMatcher.matches("", "x"));
    }
}
