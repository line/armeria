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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.RoutingTrie.Node;
import com.linecorp.armeria.server.RoutingTrie.NodeProcessor;

class RoutingTrieTest {

    @Test
    void testTrieStructure() {
        final RoutingTrieBuilder<Object> builder = new RoutingTrieBuilder<>();

        final Object value1 = new Object();
        final Object value2 = new Object();
        final Object value3 = new Object();
        final Object value4 = new Object();

        builder.add("/abc/123", value1);
        builder.add("/abc/133", value2);
        builder.add("/abc/134", value3);
        builder.add("/abc/134", value1);
        builder.add("/abc/134/*", value4);
        builder.add("/abc/124/\0", value2);

        final RoutingTrie<Object> trie = builder.build();

        // Expectation:
        //  /abc/1          (root)
        //         2        (no value)
        //           3      (has value1)
        //           4/     (no value)
        //              :   (has value2)
        //         3        (no value)
        //           3      (has value2)
        //           4      (has value3 and value1)
        //             /    (no value)
        //               *  (has value4)
        trie.dump(System.err);

        final Node<Object> found;

        // Root node.
        found = trie.findNode("/abc/1");
        assertThat(found).isNotNull();
        assertThat(found.values).isEmpty();
        assertThat(found.parent()).isNull();

        testNodeWithFindParentNode(trie, "/abc/123", "/abc/12", value1);
        testNodeWithFindParentNode(trie, "/abc/133", "/abc/13", value2);
        testNodeWithFindParentNode(trie, "/abc/134", "/abc/13", value3, value1);
        testNodeWithFindParentNode(trie, "/abc/134/5678", "/abc/134/", value4);
        testNodeWithFindParentNode(trie, "/abc/134/5/6/7/8", "/abc/134/", value4);
        testNodeWithFindParentNode(trie, "/abc/124/5678", "/abc/124/", value2);

        // Not existing nodes.
        assertThat(trie.findNode("/abc/124/5/6/7/8")).isNull();
        assertThat(trie.findNode("/abc/111")).isNull();
        assertThat(trie.findNode("/")).isNull();
        assertThat(trie.findNode("/hello")).isNull();

        // Intermediate nodes.
        testIntermNode(trie, "/abc/12", "/abc/1");
        testIntermNode(trie, "/abc/13", "/abc/1");
        testIntermNode(trie, "/abc/124/", "/abc/12");
    }

    @Test
    void testParameterAndCatchAll() {
        final RoutingTrieBuilder<Object> builder = new RoutingTrieBuilder<>();

        final Object value0 = new Object();
        final Object value1 = new Object();
        final Object value2 = new Object();
        final Object value3 = new Object();
        final Object value4 = new Object();
        final Object value5 = new Object();
        final Object value6 = new Object();
        final Object value7 = new Object();
        final Object value8 = new Object();
        final Object value9 = new Object();

        builder.add("/users/\0", value0);
        builder.add("/users/\0", value1);
        builder.add("/users/\0/movies", value2);
        builder.add("/users/\0/books", value3);
        builder.add("/users/\0/books/harry_potter", value4);
        builder.add("/users/\0/books/harry_potter*", value5);
        builder.add("/users/\0/books/\0", value6);
        builder.add("/users/\0/movies/*", value7);
        builder.add("/\0", value8);
        builder.add("/*", value9);

        final RoutingTrie<Object> trie = builder.build();

        // Expectation:
        //                          `/`(exact, values=[])  (root)
        //                                    |
        //           +------------------------------------------------------+
        //           |                        |                             |
        // `:`(param, values=[8])   `*`(catch, values=[9])   `users/`(exact, values=[])
        //           |                        |                             |
        //          Nil                      Nil               `\0`(param, values=[0,1])
        //                                                                  |
        //                      +-------------------------------------------+
        //                      |                                           |
        //         `:update`(exact, values=[10])                  `/`(exact, values=[])
        //                                                                  |
        //                           +----------------------------------------------+
        //                           |                                              |
        //             `movies`(exact, values=[2])                      `books`(exact, values=[3])
        //                           |                                              |
        //                 `/`(exact, values=[])                          `/`(exact, values=[])
        //                           |                                              |
        //                `*`(catch, values=[7])               +----------------------------------+
        //                           |                         |                                  |
        //                          Nil              `\0`(param, values=[6])   `harry_potter`(exact, values=[4])
        //                                                     |                                  |
        //                                                    Nil                        `*`(catch, values=[5])
        //                                                                                        |
        //                                                                                       Nil
        trie.dump(System.err);

        testNodeWithCheckingParentPath(trie, "/users/tom", "users/", value0, value1);
        testNodeWithCheckingParentPath(trie, "/users/tom/movies", "/", value2);
        testNodeWithCheckingParentPath(trie, "/users/tom/books", "/", value3);
        testNodeWithCheckingParentPath(trie, "/users/tom/books/harry_potter", "/", value4);
        testNodeWithCheckingParentPath(trie, "/users/tom/books/harry_potter1", "harry_potter", value5);
        testNodeWithCheckingParentPath(trie, "/users/tom/books/the_hunger_games", "/", value6);
        testNodeWithCheckingParentPath(trie, "/users/tom/movies/dunkirk", "/", value7);
        testNodeWithCheckingParentPath(trie, "/users/tom/movies/spider_man", "/", value7);
        testNodeWithCheckingParentPath(trie, "/faq", "/", value8);
        testNodeWithCheckingParentPath(trie, "/events/2017", "/", value9);
        testNodeWithCheckingParentPath(trie, "/", "/", value9);
        testNodeWithCheckingParentPath(trie, "/users/tom:update", "users/", value0, value1);
        testNodeWithCheckingParentPath(trie, "/users/11:00:update", "users/", value0, value1);
    }

    @ParameterizedTest
    @MethodSource("generateFindStrategyData")
    void testFindAll(String path, int findFirst, List<Integer> findAll) {
        final ImmutableList<Object> values = IntStream.range(0, 13).mapToObj(i -> new Object() {
            @Override
            public String toString() {
                return "value" + i;
            }
        }).collect(toImmutableList());

        final RoutingTrieBuilder<Object> builder = new RoutingTrieBuilder<>();
        builder.add("/users/\0", values.get(0));
        builder.add("/users/*", values.get(1));
        builder.add("/users/\0/movies", values.get(2));
        builder.add("/users/\0/books", values.get(3));
        builder.add("/users/\0/books/harry_potter", values.get(4));
        builder.add("/users/\0/books/harry_potter*", values.get(5));
        builder.add("/users/\0/books/\0", values.get(6));
        builder.add("/users/\0/movies/*", values.get(7));
        builder.add("/\0", values.get(8));
        builder.add("/*", values.get(9));
        builder.add("/users/\0/books/harry_potter:update", values.get(10));
        builder.add("/users/bob:no-verb", values.get(11));

        final RoutingTrie<Object> trie = builder.build();
        List<Object> found;
        found = trie.find(path);
        assertThat(found).containsExactly(values.get(findFirst));
        found = trie.find(path);
        assertThat(found).containsExactly(values.get(findFirst));
        found = trie.findAll(path);
        final List<Object> greedyExpect = findAll.stream().map(values::get).collect(toImmutableList());
        assertThat(found).containsAll(greedyExpect);
    }

    static Stream<Arguments> generateFindStrategyData() {
        return Stream.of(
                Arguments.of("/users/1", 0, ImmutableList.of(0, 1, 9)),
                Arguments.of("/users/1/movies/1", 7, ImmutableList.of(7, 1, 9)),
                Arguments.of("/users/1/books/1:update", 6, ImmutableList.of(6,1,9)),
                Arguments.of("/users/1:2/books/1", 6, ImmutableList.of(6,1,9)),
                Arguments.of("/users/1:2/books/1:update", 6, ImmutableList.of(6,1,9)),
                Arguments.of("/users/1:2/books/1:no-verb", 6, ImmutableList.of(6,1,9)),
                Arguments.of("/users/1/books/harry_potter:update", 10, ImmutableList.of(10,1,9)),
                Arguments.of("/users/1:2/books/harry_potter:update", 10, ImmutableList.of(10,1,9)),
                Arguments.of("/users/:2/books/harry_potter:update", 10, ImmutableList.of(10,1,9)),
                Arguments.of("/users/bob:no-verb", 11, ImmutableList.of(11,0,1,9))
        );
    }

    @Test
    void testExceptionalCases() {
        assertThatThrownBy(() -> new RoutingTrieBuilder<>().build())
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new RoutingTrieBuilder<>().add("*", new Object()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RoutingTrieBuilder<>().add("*012", new Object()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RoutingTrieBuilder<>().add(":", new Object()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RoutingTrieBuilder<>().add(":012", new Object()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RoutingTrieBuilder<>().add("/*abc", new Object()).build())
                .isInstanceOf(IllegalStateException.class);
    }

    private static Node<?> testNodeWithCheckingParentPath(RoutingTrie<?> trie,
                                                          String targetPath, String parentPath,
                                                          Object... values) {
        final Node<?> found = trie.findNode(targetPath);
        assertThat(found).isNotNull();
        assertThat(found.parent()).isNotNull();
        assertThat(found.parent().path).isEqualTo(parentPath);
        testValues(found, values);
        return found;
    }

    private static Node<?> testNodeWithFindParentNode(RoutingTrie<?> trie,
                                                      String targetPath, String parentPath,
                                                      Object... values) {
        final Node<?> found = trie.findNode(targetPath);
        assertThat(found).isNotNull();
        assertThat(found.parent()).isNotNull();
        assertThat(found.parent()).isSameAs(trie.findNode(parentPath, true, NodeProcessor.noop()));
        testValues(found, values);
        return found;
    }

    private static Node<?> testIntermNode(RoutingTrie<?> trie, String targetPath, String parentPath) {
        final Node<?> found = trie.findNode(targetPath);
        assertThat(found).isNotNull();
        assertThat(found.values).isEmpty();
        assertThat(found.parent()).isNotNull();
        assertThat(found.parent()).isSameAs(trie.findNode(parentPath, true, NodeProcessor.noop()));
        return found;
    }

    private static void testValues(Node<?> node, Object[] values) {
        @SuppressWarnings("unchecked")
        final List<Object> actualValues = (List<Object>) node.values;
        assertThat(actualValues).containsExactly(values);

        // Make sure the value list is immutable.
        assertThatThrownBy(() -> actualValues.add(null))
                .isInstanceOf(UnsupportedOperationException.class);

        // Make sure the child map is immutable.
        assertThatThrownBy(() -> node.children.put('0', null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void redirectMustHaveLowPrecedence() {
        final RoutingTrieBuilder<String> builder = new RoutingTrieBuilder<>();

        final String high = "high";
        final String low = "low";

        builder.add("/foo", low, false);
        builder.add("/foo", high);
        builder.add("/bar/*", low, false);
        builder.add("/bar/*", high);
        builder.add("/baz/\0", low, false);
        builder.add("/baz/\0", high);

        builder.add("/foo_low_only", low, false);
        builder.add("/bar_low_only/*", low, false);
        builder.add("/baz_low_only/\0", low, false);

        final RoutingTrie<String> trie = builder.build();
        Node<String> node;

        node = trie.findNode("/foo");
        assertThat(node).isNotNull();
        assertThat(node.values).containsExactly(high, low);

        node = trie.findNode("/bar/");
        assertThat(node).isNotNull();
        assertThat(node.values).containsExactly(high, low);

        node = trie.findNode("/baz/1");
        assertThat(node).isNotNull();
        assertThat(node.values).containsExactly(high, low);

        node = trie.findNode("/foo_low_only");
        assertThat(node).isNotNull();
        assertThat(node.values).containsExactly(low);

        node = trie.findNode("/bar_low_only/");
        assertThat(node).isNotNull();
        assertThat(node.values).containsExactly(low);

        node = trie.findNode("/baz_low_only/1");
        assertThat(node).isNotNull();
        assertThat(node.values).containsExactly(low);
    }
}
