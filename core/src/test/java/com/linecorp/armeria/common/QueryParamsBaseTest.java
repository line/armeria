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

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.testing.EqualsTester;

class QueryParamsBaseTest {

    @Test
    void testEqualsInsertionOrderSameParamName() {
        final QueryParamsBase p1 = newEmptyParams();
        p1.add("a", "b");
        p1.add("a", "c");
        final QueryParamsBase p2 = newEmptyParams();
        p2.add("a", "c");
        p2.add("a", "b");
        assertThat(p1).isNotEqualTo(p2);
    }

    @Test
    void testEqualsInsertionOrderDifferentParamNames() {
        final QueryParamsBase p1 = newEmptyParams();
        p1.add("a", "b");
        p1.add("c", "d");
        final QueryParamsBase p2 = newEmptyParams();
        p2.add("c", "d");
        p2.add("a", "b");
        assertThat(p1).isEqualTo(p2);
    }

    @Test
    void testGetLastOperations() {
        final QueryParamsBase params = newEmptyParams();
        params.add("Foo", "1");

        assertThat(params.getLast("Foo")).isEqualTo("1");
    }

    @Test
    void testGetLastOperationsWithEmptyParams() {
        final QueryParamsBase params = newEmptyParams();
        assertThat(params.getLast("Foo")).isNull();
    }

    @Test
    void testGetLastOperationsWithMultipleValues() {
        final QueryParamsBase params = newEmptyParams();
        params.add("Foo", "1");
        params.add("Foo", "2");

        assertThat(params.getLast("Foo")).isEqualTo("2");
    }

    @Test
    void testGetBooleanOperation() {
        final QueryParamsBase params = newEmptyParams();
        params.add("foo", "100");
        assertThat(params.getBoolean("foo")).isNull();
        assertThat(params.getBoolean("foo", true)).isTrue();

        params.add("foo_true", "1");
        assertThat(params.getBoolean("foo_true")).isTrue();
        assertThat(params.containsBoolean("foo_true", true)).isTrue();
        assertThat(params.containsBoolean("foo_true", false)).isFalse();

        params.add("foo_false", "0");
        assertThat(params.getBoolean("foo_false")).isFalse();
        assertThat(params.containsBoolean("foo_false", false)).isTrue();
        assertThat(params.containsBoolean("foo_false", true)).isFalse();

        params.add("bar", "true");
        params.add("bar", "false");
        assertThat(params.getBoolean("bar")).isTrue();
        assertThat(params.getLastBoolean("bar")).isFalse();
        assertThat(params.getLastBoolean("baz", false)).isFalse();
        assertThat(params.containsBoolean("baz", true)).isFalse();

        params.add("baz", "false");
        assertThat(params.containsBoolean("baz", false)).isTrue();
        assertThat(params.containsBoolean("baz", true)).isFalse();

        params.add("dup1", "v1");
        params.add("dup1", "v2");
        params.add("dup1", "true");
        params.add("dup1", "false");
        assertThat(params.containsBoolean("dup1", true)).isTrue();
        assertThat(params.containsBoolean("dup1", false)).isTrue();

        params.add("dup2", "v1");
        params.add("dup2", "v2");
        params.add("dup2", "1");
        params.add("dup2", "0");
        assertThat(params.containsBoolean("dup2", true)).isTrue();
        assertThat(params.containsBoolean("dup2", false)).isTrue();

        params.add("upperCase", "TRUE");
        params.add("upperCase", "FALSE");
        assertThat(params.getBoolean("upperCase")).isTrue();
        assertThat(params.getLastBoolean("upperCase")).isFalse();
        assertThat(params.containsBoolean("upperCase", true)).isTrue();
        assertThat(params.containsBoolean("upperCase", false)).isTrue();

        params.add("unsupported", "tRUE");
        params.add("unsupported", "FaLsE");
        assertThat(params.getBoolean("unsupported")).isNull();
        assertThat(params.getLastBoolean("unsupported")).isNull();
        assertThat(params.containsBoolean("unsupported", true)).isFalse();
        assertThat(params.containsBoolean("unsupported", false)).isFalse();
    }

    // Tests forked from io.netty.handler.codec.DefaultHeadersTest

    @Test
    void addShouldIncreaseAndRemoveShouldDecreaseTheSize() {
        final QueryParamsBase params = newEmptyParams();
        assertThat(params.size()).isEqualTo(0);
        params.add("name1", "value1", "value2");
        assertThat(params.size()).isEqualTo(2);
        params.add("name2", "value3", "value4");
        assertThat(params.size()).isEqualTo(4);
        params.add("name3", "value5");
        assertThat(params.size()).isEqualTo(5);

        params.remove("name3");
        assertThat(params.size()).isEqualTo(4);
        params.remove("name1");
        assertThat(params.size()).isEqualTo(2);
        params.remove("name2");
        assertThat(params.size()).isEqualTo(0);
        assertThat(params.isEmpty()).isTrue();
    }

    @Test
    void afterClearParamsShouldBeEmpty() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name1", "value1");
        params.add("name2", "value2");
        assertThat(params.size()).isEqualTo(2);
        params.clear();
        assertThat(params.size()).isEqualTo(0);
        assertThat(params.isEmpty()).isTrue();
        assertThat(params.contains("name1")).isFalse();
        assertThat(params.contains("name2")).isFalse();
    }

    @Test
    void removingANameForASecondTimeShouldReturnFalse() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name1", "value1");
        params.add("name2", "value2");
        assertThat(params.remove("name2")).isTrue();
        assertThat(params.remove("name2")).isFalse();
    }

    @Test
    void multipleValuesPerNameShouldBeAllowed() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name", "value1");
        params.add("name", "value2");
        params.add("name", "value3");
        assertThat(params.size()).isEqualTo(3);

        final List<String> values = params.getAll("name");
        assertThat(values).hasSize(3)
                          .containsExactly("value1", "value2", "value3");
    }

    @Test
    void multipleValuesPerNameIteratorWithOtherNames() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name1", "value1");
        params.add("name1", "value2");
        params.add("name2", "value4");
        params.add("name1", "value3");
        assertThat(params.size()).isEqualTo(4);

        final List<String> values = ImmutableList.copyOf(params.valueIterator("name1"));
        assertThat(values).containsExactly("value1", "value2", "value3");

        final Iterator<String> itr = params.valueIterator("name2");
        assertThat(itr).hasNext();
        assertThat(itr.next()).isEqualTo("value4");
        assertThat(itr).isExhausted();
    }

    @Test
    void multipleValuesPerNameIterator() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name1", "value1");
        params.add("name1", "value2");
        assertThat(params.size()).isEqualTo(2);

        final List<String> values = ImmutableList.copyOf(params.valueIterator("name1"));
        assertThat(values).containsExactly("value1", "value2");
    }

    @Test
    void multipleValuesPerNameIteratorEmpty() {
        final QueryParamsBase params = newEmptyParams();
        assertThat(params.valueIterator("name")).isExhausted();
        assertThatThrownBy(() -> params.valueIterator("name").next())
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testContains() {
        final QueryParamsBase params = newEmptyParams();

        params.addLong("long", Long.MAX_VALUE);
        assertThat(params.containsLong("long", Long.MAX_VALUE)).isTrue();
        assertThat(params.containsLong("long", Long.MIN_VALUE)).isFalse();

        params.addInt("int", Integer.MIN_VALUE);
        assertThat(params.containsInt("int", Integer.MIN_VALUE)).isTrue();
        assertThat(params.containsInt("int", Integer.MAX_VALUE)).isFalse();

        params.addDouble("double", Double.MAX_VALUE);
        assertThat(params.containsDouble("double", Double.MAX_VALUE)).isTrue();
        assertThat(params.containsDouble("double", Double.MIN_VALUE)).isFalse();

        params.addFloat("float", Float.MAX_VALUE);
        assertThat(params.containsFloat("float", Float.MAX_VALUE)).isTrue();
        assertThat(params.containsFloat("float", Float.MIN_VALUE)).isFalse();

        final long millis = System.currentTimeMillis();
        params.addTimeMillis("millis", millis);
        assertThat(params.containsTimeMillis("millis", millis)).isTrue();
        // This test doesn't work on midnight, January 1, 1970 UTC
        assertThat(params.containsTimeMillis("millis", 0)).isFalse();

        params.addObject("object", "Hello World");
        assertThat(params.containsObject("object", "Hello World")).isTrue();
        assertThat(params.containsObject("object", "")).isFalse();

        params.add("name", "value");
        assertThat(params.contains("name", "value")).isTrue();
        assertThat(params.contains("name", "value1")).isFalse();
    }

    @Test
    void testCopy() throws Exception {
        QueryParamsBase params = newEmptyParams();
        params.addLong("long", Long.MAX_VALUE);
        params.addInt("int", Integer.MIN_VALUE);
        params.addDouble("double", Double.MAX_VALUE);
        params.addFloat("float", Float.MAX_VALUE);
        final long millis = System.currentTimeMillis();
        params.addTimeMillis("millis", millis);
        params.addObject("object", "Hello World");
        params.add("name", "value");

        final QueryParamsBase oldParams = params;
        params = newEmptyParams();
        params.add(oldParams);

        assertThat(params.containsLong("long", Long.MAX_VALUE)).isTrue();
        assertThat(params.containsLong("long", Long.MIN_VALUE)).isFalse();

        assertThat(params.containsInt("int", Integer.MIN_VALUE)).isTrue();
        assertThat(params.containsInt("int", Integer.MAX_VALUE)).isFalse();

        assertThat(params.containsDouble("double", Double.MAX_VALUE)).isTrue();
        assertThat(params.containsDouble("double", Double.MIN_VALUE)).isFalse();

        assertThat(params.containsFloat("float", Float.MAX_VALUE)).isTrue();
        assertThat(params.containsFloat("float", Float.MIN_VALUE)).isFalse();

        assertThat(params.containsTimeMillis("millis", millis)).isTrue();
        // This test doesn't work on midnight, January 1, 1970 UTC
        assertThat(params.containsTimeMillis("millis", 0)).isFalse();

        assertThat(params.containsObject("object", "Hello World")).isTrue();
        assertThat(params.containsObject("object", "")).isFalse();

        assertThat(params.contains("name", "value")).isTrue();
        assertThat(params.contains("name", "value1")).isFalse();
    }

    @Test
    void canMixConvertedAndNormalValues() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name", "value");
        params.addInt("name", 100);

        assertThat(params.size()).isEqualTo(2);
        assertThat(params.contains("name")).isTrue();
        assertThat(params.contains("name", "value")).isTrue();
        assertThat(params.containsInt("name", 100)).isTrue();
    }

    @Test
    void testGetAndRemove() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name1", "value1");
        params.add("name2", "value2", "value3");
        params.add("name3", "value4", "value5", "value6");

        assertThat(params.getAndRemove("name1", "defaultvalue")).isEqualTo("value1");
        assertThat(params.getAndRemove("name2")).isEqualTo("value2");
        assertThat(params.getAndRemove("name2")).isNull();
        assertThat(params.getAllAndRemove("name3")).containsExactly("value4", "value5", "value6");
        assertThat(params.size()).isZero();
        assertThat(params.getAndRemove("noname")).isNull();
        assertThat(params.getAndRemove("noname", "defaultvalue")).isEqualTo("defaultvalue");
    }

    @Test
    void whenNameContainsMultipleValuesGetShouldReturnTheFirst() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name1", "value1", "value2");
        assertThat(params.get("name1")).isEqualTo("value1");
    }

    @Test
    void getWithDefaultValueWorks() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name1", "value1");

        assertThat(params.get("name1", "defaultvalue")).isEqualTo("value1");
        assertThat(params.get("noname", "defaultvalue")).isEqualTo("defaultvalue");
    }

    @Test
    void setShouldOverWritePreviousValue() {
        final QueryParamsBase params = newEmptyParams();
        params.set("name", "value1");
        params.set("name", "value2");
        assertThat(params.size()).isEqualTo(1);
        assertThat(params.getAll("name").size()).isEqualTo(1);
        assertThat(params.getAll("name").get(0)).isEqualTo("value2");
        assertThat(params.get("name")).isEqualTo("value2");
    }

    @Test
    void setAllShouldOverwriteSomeAndLeaveOthersUntouched() {
        final QueryParamsBase p1 = newEmptyParams();

        p1.add("name1", "value1");
        p1.add("name2", "value2");
        p1.add("name2", "value3");
        p1.add("name3", "value4");

        final QueryParamsBase p2 = newEmptyParams();
        p2.add("name1", "value5");
        p2.add("name2", "value6");
        p2.add("name1", "value7");

        final QueryParamsBase expected = newEmptyParams();
        expected.add("name1", "value5");
        expected.add("name2", "value6");
        expected.add("name1", "value7");
        expected.add("name3", "value4");

        p1.set(p2);

        assertThat(p1).isEqualTo(expected);
    }

    @Test
    void paramsWithSameNamesAndValuesShouldBeEquivalent() {
        final QueryParamsBase params1 = newEmptyParams();
        params1.add("name1", "value1");
        params1.add("name2", "value2");
        params1.add("name2", "value3");

        final QueryParamsBase params2 = newEmptyParams();
        params2.add("name1", "value1");
        params2.add("name2", "value2");
        params2.add("name2", "value3");

        new EqualsTester()
                .addEqualityGroup(params1, params2)
                .testEquals();
    }

    @Test
    void emptyParamsShouldBeEqual() {
        final QueryParamsBase params1 = newEmptyParams();
        final QueryParamsBase params2 = newEmptyParams();
        assertThat(params2).isEqualTo(params1);
        assertThat(params2.hashCode()).isEqualTo(params1.hashCode());
    }

    @Test
    void paramsWithSameNamesButDifferentValuesShouldNotBeEquivalent() {
        final QueryParamsBase params1 = newEmptyParams();
        params1.add("name1", "value1");
        final QueryParamsBase params2 = newEmptyParams();
        params1.add("name1", "value2");
        assertThat(params1).isNotEqualTo(params2);
    }

    @Test
    void subsetOfParamsShouldNotBeEquivalent() {
        final QueryParamsBase params1 = newEmptyParams();
        params1.add("name1", "value1");
        params1.add("name2", "value2");
        final QueryParamsBase params2 = newEmptyParams();
        params1.add("name1", "value1");
        assertThat(params1).isNotEqualTo(params2);
    }

    @Test
    void paramsWithDifferentNamesAndValuesShouldNotBeEquivalent() {
        final QueryParamsBase p1 = newEmptyParams();
        p1.set("name1", "value1");
        final QueryParamsBase p2 = newEmptyParams();
        p2.set("name2", "value2");

        new EqualsTester()
                .addEqualityGroup(p1)
                .addEqualityGroup(p2)
                .testEquals();
    }

    @Test
    void iterateEmptyParamsShouldThrow() {
        final Iterator<Map.Entry<String, String>> iterator = newEmptyParams().iterator();
        assertThat(iterator.hasNext()).isFalse();
        assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void iteratorShouldReturnAllNameValuePairs() {
        final QueryParamsBase params1 = newEmptyParams();
        params1.add("name1", "value1", "value2");
        params1.add("name2", "value3");
        params1.add("name3", "value4", "value5", "value6");
        params1.add("name1", "value7", "value8");
        assertThat(params1.size()).isEqualTo(8);

        final QueryParamsBase params2 = newEmptyParams();
        for (Map.Entry<String, String> entry : params1) {
            params2.add(entry.getKey(), entry.getValue());
        }

        assertThat(params2).isEqualTo(params1);
    }

    @Test
    void iteratorSetShouldFail() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name1", "value1", "value2", "value3");
        params.add("name2", "value4");
        assertThat(params.size()).isEqualTo(4);

        assertThatThrownBy(() -> params.iterator().next().setValue(""))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testEntryEquals() {
        final QueryParamsBase nameValue = newEmptyParams();
        nameValue.add("name", "value");
        final QueryParamsBase nameValueCopy = newEmptyParams();
        nameValueCopy.add("name", "value");
        final Map.Entry<String, String> same1 = nameValue.iterator().next();
        final Map.Entry<String, String> same2 = nameValueCopy.iterator().next();
        assertThat(same2).isEqualTo(same1);
        assertThat(same2.hashCode()).isEqualTo(same1.hashCode());

        final QueryParamsBase name1Value = newEmptyParams();
        name1Value.add("name1", "value");
        final QueryParamsBase name2Value = newEmptyParams();
        name2Value.add("name2", "value");
        final Map.Entry<String, String> nameDifferent1 = name1Value.iterator().next();
        final Map.Entry<String, String> nameDifferent2 = name2Value.iterator().next();
        assertThat(nameDifferent1).isNotEqualTo(nameDifferent2);
        assertThat(nameDifferent1.hashCode()).isNotEqualTo(nameDifferent2.hashCode());

        final QueryParamsBase nameValue1 = newEmptyParams();
        nameValue1.add("name", "value1");
        final QueryParamsBase nameValue2 = newEmptyParams();
        nameValue2.add("name", "value2");
        final Map.Entry<String, String> valueDifferent1 = nameValue1.iterator().next();
        final Map.Entry<String, String> valueDifferent2 = nameValue2.iterator().next();
        assertThat(valueDifferent1).isNotEqualTo(valueDifferent2);
        assertThat(valueDifferent1.hashCode()).isNotEqualTo(valueDifferent2.hashCode());
    }

    @Test
    void getAllReturnsEmptyListForUnknownName() {
        final QueryParamsBase params = newEmptyParams();
        assertThat(params.getAll("noname").size()).isEqualTo(0);
    }

    @Test
    void setParamsShouldClearAndOverwrite() {
        final QueryParamsBase params1 = newEmptyParams();
        params1.add("name", "value");

        final QueryParamsBase params2 = newEmptyParams();
        params2.add("name", "newvalue");
        params2.add("name1", "value1");

        params1.set(params2);
        assertThat(params2).isEqualTo(params1);
    }

    @Test
    void setParamsShouldOnlyOverwriteParams() {
        final QueryParamsBase params1 = newEmptyParams();
        params1.add("name", "value");
        params1.add("name1", "value1");

        final QueryParamsBase params2 = newEmptyParams();
        params2.add("name", "newvalue");
        params2.add("name2", "value2");

        final QueryParamsBase expected = newEmptyParams();
        expected.add("name", "newvalue");
        expected.add("name1", "value1");
        expected.add("name2", "value2");

        params1.set(params2);
        assertThat(expected).isEqualTo(params1);
    }

    @Test
    void testAddSelf() {
        final QueryParamsBase params = newEmptyParams();
        assertThatThrownBy(() -> params.add(params)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSetSelfIsNoOp() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name", "value");
        params.set(params);
        assertThat(params.size()).isEqualTo(1);
    }

    @Test
    void testToString() {
        QueryParamsBase params = newEmptyParams();
        params.add("name1", "value1");
        params.add("name1", "value2");
        params.add("name2", "value3");
        assertThat(params.toString()).isEqualTo("[name1=value1, name1=value2, name2=value3]");

        params = newEmptyParams();
        params.add("name1", "value1");
        params.add("name2", "value2");
        params.add("name3", "value3");
        assertThat(params.toString()).isEqualTo("[name1=value1, name2=value2, name3=value3]");

        params = newEmptyParams();
        params.add("name1", "value1");
        assertThat(params.toString()).isEqualTo("[name1=value1]");

        params = newEmptyParams();
        assertThat(params.toString()).isEqualTo("[]");
    }

    @Test
    void testNotThrowWhenConvertFails() {
        final QueryParamsBase params = newEmptyParams();
        params.set("name1", "");
        assertThat(params.getInt("name1")).isNull();
        assertThat(params.getInt("name1", 1)).isEqualTo(1);

        assertThat(params.getDouble("name")).isNull();
        assertThat(params.getDouble("name1", 1)).isEqualTo(1);

        assertThat(params.getFloat("name")).isNull();
        assertThat(params.getFloat("name1", Float.MAX_VALUE)).isEqualTo(Float.MAX_VALUE);

        assertThat(params.getLong("name")).isNull();
        assertThat(params.getLong("name1", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);

        assertThat(params.getTimeMillis("name")).isNull();
        assertThat(params.getTimeMillis("name1", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    // Tests forked from io.netty.handler.codec.http.HttpHeadersTest

    @Test
    void testGetOperations() {
        final QueryParamsBase params = newEmptyParams();
        params.add("Foo", "1");
        params.add("Foo", "2");

        assertThat(params.get("Foo")).isEqualTo("1");

        final List<String> values = params.getAll("Foo");
        assertThat(values).containsExactly("1", "2");
    }

    @Test
    void testSetNullParamValue() {
        assertThatThrownBy(() -> newEmptyParams().set("test", (String) null))
                .isInstanceOf(NullPointerException.class);
    }

    // Tests forked from io.netty.handler.codec.http2.DefaultHttp2HeadersTest

    @Test
    void nullParamNameNotAllowed() {
        assertThatThrownBy(() -> newEmptyParams().add(null, "foo")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyParamNameAllowed() {
        final QueryParamsBase params = newEmptyParams();
        params.add("", "foo");
        assertThat(params).containsExactly(Maps.immutableEntry("", "foo"));
    }

    @Test
    void testContainsNameAndValue() {
        final QueryParamsBase params = newEmptyParams();
        params.add("name1", "value1", "value2");
        params.add("name2", "value3");
        params.add("name3", "value4");

        assertThat(params.contains("name1", "value2")).isTrue();
        assertThat(params.contains("name1", "Value2")).isFalse();
        assertThat(params.contains("name2", "value3")).isTrue();
        assertThat(params.contains("name2", "Value3")).isFalse();
    }

    private static QueryParamsBase newEmptyParams() {
        return new QueryParamsBase(16);
    }
}
