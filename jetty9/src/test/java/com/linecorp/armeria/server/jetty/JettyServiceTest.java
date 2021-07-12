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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.annotations.ServletContainerInitializersStarter;
import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.webapp.WebAppContainerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JettyServiceTest extends WebAppContainerTest {

    private static final Logger logger = LoggerFactory.getLogger(JettyServiceTest.class);

    private static final List<Object> jettyBeans = new ArrayList<>();

    /**
     * Dynamically resolve {@link Response#setTrailers(Supplier)} because it doesn't exist in 9.3.
     */
    @Nullable
    private static final MethodHandle jResSetTrailers;

    static {
        MethodHandle setTrailers = null;
        try {
            setTrailers = MethodHandles.lookup().unreflect(
                    Response.class.getMethod("setTrailers", Supplier.class));
        } catch (Throwable t) {
            // Jetty 9.3
            if (!Jetty.VERSION.startsWith("9.3.")) {
                throw new Error("Failed to find Response.setTrailers(Supplier) in Jetty " + Jetty.VERSION, t);
            }
        }
        jResSetTrailers = setTrailers;
    }

    /**
     * Indicates no exceptions were captured in {@link #capturedException}.
     */
    private static final Exception NO_EXCEPTION = new Exception();

    /**
     * Captures the exception raised in a Jetty handler block.
     */
    private static final AtomicReference<Throwable> capturedException = new AtomicReference<>();

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
                    "/headers-only",
                    newJettyService((req, res) -> {
                        res.setStatus(204);
                        res.addHeader("x-headers", "foo");
                        res.getOutputStream().close();
                    }));

            sb.service(
                    "/headers-trailers",
                    newJettyService((req, res) -> {
                        res.setStatus(200);
                        res.addHeader("x-headers", "bar");
                        jResSetTrailers.invoke(res, (Supplier<HttpFields>) () -> {
                            final HttpFields trailers = new HttpFields();
                            trailers.put("x-trailers", "baz");
                            return trailers;
                        });
                        res.getOutputStream().close();
                    }));

            sb.service("/headers-data-trailers",
                       newJettyService((req, res) -> {
                           res.setStatus(200);
                           res.addHeader("x-headers", "bar");
                           jResSetTrailers.invoke(res, (Supplier<HttpFields>) () -> {
                               final HttpFields trailers = new HttpFields();
                               trailers.put("x-trailers", "baz");
                               return trailers;
                           });
                           final ServletOutputStream out = res.getOutputStream();
                           out.print("qux");
                           out.close();
                       }));

            // Attempts to write after the request handling is completed due to timeout or disconnection,
            // and captures the exception raised by {@link ServletOutputStream#println()}.
            sb.service("/timeout/{timeout}",
                       newJettyService((req, res) -> {
                           final ServiceRequestContext ctx = ServiceRequestContext.current();
                           ctx.setRequestTimeoutMillis(Integer.parseInt(ctx.pathParam("timeout")));
                           res.setStatus(200);
                           final ServletOutputStream out = res.getOutputStream();
                           await().until(() -> ctx.log().isComplete());
                           try {
                               out.println();
                               out.close();
                               capturedException.set(NO_EXCEPTION);
                           } catch (Throwable cause) {
                               capturedException.set(cause);
                           }
                       }));

            // Attempts to write again after closing the output stream,
            // and captures the exception raised by the failed write attempt after close().
            sb.service("/write-after-completion",
                       newJettyService((req, res) -> {
                           res.setStatus(200);
                           final ServletOutputStream out = res.getOutputStream();
                           out.print("before close");
                           out.close();
                           try {
                               out.print("after close");
                               capturedException.set(NO_EXCEPTION);
                           } catch (Throwable cause) {
                               capturedException.set(cause);
                           }

                       }));

            sb.service("/stream/{totalSize}/{chunkSize}",
                       newJettyService(new AsyncStreamingHandlerFunction()));
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

    @Test
    void headersOnly() throws Exception {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/headers-only")
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.NO_CONTENT);
        assertThat(res.headers()).containsAll(HttpHeaders.of("x-headers", "foo"));
        assertThat(res.trailers()).isEmpty();
    }

    @Test
    void headersAndTrailers() throws Exception {
        assumeThat(jResSetTrailers).isNotNull();
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/headers-trailers")
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers()).containsAll(HttpHeaders.of("x-headers", "bar"));
        assertThat(res.content().length()).isZero();
        assertThat(res.trailers()).containsAll(HttpHeaders.of("x-trailers", "baz"));
    }

    @Test
    void headersDataAndTrailers() throws Exception {
        assumeThat(jResSetTrailers).isNotNull();
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/headers-data-trailers")
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers()).containsAll(HttpHeaders.of("x-headers", "bar"));
        assertThat(res.contentAscii()).isEqualTo("qux");
        assertThat(res.trailers()).containsAll(HttpHeaders.of("x-trailers", "baz"));
    }

    /**
     * An {@link IOException} or {@link EofException} should be raised if a handler closed
     * its {@link ServletOutputStream} and then tries to write something to it.
     */
    @Test
    void writingAfterCompletion() {
        capturedException.set(null);
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(server.httpUri() + "/write-after-completion")
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("before close");
        // An `IOException` should be raised when writing after closing the `ServletOutputStream`.
        await().untilAsserted(() -> assertThat(capturedException).isNotNull());
        final Throwable cause = capturedException.get();
        assertThat(cause).isInstanceOf(IOException.class)
                         .hasMessage("Closed");
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void sendingResponseOnDisconnectedConnection(SessionProtocol protocol) {
        capturedException.set(null);
        // Send a request that doesn't time out until the client gives up.
        // The client will give up quickly and disconnect.
        final String uri = protocol.uriText() + "://127.0.0.1:" + server.httpPort() +
                           "/timeout/" + Integer.MAX_VALUE;
        assertThatThrownBy(() -> {
            WebClient.of()
                     .prepare()
                     .get(uri)
                     .responseTimeoutMillis(1)
                     .execute()
                     .aggregate()
                     .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ResponseTimeoutException.class);

        // No exception should be raised when a Jetty handler writes something after timeout.
        await().untilAsserted(() -> assertThat(capturedException).hasValue(NO_EXCEPTION));
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void sendingResponseToTimedOutRequest(SessionProtocol protocol) {
        capturedException.set(null);
        // Send a request that times out after 1ms.
        final String uri = protocol.uriText() + "://127.0.0.1:" + server.httpPort() + "/timeout/1";
        final AggregatedHttpResponse res =
                WebClient.of()
                         .get(uri)
                         .aggregate()
                         .join();

        assertThat(res.status()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
        // No exception should be raised when a Jetty handler writes something after timeout.
        await().untilAsserted(() -> assertThat(capturedException).hasValue(NO_EXCEPTION));
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

    private static JettyService newJettyService(SimpleHandlerFunction func) {
        return JettyService.builder()
                           .handler(new AbstractHandler() {
                               @Override
                               public void handle(String target, Request baseRequest,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) throws ServletException {
                                   try {
                                       func.handle(baseRequest, (Response) response);
                                   } catch (Throwable t) {
                                       throw new ServletException(t);
                                   }
                               }
                           })
                           .build();
    }

    @FunctionalInterface
    private interface SimpleHandlerFunction {
        void handle(Request req, Response res) throws Throwable;
    }

    private static class AsyncStreamingHandlerFunction implements SimpleHandlerFunction {
        /**
         * A 128KiB full of characters.
         */
        private static final byte[] chunk = Strings.repeat("0123456789abcdef", 128 * 1024 / 16)
                                                   .getBytes(StandardCharsets.US_ASCII);

        @Override
        public void handle(Request req, Response res) throws Exception {
            final ServiceRequestContext ctx = ServiceRequestContext.current();
            final int totalSize = Integer.parseInt(ctx.pathParam("totalSize"));
            final int chunkSize = Integer.parseInt(ctx.pathParam("chunkSize"));
            final AsyncContext asyncCtx = req.startAsync();
            ctx.eventLoop().schedule(() -> {
                res.setStatus(200);
                stream(ctx, asyncCtx, res, totalSize, chunkSize);
            }, 500, TimeUnit.MILLISECONDS);
        }

        private static void stream(ServiceRequestContext ctx, AsyncContext asyncCtx, Response res,
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
