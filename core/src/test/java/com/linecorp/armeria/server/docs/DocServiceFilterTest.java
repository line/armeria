/*
 *  Copyright 2019 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class DocServiceFilterTest {

    @Test
    public void test() {
        DocServiceFilter filter = DocServiceFilter.ofPluginName("foo");
        assertThat(filter.test("foo", "bar", "baz")).isTrue();
        assertThat(filter.test("foo1", "bar", "baz")).isFalse();

        filter = DocServiceFilter.ofServiceName("foo", "bar");
        assertThat(filter.test("foo", "bar", "baz")).isTrue();
        assertThat(filter.test("foo1", "bar", "baz")).isFalse();

        filter = DocServiceFilter.ofServiceName("bar");
        assertThat(filter.test("foo", "bar", "baz")).isTrue();
        assertThat(filter.test("foo", "bar1", "baz")).isFalse();

        filter = DocServiceFilter.ofMethodName("foo", "bar", "baz");
        assertThat(filter.test("foo", "bar", "baz")).isTrue();
        assertThat(filter.test("foo", "bar1", "baz")).isFalse();

        filter = DocServiceFilter.ofMethodName("bar", "baz");
        assertThat(filter.test("foo", "bar", "baz")).isTrue();
        assertThat(filter.test("foo", "bar1", "baz")).isFalse();

        filter = DocServiceFilter.ofMethodName("baz");
        assertThat(filter.test("foo", "bar", "baz")).isTrue();
        assertThat(filter.test("foo", "bar", "baz1")).isFalse();

        filter = DocServiceFilter.ofRegex("bar#baz");
        assertThat(filter.test("foo", "bar", "baz")).isTrue();
        assertThat(filter.test("foo", "bar1", "baz")).isFalse();
    }
}
