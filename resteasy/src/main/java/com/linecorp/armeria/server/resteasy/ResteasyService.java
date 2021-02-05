/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.resteasy;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;
import javax.ws.rs.core.SecurityContext;

import org.jboss.resteasy.core.ResteasyContext;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.core.ThreadLocalResteasyProviderFactory;
import org.jboss.resteasy.plugins.server.embedded.SecurityDomain;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.jboss.resteasy.specimpl.ResteasyUriInfo.InitData;
import org.jboss.resteasy.spi.HttpResponseCodes;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.jboss.resteasy.util.EmbeddedServerHelper;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.io.Files;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;

/**
 * RESTEasy service implementing Armeria's {@link HttpService}. This provides the main entry point for JAX-RS
 * server-side processing based on Armeria.
 */
@UnstableApi
public final class ResteasyService implements HttpService {

    private static final int URI_INFO_CACHE_MAX_SIZE = 1024;
    private static final Duration URI_INFO_CACHE_MAX_IDLE = Duration.ofMinutes(1L);

    /**
     * Creates a builder for {@link ResteasyService}.
     * @param deployment An instance of {@link ResteasyDeployment}
     * @return new {@link ResteasyServiceBuilder}
     */
    public static ResteasyServiceBuilder builder(ResteasyDeployment deployment) {
        return new ResteasyServiceBuilder(deployment);
    }

    private final ResteasyDeployment deployment;
    private final SynchronousDispatcher dispatcher;
    private final ResteasyProviderFactory providerFactory;
    private final String contextPath;
    @Nullable
    private final SecurityDomain securityDomain;
    private final Cache<String, InitData> uriInfoCache;
    private final int maxRequestBufferSize;
    private final int responseBufferSize;

    ResteasyService(ResteasyDeployment deployment, String contextPath,
                    @Nullable SecurityDomain securityDomain,
                    int maxRequestBufferSize, int responseBufferSize) {
        this.deployment = requireNonNull(deployment, "deployment");
        requireNonNull(contextPath, "contextPath");

        // get helper and check the deployment
        final EmbeddedServerHelper serverHelper = new EmbeddedServerHelper();
        serverHelper.checkDeployment(deployment); // this initializes the deployment

        // initialize context path
        final String appContextPath = checkPath(
                serverHelper.checkAppDeployment(deployment)); // fetches @ApplicationPath path
        contextPath = checkPath(contextPath);
        @SuppressWarnings("UnstableApiUsage")
        final String combinedPath = Files.simplifyPath(contextPath + appContextPath);
        this.contextPath = "/".equals(combinedPath) ? "" : combinedPath;

        this.securityDomain = securityDomain;
        dispatcher = (SynchronousDispatcher) deployment.getDispatcher();
        providerFactory = deployment.getProviderFactory();
        uriInfoCache = buildCache(URI_INFO_CACHE_MAX_SIZE, null, URI_INFO_CACHE_MAX_IDLE);

        checkArgument(maxRequestBufferSize >= 0,
                      "maxRequestBufferSize: %s (expected: >= 0)", maxRequestBufferSize);
        this.maxRequestBufferSize = maxRequestBufferSize;
        checkArgument(responseBufferSize > 0,
                      "responseBufferSize: %s (expected: > 0)", responseBufferSize);
        this.responseBufferSize = responseBufferSize;
    }

    /**
     * Context path of the RESTEasy service.
     */
    public String path() {
        return contextPath.isEmpty() ? "/" : contextPath;
    }

    /**
     * Registers {@link ResteasyService} with Armeria {@link ServerBuilder}.
     */
    public ServerBuilder register(ServerBuilder serverBuilder) {
        requireNonNull(serverBuilder, "serverBuilder");
        final String path = path();
        serverBuilder.service("prefix:" + path, this);
        final ServerListenerBuilder serverListenerBuilder = ServerListener.builder();
        serverListenerBuilder.whenStarting(this::start);
        serverListenerBuilder.whenStopping(this::stop);
        serverBuilder.serverListener(serverListenerBuilder.build());
        return serverBuilder;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        final RequestHeaders headers = req.headers();
        final Long contentLength = headers.getLong(HttpHeaderNames.CONTENT_LENGTH);
        if (contentLength != null && contentLength <= maxRequestBufferSize) {
            // aggregate bounded requests
            return HttpResponse.from(req.aggregate().thenCompose(r -> serveAsync(ctx, r)));
        } else {
            return HttpResponse.from(serveAsync(ctx, req));
        }
    }

    private CompletableFuture<HttpResponse> serveAsync(ServiceRequestContext ctx, AggregatedHttpRequest req) {
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final ResteasyHttpResponseImpl resteasyResponse =
                new ResteasyHttpResponseImpl(responseFuture, responseBufferSize);
        final ResteasyUriInfo uriInfo = createResteasyUriInfo(req.uri(), contextPath);
        final AbstractResteasyHttpRequest<?> resteasyRequest =
                new AggregatedResteasyHttpRequestImpl(ctx, req, resteasyResponse, uriInfo, dispatcher);
        serveAsync(ctx, resteasyRequest, resteasyResponse);
        return responseFuture;
    }

    private CompletableFuture<HttpResponse> serveAsync(ServiceRequestContext ctx, HttpRequest req) {
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final ResteasyHttpResponseImpl resteasyResponse =
                new ResteasyHttpResponseImpl(responseFuture, responseBufferSize);
        final ResteasyUriInfo uriInfo = createResteasyUriInfo(req.uri(), contextPath);
        final AbstractResteasyHttpRequest<?> resteasyRequest =
                new StreamingResteasyHttpRequestImpl(ctx, req, resteasyResponse, uriInfo, dispatcher);
        // we have to switch the thread context here!
        CompletableFuture.runAsync(() -> serveAsync(ctx, resteasyRequest, resteasyResponse));
        return responseFuture;
    }

    private void serveAsync(ServiceRequestContext ctx, AbstractResteasyHttpRequest<?> request,
                            ResteasyHttpResponseImpl response) {
        final ResteasyProviderFactory defaultInstance = ResteasyProviderFactory.getInstance();
        if (defaultInstance instanceof ThreadLocalResteasyProviderFactory) {
            ThreadLocalResteasyProviderFactory.push(providerFactory);
        }
        try {
            // manage SecurityContext
            final SecurityContext securityContext;
            if (securityDomain != null) {
                final BasicToken basicToken = AuthTokenExtractors.basic().apply(request.requestHeaders());
                if (basicToken == null) {
                    // no Basic Authorization present, e.g. "Authorization: Basic YWxhZGRpbjpvcGVuc2VzYW1l"
                    // respond with "Basic" authentication request
                    response.getOutputHeaders().add(HttpHeaderNames.WWW_AUTHENTICATE.toString(), "Basic");
                    response.sendError(HttpResponseCodes.SC_UNAUTHORIZED);
                    response.finish();
                    return;
                }

                final Principal principal;
                try {
                    principal = securityDomain.authenticate(basicToken.username(), basicToken.password());
                } catch (SecurityException e) {
                    // provided Basic Authorization is not authenticated successfully
                    response.sendError(HttpResponseCodes.SC_UNAUTHORIZED);
                    response.finish();
                    return;
                }

                securityContext = SecurityContextImpl.basic(principal, securityDomain);
            } else {
                securityContext = SecurityContextImpl.insecure();
            }

            ResteasyContext.pushContext(SecurityContext.class,
                                        securityContext); // should we set unsecure context?
            ResteasyContext.pushContext(ServiceRequestContext.class, ctx);
            try {
                dispatcher.invoke(request, response);
            } finally {
                ResteasyContext.clearContextData();
            }
            if (!request.getAsyncContext().isSuspended()) {
                response.finish();
            }
        } finally {
            if (defaultInstance instanceof ThreadLocalResteasyProviderFactory) {
                ThreadLocalResteasyProviderFactory.pop();
            }
        }
    }

    /**
     * This handler available to execute custom startup sequence.
     */
    private void start(Server server) {
        deployment.start();
    }

    /**
     * This handler available to execute custom shutdown sequence.
     */
    private void stop(Server server) {
        deployment.stop();
    }

    private ResteasyUriInfo createResteasyUriInfo(URI requestUri, String contextPath) {
        final String uri = requestUri.toString();
        if (InitData.canBeCached(uri)) {
            final InitData initData;
            try {
                initData = uriInfoCache.get(requestUri.getRawPath(), () -> new InitData(uri, contextPath));
            } catch (ExecutionException e) {
                // this shall never happen
                throw new RuntimeException(e);
            }
            return new ResteasyUriInfo(uri, contextPath, initData);
        } else {
            return new ResteasyUriInfo(uri, contextPath);
        }
    }

    private static String checkPath(@Nullable String path) {
        if (path == null || "/".equals(path)) {
            return "";
        } else if (!path.startsWith("/")) {
            return '/' + path;
        } else {
            return path;
        }
    }

    private static <K, V> Cache<K, V> buildCache(long maxSize, @Nullable Duration maxAge,
                                                 @Nullable Duration maxIdle, int concurrencyLevel) {
        final CacheBuilder<Object, Object> cacheBuilder = CacheBuilder.newBuilder()
                                                                      .maximumSize(maxSize)
                                                                      .concurrencyLevel(concurrencyLevel);
        if (maxAge != null) {
            cacheBuilder.expireAfterWrite(maxAge);
        }
        if (maxIdle != null) {
            cacheBuilder.expireAfterAccess(maxIdle);
        }
        return cacheBuilder.build();
    }

    private static <K, V> Cache<K, V> buildCache(long maxSize, @Nullable Duration maxAge,
                                                 @Nullable Duration maxIdle) {
        return buildCache(maxSize, maxAge, maxIdle, Runtime.getRuntime().availableProcessors());
    }
}
