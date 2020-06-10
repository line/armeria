/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.servlet;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * String util.
 */
final class StringUtil {

    // Forked from https://github.com/spring-projects/spring-framework/blob/master/spring-core/src/main/java/
    // org/springframework/util/AntPathMatcher.java

    private static final String DEFAULT_PATH_SEPARATOR = "/";
    private static final int CACHE_TURNOFF_THRESHOLD = 65536;
    private static final char[] WILDCARD_CHARS = { '*', '?', '{' };
    private static final String pathSeparator = DEFAULT_PATH_SEPARATOR;
    private static final boolean caseSensitive = true;

    @Nullable
    private volatile Boolean cachePatterns;

    private final Map<String, String[]> tokenizedPatternCache = new ConcurrentHashMap<String, String[]>(256);

    final Map<String, AntPathStringMatcher> stringMatcherCache =
            new ConcurrentHashMap<String, AntPathStringMatcher>(256);

    private void deactivatePatternCache() {
        cachePatterns = false;
        tokenizedPatternCache.clear();
        stringMatcherCache.clear();
    }

    /**
     * Match pattern.
     */
    boolean match(String pattern, String path, String allToken) {
        requireNonNull(pattern, "pattern");
        requireNonNull(path, "path");
        requireNonNull(allToken, "allToken");
        return doMatch(pattern, path, true, null, allToken);
    }

    /**
     * Actually match the given {@code path} against the given {@code pattern}.
     * @param pattern the pattern to match against.
     * @param path the path String to test.
     * @param fullMatch whether a full pattern match is required.
     * @return {@code true} if the supplied {@code path} matched, {@code false} if it didn't.
     */
    boolean doMatch(String pattern, String path, boolean fullMatch,
                    @Nullable Map<String, String> uriTemplateVariables, String allToken) {
        requireNonNull(pattern, "pattern");
        requireNonNull(path, "path");
        requireNonNull(allToken, "allToken");
        if (path.startsWith(pathSeparator) != pattern.startsWith(pathSeparator)) {
            return false;
        }

        final String[] pattDirs = tokenizePattern(pattern);
        if (fullMatch && caseSensitive && !isPotentialMatch(path, pattDirs)) {
            return false;
        }

        final String[] pathDirs = tokenizePath(path);

        int pattIdxStart = 0;
        int pattIdxEnd = pattDirs.length - 1;
        int pathIdxStart = 0;
        int pathIdxEnd = pathDirs.length - 1;

        // Match all elements up to the first **
        while (pattIdxStart <= pattIdxEnd && pathIdxStart <= pathIdxEnd) {
            final String pattDir = pattDirs[pattIdxStart];
            if (allToken.equals(pattDir)) {
                break;
            }
            if (!matchStrings(pattDir, pathDirs[pathIdxStart], uriTemplateVariables)) {
                return false;
            }
            pattIdxStart++;
            pathIdxStart++;
        }

        if (pathIdxStart > pathIdxEnd) {
            // Path is exhausted, only match if rest of pattern is * or **'s
            if (pattIdxStart > pattIdxEnd) {
                return pattern.endsWith(pathSeparator) == path.endsWith(pathSeparator);
            }
            if (!fullMatch || (pattIdxStart == pattIdxEnd && pattDirs[pattIdxStart].equals("*") &&
                               path.endsWith(pathSeparator))) {
                return true;
            }
            for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
                if (!pattDirs[i].equals(allToken)) {
                    return false;
                }
            }
            return true;
        } else if (pattIdxStart > pattIdxEnd) {
            // String not exhausted, but pattern is. Failure.
            return false;
        } else if (!fullMatch && allToken.equals(pattDirs[pattIdxStart])) {
            // Path start definitely matches due to "**" part in pattern.
            return true;
        }

        // up to last '**'
        while (pattIdxStart <= pattIdxEnd && pathIdxStart <= pathIdxEnd) {
            final String pattDir = pattDirs[pattIdxEnd];
            if (pattDir.equals(allToken)) {
                break;
            }
            if (!matchStrings(pattDir, pathDirs[pathIdxEnd], uriTemplateVariables)) {
                return false;
            }
            pattIdxEnd--;
            pathIdxEnd--;
        }
        if (pathIdxStart > pathIdxEnd) {
            // String is exhausted
            for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
                if (!pattDirs[i].equals(allToken)) {
                    return false;
                }
            }
            return true;
        }

        while (pattIdxStart != pattIdxEnd && pathIdxStart <= pathIdxEnd) {
            int patIdxTmp = -1;
            for (int i = pattIdxStart + 1; i <= pattIdxEnd; i++) {
                if (pattDirs[i].equals(allToken)) {
                    patIdxTmp = i;
                    break;
                }
            }
            if (patIdxTmp == pattIdxStart + 1) {
                // '**/**' situation, so skip one
                pattIdxStart++;
                continue;
            }
            // Find the pattern between padIdxStart & padIdxTmp in str between
            // strIdxStart & strIdxEnd
            final int patLength = patIdxTmp - pattIdxStart - 1;
            final int strLength = pathIdxEnd - pathIdxStart + 1;
            int foundIdx = -1;

            strLoop:
            for (int i = 0; i <= strLength - patLength; i++) {
                for (int j = 0; j < patLength; j++) {
                    final String subPat = pattDirs[pattIdxStart + j + 1];
                    final String subStr = pathDirs[pathIdxStart + i + j];
                    if (!matchStrings(subPat, subStr, uriTemplateVariables)) {
                        continue strLoop;
                    }
                }
                foundIdx = pathIdxStart + i;
                break;
            }

            if (foundIdx == -1) {
                return false;
            }

            pattIdxStart = patIdxTmp;
            pathIdxStart = foundIdx + patLength;
        }

        for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
            if (!pattDirs[i].equals(allToken)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isPotentialMatch(String path, String[] pattDirs) {
        requireNonNull(path, "path");
        requireNonNull(pattDirs, "pattDirs");
        int pos = 0;
        for (String pattDir : pattDirs) {
            int skipped = skipSeparator(path, pos, pathSeparator);
            pos += skipped;
            skipped = skipSegment(path, pos, pattDir);
            if (skipped < pattDir.length()) {
                return skipped > 0 || (!pattDir.isEmpty() && isWildcardChar(pattDir.charAt(0)));
            }
            pos += skipped;
        }
        return true;
    }

    private static int skipSegment(String path, int pos, String prefix) {
        requireNonNull(path, "path");
        requireNonNull(prefix, "prefix");
        checkArgument(pos >= 0, "pos: %s (expected: >= 0)", pos);
        int skipped = 0;
        for (int i = 0; i < prefix.length(); i++) {
            final char c = prefix.charAt(i);
            if (isWildcardChar(c)) {
                return skipped;
            }
            final int currPos = pos + skipped;
            if (currPos >= path.length()) {
                return 0;
            }
            if (c == path.charAt(currPos)) {
                skipped++;
            }
        }
        return skipped;
    }

    private static int skipSeparator(String path, int pos, String separator) {
        requireNonNull(path, "path");
        requireNonNull(separator, "separator");
        checkArgument(pos >= 0, "pos: %s (expected: >= 0)", pos);
        int skipped = 0;
        while (path.startsWith(separator, pos + skipped)) {
            skipped += separator.length();
        }
        return skipped;
    }

    private static boolean isWildcardChar(char c) {
        for (char candidate : WILDCARD_CHARS) {
            if (c == candidate) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tokenize the given path pattern into parts, based on this matcher's settings.
     */
    String[] tokenizePattern(String pattern) {
        requireNonNull(pattern, "pattern");
        String[] tokenized = null;
        final Boolean cachePatterns = this.cachePatterns;
        if (cachePatterns == null || cachePatterns.booleanValue()) {
            tokenized = tokenizedPatternCache.get(pattern);
        }
        if (tokenized == null) {
            tokenized = tokenizePath(pattern);
            if (cachePatterns == null && tokenizedPatternCache.size() >= CACHE_TURNOFF_THRESHOLD) {
                // Try to adapt to the runtime situation that we're encountering:
                // There are obviously too many different patterns coming in here...
                // So let's turn off the cache since the patterns are unlikely to be reoccurring.
                deactivatePatternCache();
                return tokenized;
            }
            if (cachePatterns == null || cachePatterns.booleanValue()) {
                tokenizedPatternCache.put(pattern, tokenized);
            }
        }
        return tokenized;
    }

    /**
     * Tokenize the given path String into parts, based on this matcher's settings.
     * @param path the path to tokenize.
     * @return the tokenized path parts.
     */
    String[] tokenizePath(String path) {
        requireNonNull(path, "path");
        return tokenizeToStringArray(path, pathSeparator, false, true);
    }

    /**
     * Test whether or not a string matches against a pattern.
     * @param pattern the pattern to match against (never {@code null}).
     * @param str the String which must be matched against the pattern (never {@code null}).
     * @return {@code true} if the string matches against the pattern, or {@code false} otherwise.
     */
    private boolean matchStrings(String pattern, String str,
                                 @Nullable Map<String, String> uriTemplateVariables) {
        requireNonNull(pattern, "pattern");
        requireNonNull(str, "str");
        return getStringMatcher(pattern).matchStrings(str, uriTemplateVariables);
    }

    /**
     * Build or retrieve an {@link AntPathStringMatcher} for the given pattern.
     */
    AntPathStringMatcher getStringMatcher(String pattern) {
        requireNonNull(pattern, "pattern");
        AntPathStringMatcher matcher = null;
        final Boolean cachePatterns = this.cachePatterns;
        if (cachePatterns == null || cachePatterns.booleanValue()) {
            matcher = stringMatcherCache.get(pattern);
        }
        if (matcher == null) {
            matcher = new AntPathStringMatcher(pattern, caseSensitive);
            if (cachePatterns == null && stringMatcherCache.size() >= CACHE_TURNOFF_THRESHOLD) {
                // Try to adapt to the runtime situation that we're encountering:
                // There are obviously too many different patterns coming in here...
                // So let's turn off the cache since the patterns are unlikely to be reoccurring.
                deactivatePatternCache();
                return matcher;
            }
            if (cachePatterns == null || cachePatterns.booleanValue()) {
                stringMatcherCache.put(pattern, matcher);
            }
        }
        return matcher;
    }

    /**
     * Tests whether or not a string matches against a pattern via a {@link Pattern}.
     * The pattern may contain special characters: '*' means zero or more characters; '?' means one and
     * only one character; '{' and '}' indicate a URI template pattern. For example <tt>/users/{user}</tt>.
     */
    static class AntPathStringMatcher {

        private static final Pattern GLOB_PATTERN =
                Pattern.compile("\\?|\\*|\\{((?:\\{[^/]+?\\}|[^/{}]|\\\\[{}])+?)\\}");

        private static final String DEFAULT_VARIABLE_PATTERN = "(.*)";

        private final Pattern pattern;

        private final List<String> variableNames = new LinkedList<String>();

        AntPathStringMatcher(String pattern, boolean caseSensitive) {
            requireNonNull(pattern, "pattern");
            final StringBuilder patternBuilder = new StringBuilder();
            final Matcher matcher = GLOB_PATTERN.matcher(pattern);
            int end = 0;
            while (matcher.find()) {
                patternBuilder.append(quote(pattern, end, matcher.start()));
                final String match = matcher.group();
                if ("?".equals(match)) {
                    patternBuilder.append('.');
                } else if ("*".equals(match)) {
                    patternBuilder.append(".*");
                } else if (match.startsWith("{") && match.endsWith("}")) {
                    final int colonIdx = match.indexOf(':');
                    if (colonIdx == -1) {
                        patternBuilder.append(DEFAULT_VARIABLE_PATTERN);
                        variableNames.add(matcher.group(1));
                    } else {
                        final String variablePattern = match.substring(colonIdx + 1, match.length() - 1);
                        patternBuilder.append('(');
                        patternBuilder.append(variablePattern);
                        patternBuilder.append(')');
                        final String variableName = match.substring(1, colonIdx);
                        variableNames.add(variableName);
                    }
                }
                end = matcher.end();
            }
            patternBuilder.append(quote(pattern, end, pattern.length()));
            this.pattern = caseSensitive ? Pattern.compile(patternBuilder.toString())
                                         :
                           Pattern.compile(patternBuilder.toString(), Pattern.CASE_INSENSITIVE);
        }

        private String quote(String s, int start, int end) {
            requireNonNull(s, "s");
            checkArgument(start >= 0, "start: %s (expected: >= 0)", start);
            checkArgument(end >= 0, "end: %s (expected: >= 0)", end);
            if (start == end) {
                return "";
            }
            return Pattern.quote(s.substring(start, end));
        }

        /**
         * Main entry point.
         * @return {@code true} if the string matches against the pattern, or {@code false} otherwise.
         */
        boolean matchStrings(String str, @Nullable Map<String, String> uriTemplateVariables) {
            requireNonNull(str, "str");
            final Matcher matcher = pattern.matcher(str);
            if (matcher.matches()) {
                if (uriTemplateVariables != null) {
                    // SPR-8455
                    if (variableNames.size() != matcher.groupCount()) {
                        throw new IllegalArgumentException(
                                "The number of capturing groups in the pattern segment " +
                                pattern + " does not match the number of URI template variables " +
                                "it defines, which can occur if capturing groups are used in a URI template " +
                                "regex. Use non-capturing groups instead.");
                    }
                    for (int i = 1; i <= matcher.groupCount(); i++) {
                        final String name = variableNames.get(i - 1);
                        final String value = matcher.group(i);
                        uriTemplateVariables.put(name, value);
                    }
                }
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Tokenize string by delimiters to string array.
     */
    static String[] tokenizeToStringArray(String str, String delimiters, boolean trimTokens,
                                          boolean ignoreEmptyTokens) {
        requireNonNull(str, "str");
        requireNonNull(delimiters, "delimiters");
        final StringTokenizer st = new StringTokenizer(str, delimiters);
        final ArrayList<String> tokens = new ArrayList<>();

        while (true) {
            String token;
            do {
                if (!st.hasMoreTokens()) {
                    return tokens.toArray(new String[0]);
                }

                token = st.nextToken();
                if (trimTokens) {
                    token = token.trim();
                }
            } while (ignoreEmptyTokens && token.length() <= 0);

            tokens.add(token);
        }
    }

    /**
     * Normalize path.
     */
    static String normalizePath(String path) {
        requireNonNull(path, "path");
        if (!path.isEmpty() && path.charAt(path.length() - 1) == '/') {
            path = path.substring(0, path.length() - 1);
        }
        return path.trim();
    }
}
