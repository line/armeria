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

package com.linecorp.armeria.internal.client;

import static com.google.common.base.Preconditions.checkState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Utility class to determine if a domain is a public suffix. List of rules taken from
 * <a href="https://publicsuffix.org/list/public_suffix_list.dat">Public Suffix List</a>.
 */
public final class PublicSuffix {

    private static final CharMatcher DOTS_MATCHER = CharMatcher.anyOf(".。．｡");

    public static PublicSuffix get() {
        return PublicSuffixHolder.INSTANCE;
    }

    private static final class PublicSuffixHolder {
        private static final PublicSuffix INSTANCE = new PublicSuffix();
    }

    private final TrieNode trie;

    private PublicSuffix() {
        trie = new TrieNode();
        buildTrie();
    }

    /**
     * Builds a trie from the public suffix rules/list for suffix matching. For example, the following rules
     * creates such trie:
     *
     * <pre>{@code
     * com
     * s3.amazonaws.com
     * elb.amazonaws.com
     * *.compute.amazonaws.com
     * !foo.compute.amazonaws.com (this is imaginary)
     *
     *                  com
     *                   |
     *         ----- amazonaws -----
     *        |          |          \
     *     s3(end)   elb(end)   *compute(end)
     *                                \
     *                             !foo(end)
     * }</pre>
     *
     * <p>The node with '*' has {@code isWildcard = true}, meaning it matches up to {@code compute}, and
     * <b>one</b> more label on the left (can be anything). So a domain {@code a.compute.amazonaws.com}
     * matches this rule, but {@code a.b.compute.amazonaws.com} does not.</p>
     *
     * <p>The node with '!' has {@code isException = true}, meaning it overrides a wildcard matching. If
     * {@code !foo.compute.amazonaws.com} is not specified, {@code foo.compute.amazonaws.com} matches
     * {@code *.compute.amazonaws.com}, and thus is considered a public suffix. However with '!', it is not.
     * Exception always takes precedence over wildcard.
     *
     * <p>Note: In the specs, a wildcard '*' may appear anywhere in the rule. However, in practice and in the
     * Public Suffix List, it only appears in the leftmost position. There has been discussion on updating
     * the specs to reflect this reality and may soon be applied. See more at
     * <a href="https://github.com/publicsuffix/list/issues/145">issue</a>.
     */
    private void buildTrie() {
        try (InputStream in = getClass().getClassLoader()
                                        .getResourceAsStream("com/linecorp/armeria/public_suffixes.txt")) {
            checkState(in != null, "public_suffixes.txt not found.");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String[] labels = line.split("\\.");
                    TrieNode node = trie;
                    for (int i = labels.length - 1; i >= 0; i--) {
                        // assume wildcard is at the first position, we don't need to create a new node for it
                        // but set the current node's isWildcard = true instead
                        if (labels[i].equals("*")) {
                            node.isWildcard = true;
                            break;
                        }
                        if (node.children == null) {
                            node.children = new HashMap<>();
                        }
                        if (node.children.containsKey(labels[i])) {
                            node = node.children.get(labels[i]);
                        } else {
                            final TrieNode newNode = new TrieNode();
                            if (labels[i].charAt(0) == '!') {
                                newNode.isException = true;
                                node.children.put(labels[i].substring(1), newNode);
                            } else {
                                node.children.put(labels[i], newNode);
                            }
                            node = newNode;
                        }
                    }
                    node.isEnd = true;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns whether a domain is a public suffix. Suffix matching is done as follow:
     * <ol>
     *     <li>Split the domain by dot '.' into labels. Leading and trailing dots are ignored.</li>
     *
     *     <li>Iterate from the last label to first, at the same time traverse the trie from root. At index i,
     *     current node's children should contain a node with key = labels[i]. If not, then the domain does
     *     not match any rule and thus is not a public suffix.</li>
     *
     *     <li>If the node has {@code isWildcard = true}, it can match any label at index i - 1. Since the
     *     wildcard is always the leftmost label, i should be 1, otherwise the domain does not match. For
     *     example, {@code *.ck} matches {@code a.ck}, but not {@code a.b.ck}.</li>
     *
     *     <li>If we have a wildcard match, we still need to check for exception. If label i - 1 matches the
     *     next node and that node has {@code isException = true}, the wildcard is rejected.</li>
     * </ol>
     *
     * @param domain A canonicalized domain. An internationalized domain name (IDN) should be Punycode
     *               encoded, for example, using {@link java.net.IDN#toASCII(String)}.
     */
    public boolean isPublicSuffix(String domain) {
        domain = Ascii.toLowerCase(DOTS_MATCHER.replaceFrom(domain, '.'));
        final String[] labels = domain.split("\\.");
        final int start = domain.charAt(0) == '.' ? 1 : 0;
        final int end = domain.charAt(domain.length() - 1) == '.' ? labels.length - 2 : labels.length - 1;
        TrieNode node = trie;
        for (int i = end; i >= start; i--) {
            if (node.children != null && node.children.containsKey(labels[i])) {
                node = node.children.get(labels[i]);
            } else {
                return false;
            }
            if (i == 1 && node.isWildcard) {
                return node.children == null || !node.children.containsKey(labels[0]) ||
                       !node.children.get(labels[0]).isException;
            }
        }
        return node.isEnd;
    }

    static class TrieNode {
        boolean isEnd;
        boolean isWildcard;
        boolean isException;
        @Nullable
        Map<String, TrieNode> children;
    }
}
