/*
 * Copyright 2017 LINE Corporation
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
import static com.linecorp.armeria.server.RoutingTrie.Node.convertKey;
import static com.linecorp.armeria.server.RoutingTrie.Node.validatePath;
import static java.util.Objects.requireNonNull;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

/**
 * <a href="https://en.wikipedia.org/wiki/Trie">Trie</a> implementation to route a request to the
 * designated value.
 *
 * {@link RoutingTrie} uses the character ':' and '*' for special purpose to handle the request path
 * efficiently.
 * <ul>
 *     <li>':' as a path variable like the regular expression of [^/]+</li>
 *     <li>'*' as a catch-all like the regular expression of .*</li>
 * </ul>
 * For example,
 * <ul>
 *     <li>"/hello/world" exactly matches the request path</li>
 *     <li>"/hello/*" matches every request paths starting with "/hello/"</li>
 *     <li>"/hello/:/world" matches the request paths like "/hello/java/world" and "/hello/armeria/world"</li>
 *     <li>"/hello/:/world/*" matches the request paths like "/hello/java/world" and
 *     "/hello/new/world/for/armeria</li>
 * </ul>
 *
 * @param <V> Value type of {@link RoutingTrie}.
 */
final class RoutingTrie<V> {

    private final Node<V> root;

    private RoutingTrie(Node<V> root) {
        requireNonNull(root, "root");
        this.root = root;
    }

    /**
     * Returns the list of values which is mapped to the given {@code path}.
     */
    @Nullable
    List<V> find(String path) {
        final Node<V> node = findNode(path, false);
        return node == null ? ImmutableList.of() : node.values();
    }

    /**
     * Returns a {@link Node} which is mapped to the given {@code path}.
     */
    Node<V> findNode(String path) {
        return findNode(path, false);
    }

    /**
     * Returns a {@link Node} which is mapped to the given {@code path}.
     * If {@code exact} is {@code true}, internally-added node may be returned.
     */
    @VisibleForTesting
    Node<V> findNode(String path, boolean exact) {
        requireNonNull(path, "path");
        return findNode(root, path, 0, exact);
    }

    /**
     * Finds a {@link Node} which is mapped to the given {@code path}. It is recursively called by itself
     * to visit the children of the given node. Returns {@code null} if there is no {@link Node} to find.
     */
    private Node<V> findNode(Node<V> node, String path, int begin, boolean exact) {
        final int next;
        switch (node.type()) {
            case EXACT:
                final int len = node.path().length();
                if (!path.regionMatches(begin, node.path(), 0, len)) {
                    // A given path does not start with the path of this node.
                    return null;
                }
                if (len == path.length() - begin) {
                    // Matched. No more input characters.
                    // If this node is not added by a user, then we should return a catch-all child
                    // if it exists. But if 'exact' is true, we just return this node to make caller
                    // have the exact matched node.
                    return exact || node.hasValues() || !node.hasCatchAllChild() ? node
                                                                                 : node.catchAllChild();
                }
                next = begin + len;
                break;
            case PARAMETER:
                // Consume characters until the delimiter '/' as a path variable.
                final int delim = path.indexOf('/', begin);
                if (delim < 0 || path.length() == delim + 1) {
                    // No more delimiter, or ends with delimiter.
                    return node;
                }
                next = delim;
                break;
            default:
                throw new Error("Should not reach here");
        }

        // The path is not matched to this node, but it is possible to be matched on my children
        // because the path starts with the path of this node. So we need to visit children as the
        // following sequences:
        //  - The child which is able to consume the next character of the path.
        //  - The child which has a path variable.
        //  - The child which is able to consume every remaining path. (catch-all)

        Node<V> child = node.child(path.charAt(next));
        if (child != null) {
            Node<V> found = findNode(child, path, next, exact);
            if (found != null) {
                return found;
            }
        }
        child = node.parameterChild();
        if (child != null) {
            Node<V> found = findNode(child, path, next, exact);
            if (found != null) {
                return found;
            }
        }
        child = node.catchAllChild();
        if (child != null) {
            return child;
        }

        return null;
    }

    public void dump(OutputStream output) {
        // Do not close this writer in order to keep output stream open.
        PrintWriter p = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        p.printf("Dump of %s:%n", this);
        dump(p, root, 0);
        p.flush();
    }

    private void dump(PrintWriter p, Node<V> node, int depth) {
        p.printf("<%d> %s%n", depth, node);
        node.children().forEach(child -> dump(p, child, depth + 1));
    }

    /**
     * Builds {@link RoutingTrie} with given paths and values.
     * This helps to make {@link RoutingTrie} immutable.
     *
     * @param <V> Value type of {@link RoutingTrie}.
     */
    static final class Builder<V> {

        private final List<Entry<String, V>> routes = new ArrayList<>();
        private Comparator<V> comparator;

        /**
         * Adds a path and a value to be built as {@link RoutingTrie}.
         *
         * @param path  the path to serve
         * @param value the value belonging to the path
         */
        Builder<V> add(String path, V value) {
            requireNonNull(path, "path");
            routes.add(Maps.immutableEntry(path, value));
            return this;
        }

        /**
         * Sets a {@link Comparator} to be used to sort values.
         *
         * @param comparator the comparator to sort values.
         */
        Builder<V> comparator(Comparator<V> comparator) {
            this.comparator = comparator;
            return this;
        }

        /**
         * Builds and returns {@link RoutingTrie} with given paths and values.
         */
        RoutingTrie<V> build() {
            checkArgument(!routes.isEmpty(), "No routes added");
            checkArgument(routes.stream()
                                .noneMatch(e -> e.getKey().startsWith("*") ||
                                                e.getKey().startsWith(":")),
                          "A path starting with '*' or ':' is not allowed.");
            routes.forEach(e -> validatePath(e.getKey()));

            final Node<V> root = insertAndGetRoot(routes.get(0).getKey(), routes.get(0).getValue());
            for (int i = 1; i < routes.size(); i++) {
                final Entry<String, V> route = routes.get(i);
                addRoute(root, route.getKey(), route.getValue());
            }
            return new RoutingTrie<>(root);
        }

        /**
         * Adds a new route to the trie.
         */
        private void addRoute(Node<V> node, String path, V value) {
            Node<V> current = node;
            while (true) {
                final String p = current.path();
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
                    current.addValue(value, comparator);
                    return;
                }

                // We need to find a child to be able to consume the next character of the path, or need to
                // make a new sub trie to manage remaining part of the path.
                final char nextChar = convertKey(path.charAt(same));
                final Node<V> next = current.child(nextChar);
                if (next == null) {
                    // Insert node.
                    insertChild(current, path.substring(same), value);
                    return;
                }

                current = next;
                path = path.substring(same);
            }
        }

        /**
         * Inserts the first route and gets the root node of the trie.
         */
        private Node<V> insertAndGetRoot(String path, V value) {
            Node<V> node = insertChild(null, path, value);
            // Only the root node has no parent.
            while (node.parent() != null) {
                node = node.parent();
            }
            return node;
        }

        /**
         * Makes a node and then inserts it to the given node as a child.
         */
        private Node<V> insertChild(Node<V> node, String path, V value) {
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
                    node = asChild(new Node<>(node, Type.EXACT, path.substring(pathStart, i)));
                }
                // Skip this '*' or ':' character.
                pathStart = i + 1;

                if (c == '*') {
                    node = asChild(new Node<>(node, Type.CATCH_ALL, "*"));
                } else {
                    node = asChild(new Node<>(node, Type.PARAMETER, ":"));
                }
            }

            // Make a new child node with the remaining characters of the path.
            if (pathStart < max) {
                node = asChild(new Node<>(node, Type.EXACT, path.substring(pathStart)));
            }
            // Attach the value to the last node.
            node.addValue(value, comparator);
            return node;
        }

        /**
         * Makes the given node as a child.
         */
        private Node<V> asChild(Node<V> child) {
            final Node<V> parent = child.parent();
            return parent == null ? child : parent.addChild(child);
        }
    }

    /**
     * Type of {@link Node}.
     */
    enum Type {
        EXACT,          // Specify a path string
        PARAMETER,      // Specify a path variable
        CATCH_ALL       // Specify a catch-all
    }

    static final class Node<V> {

        private static final char KEY_PARAMETER = 0x01;
        private static final char KEY_CATCH_ALL = 0x02;

        // The parent may be changed when this node is split into two.
        private Node<V> parent;

        private final Type type;

        // The path may be changed when this node is split into two.
        // But the first character of the path should not be changed even if this node is split.
        private String path;

        private Map<Character, Node<V>> children;

        // Short-cuts to the special-purpose children.
        private Node<V> parameterChild;
        private Node<V> catchAllChild;

        // These values are sorted every time a new value is added.
        private List<V> values;

        Node(Node<V> parent, Type type, String path) {
            this.parent = parent;
            this.type = requireNonNull(type, "type");
            this.path = requireNonNull(path, "path");
        }

        String path() {
            return path;
        }

        private void path(String path) {
            checkArgument(path().charAt(0) == path.charAt(0),
                          "Not acceptable path for update: " + path);
            this.path = path;
        }

        Type type() {
            return type;
        }

        List<V> values() {
            return values == null ? ImmutableList.of() : values;
        }

        boolean hasValues() {
            return values != null;
        }

        Collection<Node<V>> children() {
            return children == null ?
                   ImmutableList.of() : Collections.unmodifiableCollection(children.values());
        }

        @Nullable
        Node<V> parameterChild() {
            return parameterChild;
        }

        @Nullable
        Node<V> catchAllChild() {
            return catchAllChild;
        }

        boolean hasCatchAllChild() {
            return catchAllChild != null;
        }

        @Override
        public String toString() {
            final ToStringHelper toStringHelper =
                    MoreObjects.toStringHelper(this)
                               .add("path", path())
                               .add("type", type())
                               .add("parent", parent() == null ? "(null)"
                                                               : parent().path() + '#' + parent().type());
            children().forEach(child -> toStringHelper.add("child",
                                                           child.path() + '#' + child.type()));
            toStringHelper.add("values", values());
            return toStringHelper.toString();
        }

        @VisibleForTesting
        @Nullable
        Node<V> parent() {
            return parent;
        }

        @Nullable
        private Node<V> child(char key) {
            return children == null ? null : children.get(key);
        }

        /**
         * Attaches a given {@code value} to the value list. If the list is not empty
         * the {@code value} is added, and sorted by the given {@link Comparator}.
         */
        private void addValue(V value, Comparator<V> comparator) {
            if (values == null) {
                values = new ArrayList<>();
            }
            values.add(value);

            // Sort the values using the given comparator.
            if (comparator != null && values.size() > 1) {
                values.sort(comparator);
            }
        }

        /**
         * Adds a child {@link Node} into the {@code children} map.
         */
        private Node<V> addChild(Node<V> child) {
            requireNonNull(child, "child");

            final char key = convertKey(child.path().charAt(0));
            if (children == null) {
                children = new HashMap<>();
            }
            if (children.containsKey(key)) {
                // There should not exist two different children which starts with the same character in a trie.
                throw new IllegalStateException("Path starting with '" + key + "' already exist:" + child);
            }
            children.put(key, child);

            // Set short-cuts for the special-purpose children.
            // Overwriting was validated while adding this child into the children map.
            switch (child.type()) {
                case PARAMETER:
                    parameterChild = child;
                    break;
                case CATCH_ALL:
                    catchAllChild = child;
                    break;
            }
            return child;
        }

        /**
         * Splits this {@link Node} into two by the given index of the path.
         */
        private void split(int pathSplitPos) {
            checkArgument(pathSplitPos > 0 && pathSplitPos < path().length(),
                          "Invalid split index of the path: %s", pathSplitPos);

            // Would be split as:
            //  - AS-IS: /abc/     (me)
            //                d    (child 1)
            //                e    (child 2)
            //  - TO-BE: /ab       (me: split)
            //              c/     (child: split)
            //                d    (grandchild 1)
            //                e    (grandchild 2)

            final String parentPath = path().substring(0, pathSplitPos);
            final String childPath = path().substring(pathSplitPos);

            final Node<V> child = new Node<>(this, type(), childPath);

            // Move the values which belongs to this node to the new child.
            child.values = values;
            child.children = children;
            child.parameterChild = parameterChild;
            child.catchAllChild = catchAllChild;
            child.children().forEach(c -> c.parent = child);

            // Clear the values and update the path and children.
            children = null;
            parameterChild = null;
            catchAllChild = null;
            values = null;

            path(parentPath);
            addChild(child);
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
         * Validates the given path.
         */
        static void validatePath(String path) {
            checkArgument(path != null && !path.isEmpty(),
                          "A path should not be null and empty.");
            checkArgument(path.indexOf(KEY_PARAMETER) < 0,
                          "A path should not contain %s: %s",
                          Integer.toHexString(KEY_PARAMETER), path);
            checkArgument(path.indexOf(KEY_CATCH_ALL) < 0,
                          "A path should not contain %s: %s",
                          Integer.toHexString(KEY_CATCH_ALL), path);
        }
    }
}
