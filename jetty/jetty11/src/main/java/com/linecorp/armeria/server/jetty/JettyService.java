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

import static com.linecorp.armeria.internal.common.util.MappedPathUtil.mappedPath;
import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.net.ssl.SSLSession;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpChannelOverHttp;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.HttpInput.Content;
import org.eclipse.jetty.server.HttpInput.EofContent;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.server.servlet.ServletTlsAttributes;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;

/**
 * An {@link HttpService} that dispatches its requests to a web application running in an embedded
 * <a href="https://www.eclipse.org/jetty/">Jetty</a>.
 *
 * @see JettyServiceBuilder
 */
public final class JettyService implements HttpService {

    static final Logger logger = LoggerFactory.getLogger(JettyService.class);

    /**
     * Creates a new {@link JettyService} from an existing Jetty {@link Server}.
     *
     * @param jettyServer the Jetty {@link Server}
     */
    public static JettyService of(Server jettyServer) {
        return of(jettyServer, null);
    }

    /**
     * Creates a new {@link JettyService} from an existing Jetty {@link Server}.
     *
     * @param jettyServer the Jetty {@link Server}
     * @param hostname the default hostname, or {@code null} to use Armeria's default virtual host name.
     */
    public static JettyService of(Server jettyServer, @Nullable String hostname) {
        return of(jettyServer, hostname, false);
    }

    /**
     * Creates a new {@link JettyService} from an existing Jetty {@link Server}.
     *
     * @param jettyServer the Jetty {@link Server}
     * @param hostname the default hostname, or {@code null} to use Armeria's default virtual host name.
     * @param tlsReverseDnsLookup whether perform reverse DNS lookup for the remote IP address on a TLS
     *                            connection. See {@link JettyServiceBuilder#tlsReverseDnsLookup(boolean)}
     *                            for more information.
     */
    public static JettyService of(Server jettyServer, @Nullable String hostname, boolean tlsReverseDnsLookup) {
        requireNonNull(jettyServer, "jettyServer");
        return new JettyService(hostname, tlsReverseDnsLookup, blockingTaskExecutor -> jettyServer,
                                unused -> { /* unused */ });
    }

    /**
     * Returns a new {@link JettyServiceBuilder}.
     */
    public static JettyServiceBuilder builder() {
        return new JettyServiceBuilder();
    }

    @Nullable
    private final String hostname;
    private final boolean tlsReverseDnsLookup;
    private final Function<BlockingTaskExecutor, Server> serverFactory;
    private final Consumer<Server> postStopTask;

    private final Configurator configurator;

    @Nullable
    private Server jettyServer;
    @Nullable
    private ArmeriaConnector connector;

    private com.linecorp.armeria.server.@Nullable Server armeriaServer;
    private boolean startedServer;

    JettyService(@Nullable String hostname,
                 boolean tlsReverseDnsLookup,
                 Function<BlockingTaskExecutor, Server> serverFactory,
                 Consumer<Server> postStopTask) {
        this.hostname = hostname;
        this.tlsReverseDnsLookup = tlsReverseDnsLookup;
        this.serverFactory = serverFactory;
        this.postStopTask = postStopTask;
        configurator = new Configurator();
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) {
        if (armeriaServer != null) {
            if (armeriaServer != cfg.server()) {
                throw new IllegalStateException("cannot be added to more than one server");
            } else {
                return;
            }
        }

        armeriaServer = cfg.server();
        armeriaServer.addListener(configurator);
    }

    void start() throws Exception {
        boolean success = false;
        try {
            assert armeriaServer != null;
            jettyServer = serverFactory.apply(armeriaServer.config().blockingTaskExecutor());
            connector = new ArmeriaConnector(jettyServer, armeriaServer);
            jettyServer.addConnector(connector);

            if (!jettyServer.isRunning()) {
                logger.info("Starting an embedded Jetty: {}", jettyServer);
                jettyServer.start();
                startedServer = true;
            } else {
                startedServer = false;
            }
            success = true;
        } finally {
            if (!success) {
                jettyServer = null;
                connector = null;
            }
        }
    }

    void stop() {
        final Server jettyServer = this.jettyServer;
        this.jettyServer = null;
        connector = null;

        if (jettyServer == null || !startedServer) {
            return;
        }

        try {
            logger.info("Stopping an embedded Jetty: {}", jettyServer);
            jettyServer.stop();
        } catch (Exception e) {
            logger.warn("Failed to stop an embedded Jetty: {}", jettyServer, e);
        }

        postStopTask.accept(jettyServer);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        final String mappedPath = mappedPath(ctx);
        if (mappedPath == null) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   "Invalid matrix variable: " +
                                   ctx.routingContext().requestTarget().maybePathWithMatrixVariables());
        }

        final ArmeriaConnector connector = this.connector;
        assert connector != null;

        final HttpResponseWriter res = HttpResponse.streaming();

        req.aggregate().handle((aReq, cause) -> {
            if (cause != null) {
                cause = Exceptions.peel(cause);
                logger.warn("{} Failed to aggregate a request:", ctx, cause);
                if (cause instanceof HttpStatusException || cause instanceof HttpResponseException) {
                    res.close(cause);
                } else if (res.tryWrite(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR))) {
                    res.close();
                }
                return null;
            }

            try {
                final HttpConfiguration httpConfiguration = connector.getHttpConfiguration();
                final ArmeriaEndPoint endPoint = new ArmeriaEndPoint(ctx, hostname);
                final ArmeriaHttpConnection httpConnection =
                        new ArmeriaHttpConnection(httpConfiguration, connector, endPoint,
                                                  false, ctx, res, aReq.content());
                final HttpChannel httpChannel = httpConnection.getHttpChannel();

                // Armeria has its own timeout mechanism. Disable Jetty's timeout scheduler
                // and abort the Jetty transport when Armeria request is timed out.
                httpChannel.getState().setTimeout(0);
                ctx.whenRequestCancelling().handle((cancellationCause, unused) -> {
                    httpChannel.abort(cancellationCause);
                    return null;
                });

                final Request jReq = httpChannel.getRequest();
                fillRequest(ctx, aReq, jReq, mappedPath);
                final SSLSession sslSession = ctx.sslSession();
                final boolean needsReverseDnsLookup;
                if (sslSession != null) {
                    needsReverseDnsLookup = tlsReverseDnsLookup;
                    ServletTlsAttributes.fill(sslSession, jReq::setAttribute);
                } else {
                    needsReverseDnsLookup = false;
                }

                ctx.blockingTaskExecutor().execute(() -> {
                    // Perform a reverse DNS lookup if needed.
                    if (needsReverseDnsLookup) {
                        try {
                            ctx.remoteAddress().getHostName();
                        } catch (Throwable t) {
                            logger.warn("{} Failed to perform a reverse DNS lookup:", ctx, t);
                        }
                    }

                    // Let Jetty handle the request.
                    try {
                        httpChannel.handle();
                    } catch (Throwable t) {
                        logger.warn("{} Failed to handle a request:", ctx, t);
                    }
                });
            } catch (Throwable t) {
                res.abort(t);
            }
            return null;
        }).exceptionally(CompletionActions::log);

        return res;
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        return ExchangeType.RESPONSE_STREAMING;
    }

    private static void fillRequest(
            ServiceRequestContext ctx, AggregatedHttpRequest aReq, Request jReq, String mappedPath) {
        DispatcherTypeUtil.setRequestType(jReq);
        jReq.setAsyncSupported(true, null);
        jReq.setSecure(ctx.sessionProtocol().isTls());
        jReq.setMetaData(toRequestMetadata(ctx, aReq, mappedPath));
        final HttpHeaders trailers = aReq.trailers();
        if (!trailers.isEmpty()) {
            final HttpField[] httpFields = trailers.stream()
                                                   .map(e -> new HttpField(e.getKey().toString(), e.getValue()))
                                                   .toArray(HttpField[]::new);
            jReq.setTrailerHttpFields(HttpFields.from(httpFields));
        }
    }

    private static MetaData.Request toRequestMetadata(ServiceRequestContext ctx, AggregatedHttpRequest aReq,
                                                      String mappedPath) {
        // Construct the HttpURI
        final StringBuilder uriBuf = new StringBuilder();
        final RequestHeaders aHeaders = aReq.headers();

        uriBuf.append(ctx.sessionProtocol().isTls() ? "https" : "http");
        uriBuf.append("://");
        uriBuf.append(aHeaders.authority());

        final RequestTarget requestTarget = ctx.routingContext().requestTarget();
        if (requestTarget.query() != null) {
            mappedPath = mappedPath + '?' + requestTarget.query();
        }
        uriBuf.append(mappedPath);

        final HttpURI uri = HttpURI.build(HttpURI.build(uriBuf.toString()))
                                   .asImmutable();
        final HttpField[] fields = aHeaders.stream().map(header -> {
            final AsciiString k = header.getKey();
            final String v = header.getValue();
            if (k.charAt(0) != ':') {
                return new HttpField(k.toString(), v);
            }
            if (HttpHeaderNames.AUTHORITY.equals(k) && !aHeaders.contains(HttpHeaderNames.HOST)) {
                // Convert `:authority` to `host`.
                return new HttpField(HttpHeaderNames.HOST.toString(), v);
            }
            return null;
        }).filter(Objects::nonNull).toArray(HttpField[]::new);
        final HttpFields jHeaders = HttpFields.from(fields);

        return new MetaData.Request(aHeaders.method().name(), uri,
                                    ctx.sessionProtocol().isMultiplex() ? HttpVersion.HTTP_2
                                                                        : HttpVersion.HTTP_1_1,
                                    jHeaders, aReq.content().length());
    }

    private static final class ArmeriaHttpConnection extends HttpConnection {

        private static final EofContent EOF_CONTENT = new EofContent();

        private final ServiceRequestContext ctx;
        private final HttpResponseWriter res;
        @Nullable
        private HttpData content;

        MetaData.@Nullable Response response;

        ArmeriaHttpConnection(HttpConfiguration config, Connector connector,
                              EndPoint endPoint, boolean recordComplianceViolations,
                              ServiceRequestContext ctx, HttpResponseWriter res, HttpData content) {
            super(config, connector, endPoint, recordComplianceViolations);
            this.ctx = ctx;
            this.res = res;
            this.content = content;
        }

        @Override
        protected HttpChannelOverHttp newHttpChannel() {
            return new HttpChannelOverHttp(this, getConnector(), getHttpConfiguration(), getEndPoint(), this) {
                @Override
                public Content produceContent() {
                    if (content != null && !content.isEmpty()) {
                        final ByteBuf buf = content.byteBuf();
                        final ByteBuffer nioBuf;
                        if (buf.nioBufferCount() == 1) {
                            nioBuf = buf.nioBuffer();
                        } else {
                            nioBuf = ByteBuffer.wrap(content.array());
                        }
                        content = null;
                        return new Content(nioBuf);
                    }
                    return EOF_CONTENT;
                }
            };
        }

        @Override
        public void send(MetaData.Request unused, MetaData.@Nullable Response response,
                         @Nullable ByteBuffer content, boolean lastContent, Callback callback) {
            if (ctx.isTimedOut()) {
                // Silently discard the write request in case of timeout to match the behavior of Jetty.
                callback.succeeded();
                return;
            }

            try {
                if (response != null) {
                    this.response = response;
                    write(toResponseHeaders(response));
                }

                final int length = content != null ? content.remaining() : 0;
                if (ctx.request().headers().method() != HttpMethod.HEAD && length != 0) {
                    final HttpData data;
                    if (content.hasArray()) {
                        final int from = content.arrayOffset() + content.position();
                        content.position(content.position() + length);
                        data = HttpData.wrap(Arrays.copyOfRange(content.array(), from, from + length));
                    } else {
                        final byte[] buf = new byte[length];
                        content.get(buf);
                        data = HttpData.wrap(buf);
                    }

                    if (lastContent) {
                        final HttpHeaders trailers = toResponseTrailers(response);
                        if (trailers != null) {
                            write(data);
                            write(trailers);
                        } else {
                            write(data.withEndOfStream());
                        }
                        res.close();
                    } else {
                        write(data);
                    }
                } else if (lastContent) {
                    final HttpHeaders trailers = toResponseTrailers(response);
                    if (trailers != null) {
                        write(trailers);
                    }
                    res.close();
                }

                callback.succeeded();
            } catch (Throwable cause) {
                callback.failed(cause);
            }
        }

        @SuppressWarnings("ResultOfMethodCallIgnored")
        private void write(HttpObject o) {
            res.tryWrite(o);
        }

        private static ResponseHeaders toResponseHeaders(MetaData.Response info) {
            final ResponseHeadersBuilder headers = ResponseHeaders.builder();
            headers.status(info.getStatus());
            info.getFields().forEach(e -> headers.add(HttpHeaderNames.of(e.getName()), e.getValue()));
            return headers.build();
        }

        @Nullable
        private HttpHeaders toResponseTrailers(MetaData.@Nullable Response info) {
            if (info == null) {
                info = response;
                if (info == null) {
                    return null;
                }
            }

            final Supplier<HttpFields> trailerSupplier = info.getTrailerSupplier();
            if (trailerSupplier == null) {
                return null;
            }

            final HttpFields fields = trailerSupplier.get();
            if (fields == null || fields.size() == 0) {
                return null;
            }

            final HttpHeadersBuilder headers = HttpHeaders.builder();
            fields.forEach(e -> headers.add(HttpHeaderNames.of(e.getName()), e.getValue()));
            return headers.build();
        }

        @Override
        public boolean isPushSupported() {
            return false;
        }

        @Override
        public void push(MetaData.Request request) {}

        @Override
        public void onCompleted() {
            res.close();
        }

        @Override
        public void abort(Throwable failure) {
            res.close(failure);
        }
    }

    private final class Configurator extends ServerListenerAdapter {
        @Override
        public void serverStarting(com.linecorp.armeria.server.Server server) throws Exception {
            start();
        }

        @Override
        public void serverStopped(com.linecorp.armeria.server.Server server) throws Exception {
            stop();
        }
    }
}
