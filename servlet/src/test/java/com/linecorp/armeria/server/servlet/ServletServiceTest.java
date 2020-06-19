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

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.junit.ClassRule;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.netty.handler.codec.http.HttpConstants;

public class ServletServiceTest {

    @ClassRule
    public static ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final ServletBuilder servletBuilder = new ServletBuilder(sb);
            servletBuilder.servlet("/home", TestServlet.class.getName())
                          .attribute("attribute1", "value1")
                          .initParameter("param1", "value1")
                          .mimeMapping("avi", "video/x-msvideo")
                          .mimeMapping("bmp", "image/bmp")
                          .build();
        }
    };

    static {
        rule.start();
    }

    @Test
    void doGet() throws Exception {
        final HttpGet req = new HttpGet(rule.httpUri() + "/home?test=1&array=abc&array=a%20bc#code=3");
        req.setHeader(HttpHeaderNames.ACCEPT_LANGUAGE.toString(), "en-US");
        req.setHeader(HttpHeaderNames.COOKIE.toString(), "armeria=session_id_1");
        req.setHeader("start_date", "Tue May 12 12:55:48 2020");
        req.setHeader("start_flag", "1");
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue())
                        .startsWith(MediaType.HTML_UTF_8.toString());
                assertThat(res.getHeaders(HttpHeaderNames.SET_COOKIE.toString())[0]
                                   .getElements()[0].getValue()).isEqualTo("session_id_1");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("welcome");
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    @Test
    void doGetNotFound() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(rule.httpUri() + "/test"))) {
                assertThat(res.getStatusLine().getStatusCode()).isEqualTo(404);
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    @Test
    void doPost() throws Exception {
        final HttpPost req = new HttpPost(rule.httpUri() + "/home");
        final List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("application", "Armeria Servlet"));
        final UrlEncodedFormEntity entity = new UrlEncodedFormEntity(params, HttpConstants.DEFAULT_CHARSET);
        req.setEntity(entity);
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue())
                        .startsWith(MediaType.HTML_UTF_8.toString());
                assertThat(res.getHeaders(HttpHeaderNames.SET_COOKIE.toString())[0]
                                   .getElements()[0].getValue()).isEqualTo("session_id_1");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("Armeria Servlet");
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    @Test
    void doPut() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    new HttpPut(rule.httpUri() + "/home"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue())
                        .startsWith(MediaType.HTML_UTF_8.toString());
                assertThat(res.getHeaders(HttpHeaderNames.SET_COOKIE.toString())[0]
                                   .getElements()[0].getValue()).isEqualTo("session_id_1");
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    @Test
    void doDelete() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    new HttpDelete(rule.httpUri() + "/home?test=1"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue())
                        .startsWith(MediaType.HTML_UTF_8.toString());
                assertThat(res.getHeaders(HttpHeaderNames.SET_COOKIE.toString())[0]
                                   .getElements()[0].getValue()).isEqualTo("session_id_1");
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    static class TestServlet extends HttpServlet {
        private static final Logger logger = LoggerFactory.getLogger(TestServlet.class);

        @Override
        public void init(ServletConfig config) throws ServletException {
            logger.info("init ...");
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            logger.info("GET: {}", request.getRequestURI());
            try {
                final SimpleDateFormat df =
                        new SimpleDateFormat("EEE MMMM d HH:mm:ss yyyy", Locale.ENGLISH);
                assertThat(df.format(new Date(request.getDateHeader("start_date")))).isEqualTo(
                        "Tue May 12 12:55:48 2020");
                assertThat(request.getHeader("start_date")).isEqualTo("Tue May 12 12:55:48 2020");
                assertThat(request.getIntHeader("start_flag")).isEqualTo(1);
                assertThat(request.getIntHeader("test")).isEqualTo(-1);
                assertThat(request.getCookies()[0].getName() + "=" +
                           request.getCookies()[0].getValue()).isEqualTo("armeria=session_id_1");
                assertThat(request.getRequestURI()).isEqualTo("/home");
                assertThat(request.getRequestURL().toString()).isEqualTo("http://127.0.0.1:" +
                                                                         request.getServerPort() + "/home");
                assertThat(request.getPathInfo()).isEqualTo(null);
                assertThat(request.getQueryString()).isEqualTo("test=1&array=abc&array=a+bc");
                assertThat(request.getServletPath()).isEqualTo("/home");
                assertThat(
                        Collections.list(request.getHeaderNames()).contains("start_date")).isEqualTo(true);
                assertThat(
                        Collections.list(request.getHeaders("start_date")).size()).isEqualTo(1);
                assertThat(request.getContextPath()).isEqualTo("");
                assertThat(request.isRequestedSessionIdValid()).isEqualTo(true);
                assertThat(request.isRequestedSessionIdFromCookie()).isEqualTo(true);
                assertThat(request.isRequestedSessionIdFromURL()).isEqualTo(false);
                request.setAttribute("attribute1", "value1");
                request.setAttribute("attribute2", "value2");
                request.setAttribute("attribute2", null);
                request.getRequestDispatcher("/home");
                assertThat(request.getAttribute("attribute1")).isEqualTo("value1");
                assertThat(
                        Collections.list(request.getAttributeNames()).contains("attribute1")).isEqualTo(true);
                request.removeAttribute("attribute1");
                assertThat(
                        Collections.list(request.getAttributeNames()).contains("attribute1")).isEqualTo(false);
                assertThat(request.getServerPort()).isEqualTo(request.getServerPort());
                assertThat(request.getRemoteAddr()).isEqualTo("127.0.0.1");
                assertThat(request.getRemoteHost()).isEqualTo("localhost");
                assertThat(request.getRemotePort()).isEqualTo(request.getRemotePort());
                assertThat(request.isSecure()).isEqualTo(false);
                assertThat(request.getLocalAddr()).isEqualTo("/127.0.0.1:" + request.getServerPort());
                request.getLocalName();
                assertThat(request.getLocalPort()).isEqualTo(request.getServerPort());
                assertThat(request.isAsyncStarted()).isEqualTo(false);
                assertThat(request.isAsyncSupported()).isEqualTo(false);
                assertThat(request.getDispatcherType()).isEqualTo(DispatcherType.REQUEST);
                assertThat(request.getServletContext().getMimeType("profile.bmp")).isEqualTo("image/bmp");
                request.getServletContext().getRequestDispatcher("/app/abc");
                request.getServletContext().getRequestDispatcher("abc");
                assertThat(Objects.isNull(
                        request.getServletContext().getNamedDispatcher("/home"))).isEqualTo(false);
                assertThat(Objects.isNull(
                        request.getServletContext().getNamedDispatcher("/abc"))).isEqualTo(true);
                assertThat(Objects.isNull(
                        request.getServletContext().getServlet("/home"))).isEqualTo(false);
                assertThat(Objects.isNull(
                        request.getServletContext().getServlet("/abc"))).isEqualTo(true);
                assertThat(
                        Collections.list(request.getServletContext().getServlets()).size()).isEqualTo(1);
                assertThat(
                        Collections.list(request.getServletContext().getServletNames()).size()).isEqualTo(1);
                request.getServletContext().log(request.getServletContext().getServerInfo());
                assertThat(Collections.list(
                        request.getServletContext().getInitParameterNames()).size()).isEqualTo(1);
                assertThat(request.getServletContext().getInitParameter("param1")).isEqualTo("value1");
                assertThat(Collections.list(
                        request.getServletContext().getAttributeNames()).size()).isEqualTo(1);
                assertThat(request.getServletContext().getAttribute("attribute1")).isEqualTo("value1");
                request.getServletContext().removeAttribute("attribute1");
                assertThat(request.getServletContext().getAttribute("attribute1")).isEqualTo(null);
                assertThat(request.getServletContext().getServletContextName()).isEqualTo("");
                assertThat(request.getCharacterEncoding()).isEqualTo(
                        HttpConstants.DEFAULT_CHARSET.name());
                assertThat(request.getServletContext().getResponseCharacterEncoding()).isEqualTo(
                        HttpConstants.DEFAULT_CHARSET.name());
                assertThat(Objects.isNull(
                        request.getServletContext().getVirtualServerName())).isEqualTo(false);
                assertThat(Objects.isNull(
                        request.getServletContext().getDefaultSessionTrackingModes())).isEqualTo(false);
                assertThat(Objects.isNull(
                        request.getServletContext().getEffectiveSessionTrackingModes())).isEqualTo(false);
                assertThat(Objects.isNull(request.getServletContext().getFilterRegistration(
                        "authenticate"))).isEqualTo(true);

                final DefaultServletRegistration servletRegistration = (DefaultServletRegistration) request
                        .getServletContext().getServletRegistration("/home");
                assertThat(servletRegistration.getInitParameters().size()).isEqualTo(0);
                assertThat(servletRegistration.getInitParameter("param1")).isEqualTo(null);
                assertThat(Objects.isNull(servletRegistration.getServlet())).isEqualTo(false);
                assertThat(servletRegistration.getName()).isEqualTo("/home");
                assertThat(servletRegistration.getClassName()).isEqualTo(
                        "com.linecorp.armeria.server.servlet.ServletServiceTest$TestServlet");
                assertThat(servletRegistration.getMappings().size()).isEqualTo(1);
                request.getServletContext().log(new Exception("Test log"), "Test log exception");
                request.getServletContext().log("Test log", new Exception("Test log exception"));
                request.getServletContext().setAttribute("attribute1", null);
                assertThat(servletRegistration.getServletConfig().getServletName()).isEqualTo("/home");
                assertThat(servletRegistration.getServletConfig().getInitParameter("test")).isEqualTo(null);
                assertThat(servletRegistration.getServletConfig()
                                              .getInitParameterNames().hasMoreElements()).isEqualTo(false);
                assertThat(request.getServletContext().getServletRegistrations().size()).isEqualTo(1);
                request.getServletContext().getClassLoader();

                final ServletRequestDispatcher dispatcher =
                        (ServletRequestDispatcher) request.getServletContext()
                                                          .getRequestDispatcher(request.getRequestURI());
                assertThat(dispatcher.getPath()).isEqualTo("/home");
                assertThat(dispatcher.getName()).isEqualTo("/home");
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.addCookie(new Cookie("armeria", "session_id_1"));
                response.getWriter().write("welcome");
                response.getWriter().close();
                final UrlMapper urlMapper = new UrlMapper<String>(true);
                urlMapper.addMapping("/test", "Exact path", "/test");
                urlMapper.addMapping("/test/*", "End with *", "/test/*");
                urlMapper.addMapping("/test/*.html", "Contain *", "/test/*.html");
                assertThat(urlMapper.getMapping("/test")).isEqualTo("/test");
                assertThat(urlMapper.getMapping("/test/path/info")).isEqualTo("/test/*");
                assertThat(urlMapper.getMapping("/test/path/info.html")).isEqualTo("/test/*.html");
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            logger.info("POST: {}", request.getRequestURI());
            try {
                assertThat(request.getParameterMap().size()).isEqualTo(1);
                assertThat(request.getParameterMap().entrySet().size()).isEqualTo(1);
                assertThat(request.getParameterMap().containsKey("application")).isEqualTo(true);
                assertThat(request.getParameterMap().get("app")).isEqualTo(null);
                assertThat(request.getParameterMap().containsKey("null")).isEqualTo(false);
                assertThat(
                        Collections.list(request.getParameterNames()).contains("application")).isEqualTo(true);
                assertThat(request.getParameterValues("application")[0]).isEqualTo("Armeria Servlet");
                assertThat(request.getParameterValues("empty")).isEqualTo(null);
                assertThat(request.getServletContext().getSessionTimeout()).isEqualTo(30);
                assertThat(request.getServletContext().getMajorVersion()).isEqualTo(4);
                assertThat(request.getServletContext().getMinorVersion()).isEqualTo(0);
                assertThat(request.getServletContext().getEffectiveMajorVersion()).isEqualTo(4);
                assertThat(request.getServletContext().getEffectiveMinorVersion()).isEqualTo(0);

                assertThat(response.encodeRedirectURL("/")).isEqualTo("/");
                assertThat(response.encodeRedirectUrl("/")).isEqualTo("/");

                response.setDateHeader("end_date", new Date().getTime());
                response.setHeader("header1", "value1");
                assertThat(response.getHeader("header1")).isEqualTo("value1");
                response.setIntHeader("header2", 2);
                assertThat(response.getHeaderNames().size()).isEqualTo(4);
                assertThat(response.getHeaders("header2").size()).isEqualTo(1);
                response.addDateHeader("end_date", new Date().getTime());
                response.addHeader("header1", "value1");
                response.addIntHeader("header2", 2);

                response.setLocale(Locale.US);
                assertThat(response.getLocale()).isEqualTo(Locale.US);
                response.setCharacterEncoding(HttpConstants.DEFAULT_CHARSET.name());
                assertThat(response.getCharacterEncoding()).isEqualTo(
                        HttpConstants.DEFAULT_CHARSET.name());

                response.setContentType(MediaType.HTML_UTF_8.toString());
                assertThat(response.getContentType()).isEqualTo(MediaType.HTML_UTF_8.toString());
                response.addCookie(new Cookie("armeria", "session_id_1"));
                response.setStatus(200);
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.containsHeader("header1")).isEqualTo(true);
                response.getWriter().write(request.getParameter("application"));
                response.getWriter().close();
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }

        @Override
        protected void doPut(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            logger.info("PUT: {}", request.getRequestURI());
            try {
                assertThat(request.getParameterMap().entrySet().size()).isEqualTo(0);
                assertThat(request.getDateHeader("test")).isEqualTo(-1);
                assertThat(request.getParameter("test")).isEqualTo(null);
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.addCookie(new Cookie("armeria", "session_id_1"));

                response.getWriter().print(true);
                response.getWriter().print('c');
                response.getWriter().print(1);
                response.getWriter().print(1L);
                response.getWriter().print(1.1f);
                response.getWriter().print(1.1D);
                response.getWriter().print(new char[1]);
                response.getWriter().print("test");
                response.getWriter().print(new HashMap<>());
                response.getWriter().println();
                response.getWriter().println(true);
                response.getWriter().println('c');
                response.getWriter().println(1);
                response.getWriter().println(1L);
                response.getWriter().println(1.1f);
                response.getWriter().println(1.1D);
                response.getWriter().println(new char[1]);
                response.getWriter().println("test");
                response.getWriter().println(new HashMap<>());
                response.getWriter().append('c');
                response.getWriter().printf("test %d", 1);
                response.getWriter().printf(Locale.US, "test %d", 1);
                response.getWriter().format("test %d", 1);
                response.getWriter().format(Locale.US, "test %d", 1);
                final CharSequence cs = "test";
                response.getWriter().append(cs);
                response.getWriter().append(cs, 0, 1);
                response.getWriter().append(null);
                response.getWriter().checkError();
                final char[] c = new char[1];
                c[0] = 'c';
                response.getWriter().write(c, 0, 1);

                response.getWriter().write("put");
                response.getWriter().close();
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }

        @Override
        protected void doDelete(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            logger.info("DELETE: {}", request.getRequestURI());
            try {
                assertThat(response.encodeURL("http://localhost")).isEqualTo("http://localhost");
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.addCookie(new Cookie("armeria", "session_id_1"));

                request.getContentLength();
                request.getContentLengthLong();
                request.getContentType();
                request.getScheme();
                request.getProtocol();
                request.getServerName();
                request.getServletContext().getMimeType(null);
                request.getServletContext().getMimeType("test");
                request.getServletContext().getMimeType("test.");
                response.setStatus(HttpStatus.OK.code(), "success");
                response.getOutputStream().write(1);
                response.getOutputStream().close();
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    static class TestFilter implements Filter {
        private static final Logger logger = LoggerFactory.getLogger(TestFilter.class);

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            logger.info("Do filter successfully!");
            chain.doFilter(request, response);
        }
    }
}
