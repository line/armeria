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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import it.unimi.dsi.fastutil.chars.Char2ObjectMap;
import it.unimi.dsi.fastutil.chars.Char2ObjectMaps;

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

    private static final Node<?> CONTINUE_WALKING = new Node<>(NodeType.CATCH_ALL, "",
                                                               Char2ObjectMaps.emptyMap(),
                                                               null, null, ImmutableList.of());

    private final Node<V> root;

    @SuppressWarnings("unchecked")
    private Node<V> continueWalking() {
        return (Node<V>) CONTINUE_WALKING;
    }

    RoutingTrie(Node<V> root) {
        requireNonNull(root, "root");
        this.root = root;
    }

    /**
     * Returns the list of values which is mapped to the given {@code path}.
     */
    List<V> find(String path) {
        return find(path, NodeProcessor.noop());
    }

    /**
     * Returns the list of values which is mapped to the given {@code path}.
     * Each node matched with the given {@code path} would be passed into the given {@link NodeProcessor}.
     */
    List<V> find(String path, NodeProcessor<V> processor) {
        final Node<V> node = findNode(path, false, processor);
        return node == null ? ImmutableList.of() : node.values;
    }

    /**
     * Returns the list of values which is mapped to the given {@code path}.
     */
    List<V> findAll(String path) {
        return findAllNodes(path, false)
                .stream()
                .flatMap(n -> n.values.stream())
                .collect(toImmutableList());
    }

    /**
     * Returns a {@link Node} which is mapped to the given {@code path}.
     */
    @Nullable
    @VisibleForTesting
    Node<V> findNode(String path) {
        return findNode(path, false, NodeProcessor.noop());
    }

    /**
     * Returns a {@link Node} which is mapped to the given {@code path}.
     * If {@code exact} is {@code true}, internally-added node may be returned.
     */
    @Nullable
    @VisibleForTesting
    Node<V> findNode(String path, boolean exact, NodeProcessor<V> processor) {
        requireNonNull(path, "path");
        requireNonNull(processor, "processor");
        return findFirstNode(root, path, 0, exact, new IntHolder(), processor);
    }

    /**
     * Finds a {@link Node} which is mapped to the given {@code path}. It is recursively called by itself
     * to visit the children of the given node. Returns {@code null} if there is no {@link Node} to find.
     */
    @Nullable
    private Node<V> findFirstNode(Node<V> node, String path, int begin, boolean exact, IntHolder nextHolder,
                                  NodeProcessor<V> processor) {
        final Node<V> checked = checkNode(node, path, begin, exact, nextHolder);
        if (checked != continueWalking()) {
            if (checked != null) {
                return processor.process(checked);
            }
            return null;
        }

        // The path is not matched to this node, but it is possible to be matched on my children
        // because the path starts with the path of this node. So we need to visit children as the
        // following sequences:
        //  - The child which is able to consume the next character of the path.
        //  - The child which has a path variable.
        //  - The child which is able to consume every remaining path. (catch-all)
        final int next = nextHolder.value;
        Node<V> child = node.children.get(path.charAt(next));
        if (child != null) {
            final Node<V> found = findFirstNode(child, path, next, exact, nextHolder, processor);
            if (found != null) {
                return found;
            }
        }
        child = node.parameterChild;
        if (child != null) {
            final Node<V> found = findFirstNode(child, path, next, exact, nextHolder, processor);
            if (found != null) {
                return found;
            }
        }
        if (node.catchAllChild != null) {
            return processor.process(node.catchAllChild);
        }
        return null;
    }

    private List<Node<V>> findAllNodes(String path, boolean exact) {
        final ImmutableList.Builder<Node<V>> accumulator = ImmutableList.builder();
        findAllNodes(root, path, 0, exact, accumulator, new IntHolder());
        return accumulator.build();
    }

    private void findAllNodes(Node<V> node, String path, int begin, boolean exact,
                              ImmutableList.Builder<Node<V>> accumulator, IntHolder nextHolder) {
        final Node<V> checked = checkNode(node, path, begin, exact, nextHolder);
        if (checked != continueWalking()) {
            if (checked != null) {
                accumulator.add(checked);
            }
            return;
        }

        final int next = nextHolder.value;
        // find the nearest child node from root to preserve the access order
        Node<V> child = node.catchAllChild;
        if (child != null) {
            accumulator.add(child);
        }
        child = node.parameterChild;
        if (child != null) {
            findAllNodes(child, path, next, exact, accumulator, nextHolder);
        }
        child = node.children.get(path.charAt(next));
        if (child != null) {
            findAllNodes(child, path, next, exact, accumulator, nextHolder);
        }
    }

    /**
     * Checks a {@link Node} which is mapped to the given {@code path}.
     * Returns {@code null} if the given {@code path} does not start with the path of this {@link Node}.
     * Returns {@link #continueWalking()} if the given {@code path} has to visit {@link Node#children}.
     */
    @Nullable
    private Node<V> checkNode(Node<V> node, String path, int begin, boolean exact, IntHolder next) {
        switch (node.type) {
            case EXACT:
                final int len = node.path.length();
                if (!path.regionMatches(begin, node.path, 0, len)) {
                    // A given path does not start with the path of this node.
                    return null;
                }
                if (len == path.length() - begin) {
                    // Matched. No more input characters.
                    // If this node is not added by a user, then we should return a catch-all child
                    // if it exists. But if 'exact' is true, we just return this node to make caller
                    // have the exact matched node.
                    if (exact || !node.values.isEmpty() || node.catchAllChild == null) {
                        return node;
                    }

                    return node.catchAllChild;
                }
                next.value = begin + len;
                break;
            case PARAMETER:
                // Consume characters until the delimiter '/' as a path variable.
                final int delimSlash = path.indexOf('/', begin);

                if (delimSlash < 0) {
                    final String pathVerb = findVerb(path, begin);
                    if (pathVerb == null) {
                        // No more delimiter.
                        return node;
                    } else {
                        final Node<V> verb = node.children.get(':');
                        return verb != null && verb.path.equals(pathVerb) ? verb : node;
                    }
                }

                if (path.length() == delimSlash + 1) {
                    final Node<V> trailingSlashNode = node.children.get('/');
                    return trailingSlashNode != null ? trailingSlashNode : node;
                }
                next.value = delimSlash;
                break;
            default:
                throw new Error("Should not reach here");
        }
        return continueWalking();
    }

    void dump(OutputStream output) {
        // Do not close this writer in order to keep output stream open.
        final PrintWriter p = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        p.printf("Dump of %s:%n", this);
        dump(p, root, 0);
        p.flush();
    }

    private void dump(PrintWriter p, Node<V> node, int depth) {
        p.printf("<%d> %s%n", depth, node);
        node.children.values().forEach(child -> dump(p, child, depth + 1));
    }

    /**
     * Returns the substring from the last colon ':' found after 'begin', including the colon.
     * Returns {@code null} if no colon is found after the 'begin' index.
     */
    @Nullable
    private static String findVerb(String path, int begin) {
        for (int i = path.length() - 1; i >= begin; i--) {
            if (path.charAt(i) == ':') {
                return path.substring(i);
            }
        }
        return null;
    }

    /**
     * Type of {@link Node}.
     */
    enum NodeType {
        EXACT,          // Specify a path string
        PARAMETER,      // Specify a path variable
        CATCH_ALL       // Specify a catch-all
    }

    static final class Node<V> {

        final NodeType type;
        @Nullable
        private Node<V> parent;
        final String path;
        final Char2ObjectMap<Node<V>> children;
        // Short-cuts to the special-purpose children.
        @Nullable
        final Node<V> parameterChild;
        @Nullable
        final Node<V> catchAllChild;
        final List<V> values;

        Node(NodeType type, String path, Char2ObjectMap<Node<V>> children,
             @Nullable Node<V> parameterChild, @Nullable Node<V> catchAllChild, List<V> values) {
            this.type = requireNonNull(type, "type");
            this.path = requireNonNull(path, "path");
            this.children = requireNonNull(children, "children");
            this.parameterChild = parameterChild;
            this.catchAllChild = catchAllChild;
            this.values = requireNonNull(values, "values");

            children.values().forEach(node -> node.setParent(this));
        }

        @Nullable
        @VisibleForTesting
        Node<V> parent() {
            return parent;
        }

        private void setParent(Node<V> parent) {
            assert this.parent == null : this.parent + " vs. " + parent;
            this.parent = requireNonNull(parent, "parent");
        }

        @Override
        public String toString() {
            final MoreObjects.ToStringHelper toStringHelper =
                    MoreObjects.toStringHelper(this)
                               .add("path", path)
                               .add("type", type)
                               .add("parent", parent == null ? "(null)" : parent.path + '#' + parent.type);
            children.values().forEach(child -> toStringHelper.add("child", child.path + '#' + child.type));
            toStringHelper.add("values", values);
            return toStringHelper.toString();
        }
    }

    private static class IntHolder {
        int value;
    }

    @FunctionalInterface
    interface NodeProcessor<V> {
        static <V> NodeProcessor<V> noop() {
            return node -> node;
        }

        /**
         * Looks into the node before picking it as a candidate for handling the current request.
         * Implement this method to return one of the following:
         * <ul>
         *     <li>the given {@code node} as it is;</li>
         *     <li>a new {@link Node} that will replace the given one; or</li>
         *     <li>{@code null} to exclude the given {@code node} from the candidate list.</li>
         * </ul>
         */
        @Nullable
        Node<V> process(Node<V> node);
    }
}
