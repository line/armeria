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
        assertThat(urlMapper.getMapping("/a/b/c")).isSameAs(abcRegistration);
        assertThat(urlMapper.getMapping("/a/b/c/")).isSameAs(abcRegistration);
        assertThat(urlMapper.getMapping("/a/b/c/d")).isNull();
        assertThat(urlMapper.getMapping("/a/b/")).isNull();

        final DefaultServletRegistration fooRegistration =
                new DefaultServletRegistration("foo", servlet, servletContext, urlMapper, ImmutableMap.of());
        urlMapper.addMapping("/foo/*", fooRegistration);
        assertThat(urlMapper.getMapping("/foo/bar")).isSameAs(fooRegistration);
        assertThat(urlMapper.getMapping("/foo/bar/baz")).isSameAs(fooRegistration);
        assertThat(urlMapper.getMapping("/foo")).isSameAs(fooRegistration);
        assertThat(urlMapper.getMapping("/fo")).isNull();

        final DefaultServletRegistration htmlRegistration =
                new DefaultServletRegistration("html", servlet, servletContext, urlMapper, ImmutableMap.of());
        urlMapper.addMapping("*.html", htmlRegistration);
        assertThat(urlMapper.getMapping("/foo/bar.html")).isSameAs(fooRegistration);
        assertThat(urlMapper.getMapping("/a.html")).isSameAs(htmlRegistration);
        assertThat(urlMapper.getMapping("/a.htm")).isNull();
        assertThat(urlMapper.getMapping("/a.html/b")).isNull();
    }
}
