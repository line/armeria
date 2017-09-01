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

package com.linecorp.armeria.common;

import static io.netty.util.AsciiString.of;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Test;

import io.netty.util.AsciiString;

public class HttpHeadersTest {

    @Test
    public void testCaseInsensitiveHeaderNames() throws Exception {
        final HttpHeaders headers = HttpHeaders.of(of("header1"), "value1",
                                                   of("HEADER2"), "value2",
                                                   of("Header3"), "VALUE3");

        assertThat(headers.get(of("HeAdEr1")), is("value1"));
        assertThat(headers.get(of("header2")), is("value2"));
        assertThat(headers.get(of("HEADER3")), is("VALUE3"));

        assertThat(headers.names(), containsInAnyOrder(of("header1"), of("header2"), of("header3")));
    }

    @Test
    public void testInvalidHeaderName() throws Exception {
        assertThatThrownBy(() -> HttpHeaders.of((AsciiString) null, "value1"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> HttpHeaders.of(AsciiString.of(""), "value1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
