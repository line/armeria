/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.jetty;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.annotations.ServletContainerInitializersStarter;
import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.testing.webapp.WebAppContainerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JettyServiceTest extends WebAppContainerTest {

    private static final Logger logger = LoggerFactory.getLogger(JettyServiceTest.class);

    private static final List<Object> jettyBeans = new ArrayList<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            sb.serviceUnder(
                    "/jsp/",
                    JettyService.builder()
                                .handler(newWebAppContext())
                                .configurator(s -> jettyBeans.addAll(s.getBeans()))
                                .build()
                                .decorate(LoggingService.newDecorator()));

            sb.serviceUnder(
                    "/default/",
                    JettyService.builder()
                                .handler(new DefaultHandler())
                                .build());

            final ResourceHandler resourceHandler = new ResourceHandler();
            resourceHandler.setResourceBase(webAppRoot().getPath());
            sb.serviceUnder(
                    "/resources/",
                    JettyService.builder()
                                .handler(resourceHandler)
                                .build());

            sb.service(
                    "/stream/{totalSize}/{chunkSize}",
                    JettyService.builder()
                                .handler(new AsyncStreamingHandler())
                                .build());
        }
    };

    static WebAppContext newWebAppContext() throws MalformedURLException {
        final WebAppContext handler = new WebAppContext();
        handler.setContextPath("/");
        handler.setBaseResource(Resource.newResource(webAppRoot()));
        handler.setClassLoader(new URLClassLoader(
                new URL[] {
                        Resource.newResource(new File(webAppRoot(),
                                                      "WEB-INF" + File.separatorChar +
                                                      "lib" + File.separatorChar +
                                                      "hello.jar")).getURI().toURL()
                },
                JettyService.class.getClassLoader()));

        handler.addBean(new ServletContainerInitializersStarter(handler), true);
        handler.setAttribute(
                "org.eclipse.jetty.containerInitializers",
                Collections.singletonList(new ContainerInitializer(new JettyJasperInitializer(), null)));
        return handler;
    }

    @Override
    protected ServerExtension server() {
        return server;
    }

    @Test
    void configurator() throws Exception {
        assertThat(jettyBeans)
                .hasAtLeastOneElementOfType(ThreadPool.class)
                .hasAtLeastOneElementOfType(WebAppContext.class);
    }

    @Test
    void defaultHandlerFavicon() throws Exception {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/default/favicon.ico")
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.parse("image/x-icon"));
        assertThat(res.content().length()).isGreaterThan(0);
    }

    @Test
    void resourceHandlerWithLargeResource() throws Exception {
        testLarge("/resources/large.txt", true);
    }

    /**
     * Makes sure asynchronous streaming works for various sizes of responses.
     */
    @ParameterizedTest
    @CsvSource({
            "8192, 8192",     // 8KiB in a single write
            "8192, 128",      // 8KiB in many writes
            "131072, 131072", // 128KiB in a single write
            "131072, 8192",   // 128KiB in many writes
    })
    void asyncRequest(int totalSize, int chunkSize) throws Exception {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/stream/" + totalSize + '/' + chunkSize)
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentAscii()).hasSize(totalSize)
                                      .matches("^(?:0123456789abcdef)*$");
    }

    private static class AsyncStreamingHandler extends AbstractHandler {
        /**
         * A 128KiB full of characters.
         */
        private static final byte[] chunk = Strings.repeat("0123456789abcdef", 128 * 1024 / 16)
                                                   .getBytes(StandardCharsets.US_ASCII);

        @Override
        public void handle(String target, Request baseRequest,
                           HttpServletRequest request,
                           HttpServletResponse response) {
            final ServiceRequestContext ctx = ServiceRequestContext.current();
            final int totalSize = Integer.parseInt(ctx.pathParam("totalSize"));
            final int chunkSize = Integer.parseInt(ctx.pathParam("chunkSize"));
            final AsyncContext asyncCtx = request.startAsync();
            ctx.eventLoop().schedule(() -> {
                response.setStatus(200);
                stream(ctx, asyncCtx, response, totalSize, chunkSize);
            }, 500, TimeUnit.MILLISECONDS);
        }

        private static void stream(ServiceRequestContext ctx, AsyncContext asyncCtx, HttpServletResponse res,
                                   int remainingBytes, int chunkSize) {
            final int bytesToWrite;
            final boolean lastChunk;
            if (remainingBytes <= chunkSize) {
                bytesToWrite = remainingBytes;
                lastChunk = true;
            } else {
                bytesToWrite = chunkSize;
                lastChunk = false;
            }

            try {
                final ServletOutputStream out = res.getOutputStream();
                out.write(chunk, 0, bytesToWrite);
                if (lastChunk) {
                    out.close();
                } else {
                    ctx.eventLoop().execute(
                            () -> stream(ctx, asyncCtx, res, remainingBytes - bytesToWrite, chunkSize));
                }
            } catch (Exception e) {
                logger.warn("{} Unexpected exception:", ctx, e);
            } finally {
                if (lastChunk) {
                    asyncCtx.complete();
                }
            }
        }
    }
}
