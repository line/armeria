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
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName;
import io.netty.util.AsciiString;

class HttpHeadersBaseTest {

    @Test
    void testEqualsInsertionOrderSameHeaderName() {
        final HttpHeadersBase h1 = newEmptyHeaders();
        h1.add("a", "b");
        h1.add("a", "c");
        final HttpHeadersBase h2 = newEmptyHeaders();
        h2.add("a", "c");
        h2.add("a", "b");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void testEqualsInsertionOrderDifferentHeaderNames() {
        final HttpHeadersBase h1 = newEmptyHeaders();
        h1.add("a", "b");
        h1.add("c", "d");
        final HttpHeadersBase h2 = newEmptyHeaders();
        h2.add("c", "d");
        h2.add("a", "b");
        assertThat(h1).isEqualTo(h2);
    }

    // Tests forked from io.netty.handler.codec.DefaultHeadersTest

    @Test
    void addShouldIncreaseAndRemoveShouldDecreaseTheSize() {
        final HttpHeadersBase headers = newEmptyHeaders();
        assertThat(headers.size()).isEqualTo(0);
        headers.add("name1", "value1", "value2");
        assertThat(headers.size()).isEqualTo(2);
        headers.add("name2", "value3", "value4");
        assertThat(headers.size()).isEqualTo(4);
        headers.add("name3", "value5");
        assertThat(headers.size()).isEqualTo(5);

        headers.remove("name3");
        assertThat(headers.size()).isEqualTo(4);
        headers.remove("name1");
        assertThat(headers.size()).isEqualTo(2);
        headers.remove("name2");
        assertThat(headers.size()).isEqualTo(0);
        assertThat(headers.isEmpty()).isTrue();
    }

    @Test
    void afterClearHeadersShouldBeEmpty() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name1", "value1");
        headers.add("name2", "value2");
        assertThat(headers.size()).isEqualTo(2);
        headers.clear();
        assertThat(headers.size()).isEqualTo(0);
        assertThat(headers.isEmpty()).isTrue();
        assertThat(headers.contains("name1")).isFalse();
        assertThat(headers.contains("name2")).isFalse();
    }

    @Test
    void removingANameForASecondTimeShouldReturnFalse() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name1", "value1");
        headers.add("name2", "value2");
        assertThat(headers.remove("name2")).isTrue();
        assertThat(headers.remove("name2")).isFalse();
    }

    @Test
    void multipleValuesPerNameShouldBeAllowed() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name", "value1");
        headers.add("name", "value2");
        headers.add("name", "value3");
        assertThat(headers.size()).isEqualTo(3);

        final List<String> values = headers.getAll("name");
        assertThat(values).hasSize(3)
                          .containsExactly("value1", "value2", "value3");
    }

    @Test
    void multipleValuesPerNameIteratorWithOtherNames() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name1", "value1");
        headers.add("name1", "value2");
        headers.add("name2", "value4");
        headers.add("name1", "value3");
        assertThat(headers.size()).isEqualTo(4);

        final List<String> values = ImmutableList.copyOf(headers.valueIterator("name1"));
        assertThat(values).containsExactly("value1", "value2", "value3");

        final Iterator<String> itr = headers.valueIterator("name2");
        assertThat(itr).hasNext();
        assertThat(itr.next()).isEqualTo("value4");
        assertThat(itr).isExhausted();
    }

    @Test
    void multipleValuesPerNameIterator() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name1", "value1");
        headers.add("name1", "value2");
        assertThat(headers.size()).isEqualTo(2);

        final List<String> values = ImmutableList.copyOf(headers.valueIterator("name1"));
        assertThat(values).containsExactly("value1", "value2");
    }

    @Test
    void multipleValuesPerNameIteratorEmpty() {
        final HttpHeadersBase headers = newEmptyHeaders();
        assertThat(headers.valueIterator("name")).isExhausted();
        assertThatThrownBy(() -> headers.valueIterator("name").next())
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testContains() {
        final HttpHeadersBase headers = newEmptyHeaders();

        headers.addLong("long", Long.MAX_VALUE);
        assertThat(headers.containsLong("long", Long.MAX_VALUE)).isTrue();
        assertThat(headers.containsLong("long", Long.MIN_VALUE)).isFalse();

        headers.addInt("int", Integer.MIN_VALUE);
        assertThat(headers.containsInt("int", Integer.MIN_VALUE)).isTrue();
        assertThat(headers.containsInt("int", Integer.MAX_VALUE)).isFalse();

        headers.addDouble("double", Double.MAX_VALUE);
        assertThat(headers.containsDouble("double", Double.MAX_VALUE)).isTrue();
        assertThat(headers.containsDouble("double", Double.MIN_VALUE)).isFalse();

        headers.addFloat("float", Float.MAX_VALUE);
        assertThat(headers.containsFloat("float", Float.MAX_VALUE)).isTrue();
        assertThat(headers.containsFloat("float", Float.MIN_VALUE)).isFalse();

        final long millis = System.currentTimeMillis();
        headers.addTimeMillis("millis", millis);
        assertThat(headers.containsTimeMillis("millis", millis)).isTrue();
        // This test doesn't work on midnight, January 1, 1970 UTC
        assertThat(headers.containsTimeMillis("millis", 0)).isFalse();

        headers.addObject("object", "Hello World");
        assertThat(headers.containsObject("object", "Hello World")).isTrue();
        assertThat(headers.containsObject("object", "")).isFalse();

        headers.add("name", "value");
        assertThat(headers.contains("name", "value")).isTrue();
        assertThat(headers.contains("name", "value1")).isFalse();
    }

    @Test
    void testCopy() throws Exception {
        HttpHeadersBase headers = newEmptyHeaders();
        headers.addLong("long", Long.MAX_VALUE);
        headers.addInt("int", Integer.MIN_VALUE);
        headers.addDouble("double", Double.MAX_VALUE);
        headers.addFloat("float", Float.MAX_VALUE);
        final long millis = System.currentTimeMillis();
        headers.addTimeMillis("millis", millis);
        headers.addObject("object", "Hello World");
        headers.add("name", "value");

        final HttpHeadersBase oldHeaders = headers;
        headers = newEmptyHeaders();
        headers.add(oldHeaders);

        assertThat(headers.containsLong("long", Long.MAX_VALUE)).isTrue();
        assertThat(headers.containsLong("long", Long.MIN_VALUE)).isFalse();

        assertThat(headers.containsInt("int", Integer.MIN_VALUE)).isTrue();
        assertThat(headers.containsInt("int", Integer.MAX_VALUE)).isFalse();

        assertThat(headers.containsDouble("double", Double.MAX_VALUE)).isTrue();
        assertThat(headers.containsDouble("double", Double.MIN_VALUE)).isFalse();

        assertThat(headers.containsFloat("float", Float.MAX_VALUE)).isTrue();
        assertThat(headers.containsFloat("float", Float.MIN_VALUE)).isFalse();

        assertThat(headers.containsTimeMillis("millis", millis)).isTrue();
        // This test doesn't work on midnight, January 1, 1970 UTC
        assertThat(headers.containsTimeMillis("millis", 0)).isFalse();

        assertThat(headers.containsObject("object", "Hello World")).isTrue();
        assertThat(headers.containsObject("object", "")).isFalse();

        assertThat(headers.contains("name", "value")).isTrue();
        assertThat(headers.contains("name", "value1")).isFalse();
    }

    @Test
    void canMixConvertedAndNormalValues() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name", "value");
        headers.addInt("name", 100);

        assertThat(headers.size()).isEqualTo(2);
        assertThat(headers.contains("name")).isTrue();
        assertThat(headers.contains("name", "value")).isTrue();
        assertThat(headers.containsInt("name", 100)).isTrue();
    }

    @Test
    void testGetAndRemove() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name1", "value1");
        headers.add("name2", "value2", "value3");
        headers.add("name3", "value4", "value5", "value6");

        assertThat(headers.getAndRemove("name1", "defaultvalue")).isEqualTo("value1");
        assertThat(headers.getAndRemove("name2")).isEqualTo("value2");
        assertThat(headers.getAndRemove("name2")).isNull();
        assertThat(headers.getAllAndRemove("name3")).containsExactly("value4", "value5", "value6");
        assertThat(headers.size()).isZero();
        assertThat(headers.getAndRemove("noname")).isNull();
        assertThat(headers.getAndRemove("noname", "defaultvalue")).isEqualTo("defaultvalue");
    }

    @Test
    void whenNameContainsMultipleValuesGetShouldReturnTheFirst() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name1", "value1", "value2");
        assertThat(headers.get("name1")).isEqualTo("value1");
    }

    @Test
    void getWithDefaultValueWorks() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name1", "value1");

        assertThat(headers.get("name1", "defaultvalue")).isEqualTo("value1");
        assertThat(headers.get("noname", "defaultvalue")).isEqualTo("defaultvalue");
    }

    @Test
    void setShouldOverWritePreviousValue() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.set("name", "value1");
        headers.set("name", "value2");
        assertThat(headers.size()).isEqualTo(1);
        assertThat(headers.getAll("name").size()).isEqualTo(1);
        assertThat(headers.getAll("name").get(0)).isEqualTo("value2");
        assertThat(headers.get("name")).isEqualTo("value2");
    }

    @Test
    void setAllShouldOverwriteSomeAndLeaveOthersUntouched() {
        final HttpHeadersBase h1 = newEmptyHeaders();

        h1.add("name1", "value1");
        h1.add("name2", "value2");
        h1.add("name2", "value3");
        h1.add("name3", "value4");

        final HttpHeadersBase h2 = newEmptyHeaders();
        h2.add("name1", "value5");
        h2.add("name2", "value6");
        h2.add("name1", "value7");

        final HttpHeadersBase expected = newEmptyHeaders();
        expected.add("name1", "value5");
        expected.add("name2", "value6");
        expected.add("name1", "value7");
        expected.add("name3", "value4");

        h1.set(h2);

        assertThat(h1).isEqualTo(expected);
    }

    @Test
    void headersWithSameNamesAndValuesShouldBeEquivalent() {
        final HttpHeadersBase headers1 = newEmptyHeaders();
        headers1.add("name1", "value1");
        headers1.add("name2", "value2");
        headers1.add("name2", "value3");

        final HttpHeadersBase headers2 = newEmptyHeaders();
        headers2.add("name1", "value1");
        headers2.add("name2", "value2");
        headers2.add("name2", "value3");

        assertThat(headers2).isEqualTo(headers1);
        assertThat(headers1).isEqualTo(headers2);
        assertThat(headers1).isEqualTo(headers1);
        assertThat(headers2).isEqualTo(headers2);
        assertThat(headers2.hashCode()).isEqualTo(headers1.hashCode());
        assertThat(headers1.hashCode()).isEqualTo(headers1.hashCode());
        assertThat(headers2.hashCode()).isEqualTo(headers2.hashCode());
    }

    @Test
    void emptyHeadersShouldBeEqual() {
        final HttpHeadersBase headers1 = newEmptyHeaders();
        final HttpHeadersBase headers2 = newEmptyHeaders();
        assertThat(headers2).isEqualTo(headers1);
        assertThat(headers2.hashCode()).isEqualTo(headers1.hashCode());
    }

    @Test
    void headersWithSameNamesButDifferentValuesShouldNotBeEquivalent() {
        final HttpHeadersBase headers1 = newEmptyHeaders();
        headers1.add("name1", "value1");
        final HttpHeadersBase headers2 = newEmptyHeaders();
        headers1.add("name1", "value2");
        assertThat(headers1).isNotEqualTo(headers2);
    }

    @Test
    void subsetOfHeadersShouldNotBeEquivalent() {
        final HttpHeadersBase headers1 = newEmptyHeaders();
        headers1.add("name1", "value1");
        headers1.add("name2", "value2");
        final HttpHeadersBase headers2 = newEmptyHeaders();
        headers1.add("name1", "value1");
        assertThat(headers1).isNotEqualTo(headers2);
    }

    @Test
    void headersWithDifferentNamesAndValuesShouldNotBeEquivalent() {
        final HttpHeadersBase h1 = newEmptyHeaders();
        h1.set("name1", "value1");
        final HttpHeadersBase h2 = newEmptyHeaders();
        h2.set("name2", "value2");
        assertThat(h1).isNotEqualTo(h2);
        assertThat(h2).isNotEqualTo(h1);
        assertThat(h1).isEqualTo(h1);
        assertThat(h2).isEqualTo(h2);
    }

    @Test
    void iterateEmptyHeadersShouldThrow() {
        final Iterator<Map.Entry<AsciiString, String>> iterator = newEmptyHeaders().iterator();
        assertThat(iterator.hasNext()).isFalse();
        assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void iteratorShouldReturnAllNameValuePairs() {
        final HttpHeadersBase headers1 = newEmptyHeaders();
        headers1.add("name1", "value1", "value2");
        headers1.add("name2", "value3");
        headers1.add("name3", "value4", "value5", "value6");
        headers1.add("name1", "value7", "value8");
        assertThat(headers1.size()).isEqualTo(8);

        final HttpHeadersBase headers2 = newEmptyHeaders();
        for (Map.Entry<AsciiString, String> entry : headers1) {
            headers2.add(entry.getKey(), entry.getValue());
        }

        assertThat(headers2).isEqualTo(headers1);
    }

    @Test
    void iteratorSetShouldFail() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name1", "value1", "value2", "value3");
        headers.add("name2", "value4");
        assertThat(headers.size()).isEqualTo(4);

        assertThatThrownBy(() -> headers.iterator().next().setValue(""))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testEntryEquals() {
        final HttpHeadersBase nameValue = newEmptyHeaders();
        nameValue.add("name", "value");
        final HttpHeadersBase nameValueCopy = newEmptyHeaders();
        nameValueCopy.add("name", "value");
        final Map.Entry<AsciiString, String> same1 = nameValue.iterator().next();
        final Map.Entry<AsciiString, String> same2 = nameValueCopy.iterator().next();
        assertThat(same2).isEqualTo(same1);
        assertThat(same2.hashCode()).isEqualTo(same1.hashCode());

        final HttpHeadersBase name1Value = newEmptyHeaders();
        name1Value.add("name1", "value");
        final HttpHeadersBase name2Value = newEmptyHeaders();
        name2Value.add("name2", "value");
        final Map.Entry<AsciiString, String> nameDifferent1 = name1Value.iterator().next();
        final Map.Entry<AsciiString, String> nameDifferent2 = name2Value.iterator().next();
        assertThat(nameDifferent1).isNotEqualTo(nameDifferent2);
        assertThat(nameDifferent1.hashCode()).isNotEqualTo(nameDifferent2.hashCode());

        final HttpHeadersBase nameValue1 = newEmptyHeaders();
        nameValue1.add("name", "value1");
        final HttpHeadersBase nameValue2 = newEmptyHeaders();
        nameValue2.add("name", "value2");
        final Map.Entry<AsciiString, String> valueDifferent1 = nameValue1.iterator().next();
        final Map.Entry<AsciiString, String> valueDifferent2 = nameValue2.iterator().next();
        assertThat(valueDifferent1).isNotEqualTo(valueDifferent2);
        assertThat(valueDifferent1.hashCode()).isNotEqualTo(valueDifferent2.hashCode());
    }

    @Test
    void getAllReturnsEmptyListForUnknownName() {
        final HttpHeadersBase headers = newEmptyHeaders();
        assertThat(headers.getAll("noname").size()).isEqualTo(0);
    }

    @Test
    void setHeadersShouldClearAndOverwrite() {
        final HttpHeadersBase headers1 = newEmptyHeaders();
        headers1.add("name", "value");

        final HttpHeadersBase headers2 = newEmptyHeaders();
        headers2.add("name", "newvalue");
        headers2.add("name1", "value1");

        headers1.set(headers2);
        assertThat(headers2).isEqualTo(headers1);
    }

    @Test
    void setHeadersShouldOnlyOverwriteHeaders() {
        final HttpHeadersBase headers1 = newEmptyHeaders();
        headers1.add("name", "value");
        headers1.add("name1", "value1");

        final HttpHeadersBase headers2 = newEmptyHeaders();
        headers2.add("name", "newvalue");
        headers2.add("name2", "value2");

        final HttpHeadersBase expected = newEmptyHeaders();
        expected.add("name", "newvalue");
        expected.add("name1", "value1");
        expected.add("name2", "value2");

        headers1.set(headers2);
        assertThat(expected).isEqualTo(headers1);
    }

    @Test
    void testAddSelf() {
        final HttpHeadersBase headers = newEmptyHeaders();
        assertThatThrownBy(() -> headers.add(headers)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSetSelfIsNoOp() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name", "value");
        headers.set(headers);
        assertThat(headers.size()).isEqualTo(1);
    }

    @Test
    void testToString() {
        HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name1", "value1");
        headers.add("name1", "value2");
        headers.add("name2", "value3");
        assertThat(headers.toString()).isEqualTo("[name1=value1, name1=value2, name2=value3]");

        headers = newEmptyHeaders();
        headers.add("name1", "value1");
        headers.add("name2", "value2");
        headers.add("name3", "value3");
        assertThat(headers.toString()).isEqualTo("[name1=value1, name2=value2, name3=value3]");

        headers = newEmptyHeaders();
        headers.add("name1", "value1");
        assertThat(headers.toString()).isEqualTo("[name1=value1]");

        headers = newEmptyHeaders();
        headers.endOfStream(true);
        headers.add("name1", "value1");
        assertThat(headers.toString()).isEqualTo("[EOS, name1=value1]");

        headers = newEmptyHeaders();
        assertThat(headers.toString()).isEqualTo("[]");

        headers = newEmptyHeaders();
        headers.endOfStream(true);
        assertThat(headers.toString()).isEqualTo("[EOS]");
    }

    @Test
    void testNotThrowWhenConvertFails() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.set("name1", "");
        assertThat(headers.getInt("name1")).isNull();
        assertThat(headers.getInt("name1", 1)).isEqualTo(1);

        assertThat(headers.getDouble("name")).isNull();
        assertThat(headers.getDouble("name1", 1)).isEqualTo(1);

        assertThat(headers.getFloat("name")).isNull();
        assertThat(headers.getFloat("name1", Float.MAX_VALUE)).isEqualTo(Float.MAX_VALUE);

        assertThat(headers.getLong("name")).isNull();
        assertThat(headers.getLong("name1", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);

        assertThat(headers.getTimeMillis("name")).isNull();
        assertThat(headers.getTimeMillis("name1", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void valueValidation() {
        final HttpHeadersBase headers = newEmptyHeaders();
        assertThatThrownBy(() -> headers.add("foo", "\u0000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header value: <NUL>");
        assertThatThrownBy(() -> headers.add("foo", "\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header value: <LF>");
        assertThatThrownBy(() -> headers.add("foo", "\u000B"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header value: <VT>");
        assertThatThrownBy(() -> headers.add("foo", "\f"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header value: <FF>");
        assertThatThrownBy(() -> headers.add("foo", "\r"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header value: <CR>");
    }

    // Tests forked from io.netty.handler.codec.http.HttpHeadersTest

    @Test
    void testGetOperations() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("Foo", "1");
        headers.add("Foo", "2");

        assertThat(headers.get("Foo")).isEqualTo("1");

        final List<String> values = headers.getAll("Foo");
        assertThat(values).containsExactly("1", "2");
    }

    @Test
    void testSetNullHeaderValue() {
        assertThatThrownBy(() -> newEmptyHeaders().set("test", (String) null))
                .isInstanceOf(NullPointerException.class);
    }

    // Tests forked from io.netty.handler.codec.http2.DefaultHttp2HeadersTest

    @Test
    void nullHeaderNameNotAllowed() {
        assertThatThrownBy(() -> newEmptyHeaders().add(null, "foo")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyHeaderNameNotAllowed() {
        assertThatThrownBy(() -> newEmptyHeaders().add("", "foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: <EMPTY>");
    }

    @Test
    void testPseudoHeadersMustComeFirstWhenIterating() {
        final HttpHeadersBase headers = newHttp2Headers();
        verifyPseudoHeadersFirst(headers);
        verifyAllPseudoHeadersPresent(headers);
    }

    @Test
    void testPseudoHeadersWithRemovePreservesPseudoIterationOrder() {
        final HttpHeadersBase headers = newHttp2Headers();
        final HttpHeadersBase nonPseudoHeaders = newEmptyHeaders();
        for (Map.Entry<AsciiString, String> entry : headers) {
            if (entry.getKey().isEmpty() ||
                entry.getKey().charAt(0) != ':' && !nonPseudoHeaders.contains(entry.getKey())) {
                nonPseudoHeaders.add(entry.getKey(), entry.getValue());
            }
        }

        assertThat(nonPseudoHeaders.isEmpty()).isFalse();

        // Remove all the non-pseudo headers and verify
        for (Map.Entry<AsciiString, String> nonPseudoHeaderEntry : nonPseudoHeaders) {
            assertThat(headers.remove(nonPseudoHeaderEntry.getKey())).isTrue();
            verifyPseudoHeadersFirst(headers);
            verifyAllPseudoHeadersPresent(headers);
        }

        // Add back all non-pseudo headers
        for (Map.Entry<AsciiString, String> nonPseudoHeaderEntry : nonPseudoHeaders) {
            headers.add(nonPseudoHeaderEntry.getKey(), "goo");
            verifyPseudoHeadersFirst(headers);
            verifyAllPseudoHeadersPresent(headers);
        }
    }

    @Test
    void testPseudoHeadersWithClearDoesNotLeak() {
        final HttpHeadersBase headers = newHttp2Headers();

        assertThat(headers.isEmpty()).isFalse();
        headers.clear();
        assertThat(headers.isEmpty()).isTrue();

        // Combine 2 headers together, make sure pseudo headers stay up front.
        headers.add("name1", "value1");
        headers.scheme("nothing");
        verifyPseudoHeadersFirst(headers);

        final HttpHeadersBase other = newEmptyHeaders();
        other.add("name2", "value2");
        other.authority("foo");
        verifyPseudoHeadersFirst(other);

        headers.add(other);
        verifyPseudoHeadersFirst(headers);

        // Make sure the headers are what we expect them to be, and no leaking behind the scenes.
        assertThat(headers.size()).isEqualTo(4);
        assertThat(headers.get("name1")).isEqualTo("value1");
        assertThat(headers.get("name2")).isEqualTo("value2");
        assertThat(headers.scheme()).isEqualTo("nothing");
        assertThat(headers.authority()).isEqualTo("foo");
    }

    @Test
    void testSetOrdersPseudoHeadersCorrectly() {
        final HttpHeadersBase headers = newHttp2Headers();
        final HttpHeadersBase other = newEmptyHeaders();
        other.add("name2", "value2");
        other.add("name3", "value3");
        other.add("name4", "value4");
        other.authority("foo");

        final int headersSizeBefore = headers.size();
        headers.set(other);
        verifyPseudoHeadersFirst(headers);
        verifyAllPseudoHeadersPresent(headers);
        assertThat(headers.size()).isEqualTo(headersSizeBefore + 1);
        assertThat(headers.authority()).isEqualTo("foo");
        assertThat(headers.get("name2")).isEqualTo("value2");
        assertThat(headers.get("name3")).isEqualTo("value3");
        assertThat(headers.get("name4")).isEqualTo("value4");
    }

    @Test
    void testHeaderNameNormalization() {
        final HttpHeadersBase headers = newHttp2Headers();
        headers.add("Foo", "bar");
        assertThat(headers.getAll("foo")).containsExactly("bar");
        assertThat(headers.getAll("fOO")).containsExactly("bar");
        assertThat(headers.names()).contains(HttpHeaderNames.of("foo"))
                                   .doesNotContain(AsciiString.of("Foo"));
    }

    @Test
    void testClearResetsPseudoHeaderDivision() {
        final HttpHeadersBase http2Headers = newHttp2Headers();
        http2Headers.method(HttpMethod.POST);
        http2Headers.set("some", "value");
        http2Headers.clear();
        http2Headers.method(HttpMethod.GET);
        assertThat(http2Headers.names()).containsExactly(HttpHeaderNames.METHOD);
        assertThat(http2Headers.getAll(HttpHeaderNames.METHOD)).containsExactly("GET");
    }

    @Test
    void testContainsNameAndValue() {
        final HttpHeadersBase headers = newHttp2Headers();
        assertThat(headers.contains("name1", "value2")).isTrue();
        assertThat(headers.contains("name1", "Value2")).isFalse();
        assertThat(headers.contains("name2", "value3")).isTrue();
        assertThat(headers.contains("name2", "Value3")).isFalse();
    }

    @Test
    void testUri() {
        final HttpHeadersBase headers = newHttp2Headers();
        assertThat(headers.uri()).isEqualTo(URI.create("https://netty.io/index.html"));
    }

    @Test
    void testContentDispositionObject() {
        final HttpHeadersBase headers = newHttp2Headers();
        final ContentDisposition contentDisposition = ContentDisposition.of("form-data", "fieldA", "text.txt");
        headers.addObject(HttpHeaderNames.CONTENT_DISPOSITION, contentDisposition);
        assertThat(headers.get(HttpHeaderNames.CONTENT_DISPOSITION))
                .isSameAs(contentDisposition.asHeaderValue());
    }

    private static void verifyAllPseudoHeadersPresent(HttpHeadersBase headers) {
        for (PseudoHeaderName pseudoName : PseudoHeaderName.values()) {
            assertThat(headers.get(pseudoName.value())).isNotNull();
        }
    }

    static void verifyPseudoHeadersFirst(HttpHeadersBase headers) {
        AsciiString lastNonPseudoName = null;
        for (Map.Entry<AsciiString, String> entry : headers) {
            if (entry.getKey().isEmpty() || entry.getKey().charAt(0) != ':') {
                lastNonPseudoName = entry.getKey();
            } else if (lastNonPseudoName != null) {
                fail("All pseudo headers must be fist in iteration. Pseudo header " + entry.getKey() +
                     " is after a non-pseudo header " + lastNonPseudoName);
            }
        }
    }

    private static HttpHeadersBase newEmptyHeaders() {
        return new HttpHeadersBase(16);
    }

    private static HttpHeadersBase newHttp2Headers() {
        final HttpHeadersBase headers = newEmptyHeaders();
        headers.add("name1", "value1", "value2");
        headers.method(HttpMethod.POST);
        headers.add("name2", "value3");
        headers.path("/index.html");
        headers.status(HttpStatus.OK);
        headers.authority("netty.io");
        headers.add("name3", "value4");
        headers.scheme("https");
        return headers;
    }
}
