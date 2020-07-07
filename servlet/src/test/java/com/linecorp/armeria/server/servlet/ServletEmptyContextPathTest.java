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
package com.linecorp.armeria.server.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServletEmptyContextPathTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            final ServletBuilder servletBuilder = new ServletBuilder(sb);
            sb = servletBuilder
                    .servlet("root", new HomeServletTest(), "/")
                    .servlet("bar", new BarServletTest(), "/bar")
                    .servlet("end", new EndServletTest(), "/end/")
                    .servlet("servlet_path",
                             new PathInfoServletTest(), "/servlet/path/*")
                    .build();
        }
    };

    @Test
    void doGet() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        AggregatedHttpResponse res;

        res = client.get("").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get home");

        res = client.get("/").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get home");

        res = client.get("/bar").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get bar");

        res = client.get("/bar/").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get bar");

        res = client.get("/end").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get end");

        res = client.get("/end/").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get end");

        res = client.get("/servlet/path/path/info").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(new String(res.content().array())).isEqualTo("get path info");
    }

    static class HomeServletTest extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            try {
                // Context path: "" and servlet path: ""
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getRequestDispatcher("/")).getName()).isEqualTo("root");
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getRequestDispatcher("")).getName()).isEqualTo("root");
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.getWriter().write("get home");
                response.getWriter().flush();
                response.getWriter().close();
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    static class BarServletTest extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            try {
                // Context path: "" and servlet path: "/bar"
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getRequestDispatcher("/bar")).getName()).isEqualTo("bar");
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getRequestDispatcher("/bar/")).getName()).isEqualTo("bar");
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.getWriter().write("get bar");
                response.getWriter().flush();
                response.getWriter().close();
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    static class EndServletTest extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            try {
                // Context path: "" and servlet path: "/end"
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getRequestDispatcher("/end")).getName()).isEqualTo("end");
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getRequestDispatcher("/end/")).getName()).isEqualTo("end");
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.getWriter().write("get end");
                response.getWriter().flush();
                response.getWriter().close();
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    static class PathInfoServletTest extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            try {
                // Context path: "" and servlet path: "/servlet/path" path info: "/path/info"
                assertThat(request.getContextPath()).isEqualTo("");
                assertThat(request.getServletPath()).isEqualTo("/servlet/path");
                assertThat(request.getPathInfo()).isEqualTo("/path/info");
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.getWriter().write("get path info");
                response.getWriter().flush();
                response.getWriter().close();
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }
}
