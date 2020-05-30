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

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class ServletEmptyContextPathTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            final ServletBuilder servletBuilder = new ServletBuilder(sb);
            sb = servletBuilder
                    .servlet("/", new HomeServlet())
                    .servlet("/bar", new BarServlet())
                    .servlet("/end/", new EndServlet())
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
    }

    static class HomeServlet extends HttpServlet {
        private static final Logger logger = LoggerFactory.getLogger(HomeServlet.class);

        @Override
        public void init(ServletConfig config) throws ServletException {
            logger.info("init ...");
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            logger.info("GET: {}", request.getRequestURI());
            try {
                // Context path: "" and servlet path: ""
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getNamedDispatcher("/")).getName()).isEqualTo("");
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getNamedDispatcher("")).getName()).isEqualTo("");
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.getWriter().write("get home");
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        @Override
        protected void doPut(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        @Override
        protected void doDelete(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    static class BarServlet extends HttpServlet {
        private static final Logger logger = LoggerFactory.getLogger(BarServlet.class);

        @Override
        public void init(ServletConfig config) throws ServletException {
            logger.info("init ...");
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            logger.info("GET: {}", request.getRequestURI());
            try {
                // Context path: "" and servlet path: "/bar"
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getNamedDispatcher("/bar")).getName()).isEqualTo("/bar");
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getNamedDispatcher("/bar/")).getName()).isEqualTo("/bar");
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.getWriter().write("get bar");
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        @Override
        protected void doPut(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        @Override
        protected void doDelete(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    static class EndServlet extends HttpServlet {
        private static final Logger logger = LoggerFactory.getLogger(EndServlet.class);

        @Override
        public void init(ServletConfig config) throws ServletException {
            logger.info("init ...");
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            logger.info("GET: {}", request.getRequestURI());
            try {
                // Context path: "" and servlet path: "/end"
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getNamedDispatcher("/end")).getName()).isEqualTo("/end");
                assertThat(((ServletRequestDispatcher) request
                        .getServletContext().getNamedDispatcher("/end/")).getName()).isEqualTo("/end");
                response.setStatus(HttpStatus.OK.code());
                response.setContentType(MediaType.HTML_UTF_8.toString());
                response.getWriter().write("get end");
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        @Override
        protected void doPut(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        @Override
        protected void doDelete(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
