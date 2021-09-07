/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;

import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName;
import io.netty.util.AsciiString;

class ArmeriaHttp2HeadersTest {

    // Copied from Http2HeadersTest
    
    @Test
    void testEqualsInsertionOrderSameHeaderName() {
        final Http2Headers h1 = newEmptyHeaders();
        h1.add("a", "b");
        h1.add("a", "c");
        final Http2Headers h2 = newEmptyHeaders();
        h2.add("a", "c");
        h2.add("a", "b");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void testEqualsInsertionOrderDifferentHeaderNames() {
        final Http2Headers h1 = newEmptyHeaders();
        h1.add("a", "b");
        h1.add("c", "d");
        final Http2Headers h2 = newEmptyHeaders();
        h2.add("c", "d");
        h2.add("a", "b");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void testGetAllOperationsWithEmptyHeaders() {
        final Http2Headers headers = newEmptyHeaders();
        assertThat(headers.getAll("Foo")).isEmpty();
    }

    @Test
    void testGetAllOperationsWithMultipleValues() {
        final Http2Headers headers = newEmptyHeaders();
        headers.add("Foo", "1");
        headers.add("Foo", "2");

        assertThat(headers.getAll("Foo")).containsExactly("1", "2");
    }

    @Test
    void testGetBooleanOperation() {
        final Http2Headers headers = newEmptyHeaders();
        headers.add("foo", "100");
        assertThat(headers.getBoolean("foo")).isNull();
        assertThat(headers.getBoolean("foo", true)).isTrue();

        headers.add("foo_true", "1");
        assertThat(headers.getBoolean("foo_true")).isTrue();
        assertThat(headers.containsBoolean("foo_true", true)).isTrue();
        assertThat(headers.containsBoolean("foo_true", false)).isFalse();

        headers.add("foo_false", "0");
        assertThat(headers.getBoolean("foo_false")).isFalse();
        assertThat(headers.containsBoolean("foo_false", false)).isTrue();
        assertThat(headers.containsBoolean("foo_false", true)).isFalse();

        headers.add("bar", "true");
        headers.add("bar", "false");
        assertThat(headers.getBoolean("bar")).isTrue();
        assertThat(headers.getAll("bar").get(1)).isEqualTo("false");
        assertThat(headers.containsBoolean("baz", true)).isFalse();

        headers.add("baz", "false");
        assertThat(headers.containsBoolean("baz", false)).isTrue();
        assertThat(headers.containsBoolean("baz", true)).isFalse();
    }

    // Tests forked from io.netty.handler.codec.DefaultHeadersTest

    @Test
    void addShouldIncreaseAndRemoveShouldDecreaseTheSize() {
        final Http2Headers headers = newEmptyHeaders();
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
        final Http2Headers headers = newEmptyHeaders();
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
        final Http2Headers headers = newEmptyHeaders();
        headers.add("name1", "value1");
        headers.add("name2", "value2");
        assertThat(headers.remove("name2")).isTrue();
        assertThat(headers.remove("name2")).isFalse();
    }

    @Test
    void multipleValuesPerNameShouldBeAllowed() {
        final Http2Headers headers = newEmptyHeaders();
        headers.add("name", "value1");
        headers.add("name", "value2");
        headers.add("name", "value3");
        assertThat(headers.size()).isEqualTo(3);

        final List<CharSequence> values = headers.getAll("name");
        assertThat(values).hasSize(3)
                          .containsExactly("value1", "value2", "value3");
    }

    @Test
    void multipleValuesPerNameIteratorWithOtherNames() {
        final Http2Headers headers = newEmptyHeaders();
        headers.add("name1", "value1");
        headers.add("name1", "value2");
        headers.add("name2", "value4");
        headers.add("name1", "value3");
        assertThat(headers.size()).isEqualTo(4);

        final List<CharSequence> values = ImmutableList.copyOf(headers.valueIterator("name1"));
        assertThat(values).containsExactly("value1", "value2", "value3");

        final Iterator<CharSequence> itr = headers.valueIterator("name2");
        assertThat(itr).hasNext();
        assertThat(itr.next()).isEqualTo("value4");
        assertThat(itr).isExhausted();
    }

    @Test
    void multipleValuesPerNameIterator() {
        final Http2Headers headers = newEmptyHeaders();
        headers.add("name1", "value1");
        headers.add("name1", "value2");
        assertThat(headers.size()).isEqualTo(2);

        final List<CharSequence> values = ImmutableList.copyOf(headers.valueIterator("name1"));
        assertThat(values).containsExactly("value1", "value2");
    }

    @Test
    void multipleValuesPerNameIteratorEmpty() {
        final Http2Headers headers = newEmptyHeaders();
        assertThat(headers.valueIterator("name")).isExhausted();
        assertThatThrownBy(() -> headers.valueIterator("name").next())
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testContains() {
        final Http2Headers headers = newEmptyHeaders();

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
        Http2Headers headers = newEmptyHeaders();
        headers.addLong("long", Long.MAX_VALUE);
        headers.addInt("int", Integer.MIN_VALUE);
        headers.addDouble("double", Double.MAX_VALUE);
        headers.addFloat("float", Float.MAX_VALUE);
        final long millis = System.currentTimeMillis();
        headers.addTimeMillis("millis", millis);
        headers.addObject("object", "Hello World");
        headers.add("name", "value");

        final Http2Headers oldHeaders = headers;
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
        final Http2Headers headers = newEmptyHeaders();
        headers.add("name", "value");
        headers.addInt("name", 100);

        assertThat(headers.size()).isEqualTo(2);
        assertThat(headers.contains("name")).isTrue();
        assertThat(headers.contains("name", "value")).isTrue();
        assertThat(headers.containsInt("name", 100)).isTrue();
    }

    @Test
    void testGetAndRemove() {
        final Http2Headers headers = newEmptyHeaders();
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
        final Http2Headers headers = newEmptyHeaders();
        headers.add("name1", "value1", "value2");
        assertThat(headers.get("name1")).isEqualTo("value1");
    }

    @Test
    void getWithDefaultValueWorks() {
        final Http2Headers headers = newEmptyHeaders();
        headers.add("name1", "value1");

        assertThat(headers.get("name1", "defaultvalue")).isEqualTo("value1");
        assertThat(headers.get("noname", "defaultvalue")).isEqualTo("defaultvalue");
    }

    @Test
    void setShouldOverWritePreviousValue() {
        final Http2Headers headers = newEmptyHeaders();
        headers.set("name", "value1");
        headers.set("name", "value2");
        assertThat(headers.size()).isEqualTo(1);
        assertThat(headers.getAll("name").size()).isEqualTo(1);
        assertThat(headers.getAll("name").get(0)).isEqualTo("value2");
        assertThat(headers.get("name")).isEqualTo("value2");
    }

    @Test
    void setAllShouldOverwriteSomeAndLeaveOthersUntouched() {
        final Http2Headers h1 = newEmptyHeaders();

        h1.add("name1", "value1");
        h1.add("name2", "value2");
        h1.add("name2", "value3");
        h1.add("name3", "value4");

        final Http2Headers h2 = newEmptyHeaders();
        h2.add("name1", "value5");
        h2.add("name2", "value6");
        h2.add("name1", "value7");

        final Http2Headers expected = newEmptyHeaders();
        expected.add("name1", "value5");
        expected.add("name2", "value6");
        expected.add("name1", "value7");
        expected.add("name3", "value4");

        h1.setAll(h2);

        assertThat(h1).isEqualTo(expected);
    }

    @Test
    void headersWithSameNamesAndValuesShouldBeEquivalent() {
        final Http2Headers headers1 = newEmptyHeaders();
        headers1.add("name1", "value1");
        headers1.add("name2", "value2");
        headers1.add("name2", "value3");

        final Http2Headers headers2 = newEmptyHeaders();
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
        final Http2Headers headers1 = newEmptyHeaders();
        final Http2Headers headers2 = newEmptyHeaders();
        assertThat(headers2).isEqualTo(headers1);
        assertThat(headers2.hashCode()).isEqualTo(headers1.hashCode());
    }

    @Test
    void headersWithSameNamesButDifferentValuesShouldNotBeEquivalent() {
        final Http2Headers headers1 = newEmptyHeaders();
        headers1.add("name1", "value1");
        final Http2Headers headers2 = newEmptyHeaders();
        headers1.add("name1", "value2");
        assertThat(headers1).isNotEqualTo(headers2);
    }

    @Test
    void subsetOfHeadersShouldNotBeEquivalent() {
        final Http2Headers headers1 = newEmptyHeaders();
        headers1.add("name1", "value1");
        headers1.add("name2", "value2");
        final Http2Headers headers2 = newEmptyHeaders();
        headers1.add("name1", "value1");
        assertThat(headers1).isNotEqualTo(headers2);
    }

    @Test
    void headersWithDifferentNamesAndValuesShouldNotBeEquivalent() {
        final Http2Headers h1 = newEmptyHeaders();
        h1.set("name1", "value1");
        final Http2Headers h2 = newEmptyHeaders();
        h2.set("name2", "value2");
        assertThat(h1).isNotEqualTo(h2);
        assertThat(h2).isNotEqualTo(h1);
        assertThat(h1).isEqualTo(h1);
        assertThat(h2).isEqualTo(h2);
    }

    @Test
    void iterateEmptyHeadersShouldThrow() {
        final Iterator<Map.Entry<CharSequence, CharSequence>> iterator = newEmptyHeaders().iterator();
        assertThat(iterator.hasNext()).isFalse();
        assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void iteratorShouldReturnAllNameValuePairs() {
        final Http2Headers headers1 = newEmptyHeaders();
        headers1.add("name1", "value1", "value2");
        headers1.add("name2", "value3");
        headers1.add("name3", "value4", "value5", "value6");
        headers1.add("name1", "value7", "value8");
        assertThat(headers1.size()).isEqualTo(8);

        final Http2Headers headers2 = newEmptyHeaders();
        for (Map.Entry<CharSequence, CharSequence> entry : headers1) {
            headers2.add(entry.getKey(), entry.getValue());
        }

        assertThat(headers2).isEqualTo(headers1);
    }

    @Test
    void iteratorSetShouldFail() {
        final Http2Headers headers = newEmptyHeaders();
        headers.add("name1", "value1", "value2", "value3");
        headers.add("name2", "value4");
        assertThat(headers.size()).isEqualTo(4);

        assertThatThrownBy(() -> headers.iterator().next().setValue(""))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testEntryEquals() {
        final Http2Headers nameValue = newEmptyHeaders();
        nameValue.add("name", "value");
        final Http2Headers nameValueCopy = newEmptyHeaders();
        nameValueCopy.add("name", "value");
        final Map.Entry<CharSequence, CharSequence> same1 = nameValue.iterator().next();
        final Map.Entry<CharSequence, CharSequence> same2 = nameValueCopy.iterator().next();
        assertThat(same2).isEqualTo(same1);
        assertThat(same2.hashCode()).isEqualTo(same1.hashCode());

        final Http2Headers name1Value = newEmptyHeaders();
        name1Value.add("name1", "value");
        final Http2Headers name2Value = newEmptyHeaders();
        name2Value.add("name2", "value");
        final Map.Entry<CharSequence, CharSequence> nameDifferent1 = name1Value.iterator().next();
        final Map.Entry<CharSequence, CharSequence> nameDifferent2 = name2Value.iterator().next();
        assertThat(nameDifferent1).isNotEqualTo(nameDifferent2);
        assertThat(nameDifferent1.hashCode()).isNotEqualTo(nameDifferent2.hashCode());

        final Http2Headers nameValue1 = newEmptyHeaders();
        nameValue1.add("name", "value1");
        final Http2Headers nameValue2 = newEmptyHeaders();
        nameValue2.add("name", "value2");
        final Map.Entry<CharSequence, CharSequence> valueDifferent1 = nameValue1.iterator().next();
        final Map.Entry<CharSequence, CharSequence> valueDifferent2 = nameValue2.iterator().next();
        assertThat(valueDifferent1).isNotEqualTo(valueDifferent2);
        assertThat(valueDifferent1.hashCode()).isNotEqualTo(valueDifferent2.hashCode());
    }

    @Test
    void getAllReturnsEmptyListForUnknownName() {
        final Http2Headers headers = newEmptyHeaders();
        assertThat(headers.getAll("noname").size()).isEqualTo(0);
    }

    @Test
    void setHeadersShouldClearAndOverwrite() {
        final Http2Headers headers1 = newEmptyHeaders();
        headers1.add("name", "value");

        final Http2Headers headers2 = newEmptyHeaders();
        headers2.add("name", "newvalue");
        headers2.add("name1", "value1");

        headers1.set(headers2);
        assertThat(headers2).isEqualTo(headers1);
    }

    @Test
    void testAddSelf() {
        final Http2Headers headers = newEmptyHeaders();
        assertThatThrownBy(() -> headers.add(headers)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSetSelfIsNoOp() {
        final Http2Headers headers = newEmptyHeaders();
        headers.add("name", "value");
        headers.set(headers);
        assertThat(headers.size()).isEqualTo(1);
    }

    @Test
    void testToString() {
        Http2Headers headers = newEmptyHeaders();
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
        ((ArmeriaHttp2Headers) headers).delegate().endOfStream(true);
        headers.add("name1", "value1");
        assertThat(headers.toString()).isEqualTo("[EOS, name1=value1]");

        headers = newEmptyHeaders();
        assertThat(headers.toString()).isEqualTo("[]");

        headers = newEmptyHeaders();
        ((ArmeriaHttp2Headers) headers).delegate().endOfStream(true);
        assertThat(headers.toString()).isEqualTo("[EOS]");
    }

    @Test
    void testNotThrowWhenConvertFails() {
        final Http2Headers headers = newEmptyHeaders();
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
        final Http2Headers headers = newEmptyHeaders();
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
        final Http2Headers headers = newEmptyHeaders();
        headers.add("Foo", "1");
        headers.add("Foo", "2");

        assertThat(headers.get("Foo")).isEqualTo("1");

        final List<CharSequence> values = headers.getAll("Foo");
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
        final Http2Headers headers = newHttp2Headers();
        verifyPseudoHeadersFirst(headers);
        verifyAllPseudoHeadersPresent(headers);
    }

    @Test
    void testPseudoHeadersWithRemovePreservesPseudoIterationOrder() {
        final Http2Headers headers = newHttp2Headers();
        final Http2Headers nonPseudoHeaders = newEmptyHeaders();
        for (Map.Entry<CharSequence, CharSequence> entry : headers) {
            if (entry.getKey().length() == 0 ||
                entry.getKey().charAt(0) != ':' && !nonPseudoHeaders.contains(entry.getKey())) {
                nonPseudoHeaders.add(entry.getKey(), entry.getValue());
            }
        }

        assertThat(nonPseudoHeaders.isEmpty()).isFalse();

        // Remove all the non-pseudo headers and verify
        for (Map.Entry<CharSequence, CharSequence> nonPseudoHeaderEntry : nonPseudoHeaders) {
            assertThat(headers.remove(nonPseudoHeaderEntry.getKey())).isTrue();
            verifyPseudoHeadersFirst(headers);
            verifyAllPseudoHeadersPresent(headers);
        }

        // Add back all non-pseudo headers
        for (Map.Entry<CharSequence, CharSequence> nonPseudoHeaderEntry : nonPseudoHeaders) {
            headers.add(nonPseudoHeaderEntry.getKey(), "goo");
            verifyPseudoHeadersFirst(headers);
            verifyAllPseudoHeadersPresent(headers);
        }
    }

    @Test
    void testPseudoHeadersWithClearDoesNotLeak() {
        final Http2Headers headers = newHttp2Headers();

        assertThat(headers.isEmpty()).isFalse();
        headers.clear();
        assertThat(headers.isEmpty()).isTrue();

        // Combine 2 headers together, make sure pseudo headers stay up front.
        headers.add("name1", "value1");
        headers.scheme("nothing");
        verifyPseudoHeadersFirst(headers);

        final Http2Headers other = newEmptyHeaders();
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
    void testOverwriteWithSet() {
        final Http2Headers headers = newHttp2Headers();
        final Http2Headers other = newEmptyHeaders();
        other.add("name2", "value2");
        other.add("name3", "value3");
        other.add("name4", "value4");
        other.authority("foo");

        final int headersSizeBefore = headers.size();
        // Netty's Http2Headers.set(Headers) should clear the current entries and overwrite with the new one.
        headers.set(other);
        assertThat(headers).isEqualTo(other);
    }

    @Test
    void testHeaderNameNormalization() {
        final Http2Headers headers = newHttp2Headers();
        headers.add("Foo", "bar");
        assertThat(headers.getAll("foo")).containsExactly("bar");
        assertThat(headers.getAll("fOO")).containsExactly("bar");
        assertThat(headers.names()).contains(HttpHeaderNames.of("foo"))
                                   .doesNotContain(AsciiString.of("Foo"));
    }

    @Test
    void testClearResetsPseudoHeaderDivision() {
        final Http2Headers http2Headers = newHttp2Headers();
        http2Headers.method(HttpMethod.POST.name());
        http2Headers.set("some", "value");
        http2Headers.clear();
        http2Headers.method(HttpMethod.GET.name());
        assertThat(http2Headers.names()).containsExactly(HttpHeaderNames.METHOD);
        assertThat(http2Headers.getAll(HttpHeaderNames.METHOD)).containsExactly("GET");
    }

    @Test
    void testContainsNameAndValue() {
        final Http2Headers headers = newHttp2Headers();
        assertThat(headers.contains("name1", "value2")).isTrue();
        assertThat(headers.contains("name1", "Value2")).isFalse();
        assertThat(headers.contains("name2", "value3")).isTrue();
        assertThat(headers.contains("name2", "Value3")).isFalse();
    }

    @Test
    void testPath() {
        final Http2Headers headers = newHttp2Headers();
        assertThat(headers.path()).isEqualTo("/index.html");
    }

    @Test
    void testContentDispositionObject() {
        final Http2Headers headers = newHttp2Headers();
        final ContentDisposition contentDisposition = ContentDisposition.of("form-data", "fieldA", "text.txt");
        headers.addObject(HttpHeaderNames.CONTENT_DISPOSITION, contentDisposition);
        assertThat(headers.get(HttpHeaderNames.CONTENT_DISPOSITION))
                .isSameAs(contentDisposition.asHeaderValue());
    }

    private static void verifyAllPseudoHeadersPresent(Http2Headers headers) {
        for (PseudoHeaderName pseudoName : PseudoHeaderName.values()) {
            assertThat(headers.get(pseudoName.value())).isNotNull();
        }
    }

    static void verifyPseudoHeadersFirst(Http2Headers headers) {
        CharSequence lastNonPseudoName = null;
        for (Map.Entry<CharSequence, CharSequence> entry : headers) {
            if (entry.getKey().length() == 0 || entry.getKey().charAt(0) != ':') {
                lastNonPseudoName = entry.getKey();
            } else if (lastNonPseudoName != null) {
                fail("All pseudo headers must be fist in iteration. Pseudo header " + entry.getKey() +
                     " is after a non-pseudo header " + lastNonPseudoName);
            }
        }
    }

    private static Http2Headers newEmptyHeaders() {
        return new ArmeriaHttp2Headers();
    }

    private static Http2Headers newHttp2Headers() {
        final Http2Headers headers = newEmptyHeaders();
        headers.add("name1", "value1", "value2");
        headers.method(HttpMethod.POST.name());
        headers.add("name2", "value3");
        headers.path("/index.html");
        headers.status(HttpStatus.OK.codeAsText());
        headers.authority("netty.io");
        headers.add("name3", "value4");
        headers.scheme("https");
        headers.add(HttpHeaderNames.PROTOCOL, "websocket");
        return headers;
    }
}
