/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.servlet.http.HttpServlet;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

class ServletUrlMapperTest {

    @Test
    void urlMapper() {
        final DefaultServletContext servletContext = new DefaultServletContext("/servlet");
        final HttpServlet servlet = mock(HttpServlet.class);
        final ServletUrlMapper urlMapper = new ServletUrlMapper();

        final DefaultServletRegistration abcRegistration =
                new DefaultServletRegistration("abc", servlet, servletContext, urlMapper, ImmutableMap.of());
        urlMapper.addMapping("/a/b/c", abcRegistration);
        assertThat(urlMapper.getMapping("/a/b/c").getValue()).isSameAs(abcRegistration);
        assertThat(urlMapper.getMapping("/a/b/c/").getValue()).isSameAs(abcRegistration);
        assertThat(urlMapper.getMapping("/a/b/c/d")).isNull();
        assertThat(urlMapper.getMapping("/a/b/")).isNull();

        final DefaultServletRegistration fooRegistration =
                new DefaultServletRegistration("foo", servlet, servletContext, urlMapper, ImmutableMap.of());
        urlMapper.addMapping("/foo/*", fooRegistration);
        assertThat(urlMapper.getMapping("/foo/bar").getValue()).isSameAs(fooRegistration);
        assertThat(urlMapper.getMapping("/foo/bar/baz").getValue()).isSameAs(fooRegistration);
        assertThat(urlMapper.getMapping("/foo").getValue()).isSameAs(fooRegistration);
        assertThat(urlMapper.getMapping("/fo")).isNull();

        final DefaultServletRegistration htmlRegistration =
                new DefaultServletRegistration("html", servlet, servletContext, urlMapper, ImmutableMap.of());
        urlMapper.addMapping("*.html", htmlRegistration);
        assertThat(urlMapper.getMapping("/foo/bar.html").getValue()).isSameAs(fooRegistration);
        assertThat(urlMapper.getMapping("/a.html").getValue()).isSameAs(htmlRegistration);
        assertThat(urlMapper.getMapping("/a.htm")).isNull();
        assertThat(urlMapper.getMapping("/a.html/b")).isNull();
    }
}
