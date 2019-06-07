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
package com.linecorp.armeria.server.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ServerCacheControl;

public class HttpFileTest {

    @Test
    public void additionalHeaders() throws Exception {
        final HttpFile f = HttpFileBuilder.ofResource(ClassLoader.getSystemClassLoader(),
                                                      "java/lang/Object.class")
                                          .addHeader("foo", "1")
                                          .addHeader("foo", "2")
                                          .setHeader("bar", "3")
                                          .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                          .cacheControl(ServerCacheControl.REVALIDATED)
                                          .build();

        // Make sure content-type auto-detection is disabled.
        assertThat(((AbstractHttpFile) f).contentType()).isNull();

        // Make sure all additional headers are set as expected.
        final HttpHeaders headers = f.readHeaders();
        assertThat(headers).isNotNull();
        assertThat(headers.getAll(HttpHeaderNames.of("foo"))).containsExactly("1", "2");
        assertThat(headers.getAll(HttpHeaderNames.of("bar"))).containsExactly("3");
        assertThat(headers.getAll(HttpHeaderNames.CONTENT_TYPE))
                .containsExactly(MediaType.PLAIN_TEXT_UTF_8.toString());
        assertThat(headers.getAll(HttpHeaderNames.CACHE_CONTROL))
                .containsExactly(ServerCacheControl.REVALIDATED.asHeaderValue());
    }

    @Test
    public void leadingSlashInResourcePath() throws Exception {
        final HttpFile f = HttpFile.ofResource(ClassLoader.getSystemClassLoader(), "/java/lang/Object.class");
        final HttpFileAttributes attrs = f.readAttributes();
        assertThat(attrs).isNotNull();
        assertThat(attrs.length()).isPositive();
    }
}
