/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.RoutingTrie.Node;
import com.linecorp.armeria.server.RoutingTrie.NodeType;

import it.unimi.dsi.fastutil.chars.Char2ObjectMap;
import it.unimi.dsi.fastutil.chars.Char2ObjectMaps;
import it.unimi.dsi.fastutil.chars.Char2ObjectOpenHashMap;

/**
 * Builds {@link RoutingTrie} with given paths and values.
 * This helps to make {@link RoutingTrie} immutable.
 *
 * @param <V> Value type of {@link RoutingTrie}.
 */
final class RoutingTrieBuilder<V> {

    private static final char KEY_PARAMETER = 0x01;
    private static final char KEY_CATCH_ALL = 0x02;

    private final List<Entry<V>> routes = new ArrayList<>();
    @Nullable
    private Comparator<V> comparator;

    /**
     * Adds a path and a value to be built as {@link RoutingTrie}.
     *
     * @param path  the path to serve
     * @param value the value belonging to the path
     */
    RoutingTrieBuilder<V> add(String path, V value) {
        return add(path, value, true);
    }

    /**
     * Adds a path and a value to be built as {@link RoutingTrie}.
     *
     * @param path  the path to serve
     * @param value the value belonging to the path
     * @param hasHighPrecedence whether the path being added has high precedence or not.
     *                          Any values that match a path with high precedence will be returned
     *                          before the values with low precedence. Within the same precedence,
     *                          values are returned in the order the paths were defined.
     */
    RoutingTrieBuilder<V> add(String path, V value, boolean hasHighPrecedence) {
        requireNonNull(path, "path");
        requireNonNull(value, "value");

        checkArgument(!path.isEmpty(), "A path should not be empty.");
        checkArgument(path.charAt(0) != '*' && path.charAt(0) != ':',
                      "A path starting with '*' or ':' is not allowed.");
        checkArgument(path.indexOf(KEY_PARAMETER) < 0,
                      "A path should not contain %s: %s",
                      Integer.toHexString(KEY_PARAMETER), path);
        checkArgument(path.indexOf(KEY_CATCH_ALL) < 0,
                      "A path should not contain %s: %s",
                      Integer.toHexString(KEY_CATCH_ALL), path);

        routes.add(new Entry<>(path, value, hasHighPrecedence));
        return this;
    }

    /**
     * Sets a {@link Comparator} to be used to sort values.
     *
     * @param comparator the comparator to sort values.
     */
    RoutingTrieBuilder<V> comparator(Comparator<V> comparator) {
        this.comparator = comparator;
        return this;
    }

    /**
     * Builds and returns {@link RoutingTrie} with given paths and values.
     */
    RoutingTrie<V> build() {
        checkState(!routes.isEmpty(), "No routes added");

        final NodeBuilder<V> root = insertAndGetRoot(routes.get(0));
        for (int i = 1; i < routes.size(); i++) {
            final Entry<V> route = routes.get(i);
            addRoute(root, route);
        }
        return new RoutingTrie<>(root.build());
    }

    /**
     * Inserts the first route and gets the root node of the trie.
     */
    private NodeBuilder<V> insertAndGetRoot(Entry<V> entry) {
        NodeBuilder<V> node = insertChild(null, entry.path, entry.value, entry.hasHighPrecedence);
        // Only the root node has no parent.
        for (;;) {
            final NodeBuilder<V> parent = node.parent;
            if (parent == null) {
                return node;
            }
            node = parent;
        }
    }

    /**
     * Adds a new route to the trie.
     */
    private void addRoute(NodeBuilder<V> node, Entry<V> entry) {
        String path = entry.path;
        NodeBuilder<V> current = node;
        while (true) {
            final String p = current.path;
            final int max = Math.min(p.length(), path.length());

            // Count the number of characters having the same prefix.
            int same = 0;
            while (same < max && p.charAt(same) == path.charAt(same)) {
                same++;
            }

            // We need to split the current node into two in order to ensure that this node has the
            // same part of the path. Assume that the path is "/abb" and this node is "/abc/d".
            // This node would be split into "/ab" as a parent and "c/d" as a child.
            if (same < p.length()) {
                current.split(same);
            }

            // If the same part is the last part of the path, we need to add the value to this node.
            if (same == path.length()) {
                current.addValue(entry.value, entry.hasHighPrecedence, comparator);
                return;
            }

            // We need to find a child to be able to consume the next character of the path, or need to
            // make a new sub trie to manage remaining part of the path.
            final char nextChar = convertKey(path.charAt(same));
            final NodeBuilder<V> next = current.child(nextChar);
            if (next == null) {
                // Insert node.
                insertChild(current, path.substring(same), entry.value, entry.hasHighPrecedence);
                return;
            }

            current = next;
            path = path.substring(same);
        }
    }

    /**
     * Converts the given character to the key of the children map.
     * This is only used while building a {@link RoutingTrie}.
     */
    static char convertKey(char key) {
        switch (key) {
            case ':':
                return KEY_PARAMETER;
            case '*':
                return KEY_CATCH_ALL;
            default:
                return key;
        }
    }

    /**
     * Makes a node and then inserts it to the given node as a child.
     */
    private NodeBuilder<V> insertChild(@Nullable NodeBuilder<V> node,
                                       String path, V value, boolean highPrecedence) {
        int pathStart = 0;
        final int max = path.length();

        for (int i = 0; i < max; i++) {
            final char c = path.charAt(i);
            // Find the prefix until the first wildcard (':' or '*')
            if (c != '*' && c != ':') {
                continue;
            }
            if (c == '*' && i + 1 < max) {
                throw new IllegalStateException("Catch-all should be the last in the path: " + path);
            }

            if (i > pathStart) {
                node = asChild(new NodeBuilder<>(NodeType.EXACT, node, path.substring(pathStart, i)));
            }
            // Skip this '*' or ':' character.
            pathStart = i + 1;

            if (c == '*') {
                node = asChild(new NodeBuilder<>(NodeType.CATCH_ALL, node, "*"));
            } else {
                node = asChild(new NodeBuilder<>(NodeType.PARAMETER, node, ":"));
            }
        }

        // Make a new child node with the remaining characters of the path.
        if (pathStart < max) {
            node = asChild(new NodeBuilder<>(NodeType.EXACT, node, path.substring(pathStart)));
        }
        // Attach the value to the last node.
        assert node != null;
        node.addValue(value, highPrecedence, comparator);
        return node;
    }

    /**
     * Makes the given node as a child.
     */
    private NodeBuilder<V> asChild(NodeBuilder<V> child) {
        final NodeBuilder<V> parent = child.parent;
        return parent == null ? child : parent.addChild(child);
    }

    private static final class Entry<V> {
        final String path;
        final V value;
        final boolean hasHighPrecedence;

        Entry(String path, V value, boolean hasHighPrecedence) {
            this.path = path;
            this.value = value;
            this.hasHighPrecedence = hasHighPrecedence;
        }
    }

    private static final class NodeBuilder<V> {

        private final NodeType type;

        // The parent may be changed when this node is split into two.
        @Nullable
        NodeBuilder<V> parent;

        // The path may be changed when this node is split into two.
        // But the first character of the path should not be changed even if this node is split.
        String path;

        @Nullable
        private Char2ObjectMap<NodeBuilder<V>> children;

        // These values are sorted every time a new value is added.
        @Nullable
        private List<V> valuesWithHighPrecedence;
        @Nullable
        private List<V> valuesWithLowPrecedence;

        NodeBuilder(NodeType type, @Nullable NodeBuilder<V> parent, String path) {
            this.type = requireNonNull(type, "type");
            this.parent = parent;
            this.path = requireNonNull(path, "path");
        }

        @Nullable
        NodeBuilder<V> child(char key) {
            return children == null ? null : children.get(key);
        }

        /**
         * Attaches a given {@code value} to the value list. If the list is not empty
         * the {@code value} is added, and sorted by the given {@link Comparator}.
         */
        void addValue(V value, boolean highPrecedence, @Nullable Comparator<V> comparator) {
            final List<V> values;
            if (highPrecedence) {
                if (valuesWithHighPrecedence == null) {
                    valuesWithHighPrecedence = new ArrayList<>(4);
                }
                values = valuesWithHighPrecedence;
            } else {
                if (valuesWithLowPrecedence == null) {
                    valuesWithLowPrecedence = new ArrayList<>(4);
                }
                values = valuesWithLowPrecedence;
            }

            values.add(value);

            // Sort the values using the given comparator.
            if (comparator != null && values.size() > 1) {
                values.sort(comparator);
            }
        }

        /**
         * Adds a child {@link RoutingTrie.Node} into the {@code children} map.
         */
        NodeBuilder<V> addChild(NodeBuilder<V> child) {
            requireNonNull(child, "child");

            final char key = convertKey(child.path.charAt(0));
            if (children == null) {
                children = new Char2ObjectOpenHashMap<>();
            }
            if (children.containsKey(key)) {
                // There should not exist two different children which starts with the same character in a trie.
                throw new IllegalStateException("Path starting with '" + key + "' already exist:" + child);
            }
            children.put(key, child);
            return child;
        }

        /**
         * Splits this {@link RoutingTrie.Node} into two by the given index of the path.
         */
        void split(int pathSplitPos) {
            checkArgument(pathSplitPos > 0 && pathSplitPos < path.length(),
                          "Invalid split index of the path: %s", pathSplitPos);

            // Would be split as:
            //  - AS-IS: /abc/     (me)
            //                d    (child 1)
            //                e    (child 2)
            //  - TO-BE: /ab       (me: split)
            //              c/     (child: split)
            //                d    (grandchild 1)
            //                e    (grandchild 2)

            final String parentPath = path.substring(0, pathSplitPos);
            final String childPath = path.substring(pathSplitPos);

            final NodeBuilder<V> child = new NodeBuilder<>(type, this, childPath);

            // Move the values which belongs to this node to the new child.
            child.valuesWithHighPrecedence = valuesWithHighPrecedence;
            child.valuesWithLowPrecedence = valuesWithLowPrecedence;
            child.children = children;
            if (child.children != null) {
                child.children.values().forEach(c -> c.parent = child);
            }

            // Clear the values and update the path and children.
            children = null;
            valuesWithHighPrecedence = null;
            valuesWithLowPrecedence = null;

            updatePath(parentPath);
            addChild(child);
        }

        private void updatePath(String path) {
            requireNonNull(path, "path");
            checkArgument(this.path.charAt(0) == path.charAt(0),
                          "Not acceptable path for update: " + path);
            this.path = path;
        }

        Node<V> build() {
            // Build the `children` while looking for `parameterChild` and `catchAllChild`.
            final Char2ObjectMap<Node<V>> children;
            Node<V> parameterChild = null;
            Node<V> catchAllChild = null;
            if (this.children == null || this.children.isEmpty()) {
                children = Char2ObjectMaps.emptyMap();
            } else {
                final Char2ObjectMap<Node<V>> newChildren = new Char2ObjectOpenHashMap<>(this.children.size());
                for (Char2ObjectMap.Entry<NodeBuilder<V>> e : this.children.char2ObjectEntrySet()) {
                    final Node<V> newChild = e.getValue().build();
                    switch (newChild.type) {
                        case PARAMETER:
                            if (parameterChild == null) {
                                parameterChild = newChild;
                            }
                            break;
                        case CATCH_ALL:
                            if (catchAllChild == null) {
                                catchAllChild = newChild;
                            }
                            break;
                    }

                    newChildren.put(e.getCharKey(), newChild);
                }
                children = Char2ObjectMaps.unmodifiable(newChildren);
            }

            // Build the `values` by concatenating `valuesWithHighPrecedence` and `valuesWithLowPrecedence`.
            final List<V> values;
            if (valuesWithHighPrecedence == null) {
                if (valuesWithLowPrecedence == null) {
                    values = ImmutableList.of();
                } else {
                    values = ImmutableList.copyOf(valuesWithLowPrecedence);
                }
            } else {
                if (valuesWithLowPrecedence == null) {
                    values = ImmutableList.copyOf(valuesWithHighPrecedence);
                } else {
                    final ImmutableList.Builder<V> builder = ImmutableList.builderWithExpectedSize(
                            valuesWithHighPrecedence.size() + valuesWithLowPrecedence.size());
                    values = builder.addAll(valuesWithHighPrecedence)
                                    .addAll(valuesWithLowPrecedence)
                                    .build();
                }
            }

            return new Node<>(type, path, children, parameterChild, catchAllChild, values);
        }
    }
}
