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
    public void filter() {
        DocServiceFilter filter = DocServiceFilter.pluginName("foo");
        assertThat(filter.filter("foo", "bar", "baz")).isTrue();
        assertThat(filter.filter("foo1", "bar", "baz")).isFalse();

        filter = DocServiceFilter.serviceName("foo", "bar");
        assertThat(filter.filter("foo", "bar", "baz")).isTrue();
        assertThat(filter.filter("foo1", "bar", "baz")).isFalse();

        filter = DocServiceFilter.serviceName("bar");
        assertThat(filter.filter("foo", "bar", "baz")).isTrue();
        assertThat(filter.filter("foo", "bar1", "baz")).isFalse();

        filter = DocServiceFilter.methodName("foo", "bar", "baz");
        assertThat(filter.filter("foo", "bar", "baz")).isTrue();
        assertThat(filter.filter("foo", "bar1", "baz")).isFalse();

        filter = DocServiceFilter.methodName("bar", "baz");
        assertThat(filter.filter("foo", "bar", "baz")).isTrue();
        assertThat(filter.filter("foo", "bar1", "baz")).isFalse();

        filter = DocServiceFilter.methodName("baz");
        assertThat(filter.filter("foo", "bar", "baz")).isTrue();
        assertThat(filter.filter("foo", "bar", "baz1")).isFalse();

        filter = DocServiceFilter.servicePattern("^b.+r$");
        assertThat(filter.filter("foo", "bar", "baz")).isTrue();
        assertThat(filter.filter("foo", "bar1", "baz")).isFalse();

        filter = DocServiceFilter.methodPattern("^b.+z$");
        assertThat(filter.filter("foo", "bar", "baz")).isTrue();
        assertThat(filter.filter("foo", "bar", "baz1")).isFalse();
    }
}
