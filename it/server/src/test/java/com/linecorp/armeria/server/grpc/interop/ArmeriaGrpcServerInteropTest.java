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

package com.linecorp.armeria.server.grpc.interop;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.google.common.collect.ImmutableList;
import com.squareup.okhttp.ConnectionSpec;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.grpc.ManagedChannel;
import io.grpc.ServerInterceptors;
import io.grpc.internal.testing.TestUtils;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.okhttp.internal.Platform;
import io.grpc.testing.integration.AbstractInteropTest;
import io.grpc.testing.integration.TestServiceGrpc;
import io.grpc.testing.integration.TestServiceImpl;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * Interop test based on grpc-interop-testing. Should provide reasonable confidence in Armeria's
 * handling of the gRPC protocol.
 */
public class ArmeriaGrpcServerInteropTest extends AbstractInteropTest {

    static {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    private static final ApplicationProtocolConfig ALPN = new ApplicationProtocolConfig(
            Protocol.ALPN,
            SelectorFailureBehavior.NO_ADVERTISE,
            SelectedListenerFailureBehavior.ACCEPT,
            ImmutableList.of("h2"));

    private static final AtomicReference<ServiceRequestContext> ctxCapture = new AtomicReference<>();

    private static SelfSignedCertificate ssc;

    @ClassRule
    public static final ServerRule server = new ServerRule() {

        private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            ssc = new SelfSignedCertificate("example.com");

            sb.serverListener(new ServerListenerAdapter() {
                @Override
                public void serverStopped(Server server) {
                    executor.shutdown();
                    ssc.delete();
                }
            });

            sb.https(new InetSocketAddress("127.0.0.1", 0));
            sb.tls(newSslContext());
            sb.maxRequestLength(16 * 1024 * 1024);
            sb.serviceUnder("/", new GrpcServiceBuilder()
                    .addService(ServerInterceptors.intercept(
                            new TestServiceImpl(executor), TestServiceImpl.interceptors()))
                    .build()
                    .decorate((delegate, ctx, req) -> {
                        ctxCapture.set(ctx);
                        return delegate.serve(ctx, req);
                    }));
        }

        private SslContext newSslContext() throws Exception {
            return GrpcSslContexts.forServer(ssc.certificate(), ssc.privateKey())
                                  .applicationProtocolConfig(ALPN)
                                  .trustManager(TestUtils.loadCert("ca.pem"))
                                  .build();
        }
    };

    @After
    public void clearCtxCapture() {
        ctxCapture.set(null);
    }

    @Override
    protected ManagedChannel createChannel() {
        try {
            final int port = server.httpsPort();
            return OkHttpChannelBuilder
                    .forAddress("localhost", port)
                    .useTransportSecurity()
                    .maxInboundMessageSize(16 * 1024 * 1024)
                    .connectionSpec(ConnectionSpec.MODERN_TLS)
                    .overrideAuthority("example.com:" + port)
                    .sslSocketFactory(TestUtils.newSslSocketFactoryForCa(
                            Platform.get().getProvider(), ssc.certificate()))
                    .build();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    protected boolean metricsExpected() {
        return false;
    }

    @Ignore
    @Override
    public void exchangeMetadataUnaryCall() {
        // Disable Metadata tests, which armeria does not support.
    }

    @Ignore
    @Override
    public void exchangeMetadataStreamingCall() {
        // Disable Metadata tests, which armeria does not support.
    }

    @Override
    public void sendsTimeoutHeader() {
        final long configuredTimeoutMinutes = 100;
        final TestServiceGrpc.TestServiceBlockingStub stub =
                blockingStub.withDeadlineAfter(configuredTimeoutMinutes, TimeUnit.MINUTES);
        stub.emptyCall(EMPTY);
        final long transferredTimeoutMinutes = TimeUnit.MILLISECONDS.toMinutes(
                ctxCapture.get().requestTimeoutMillis());
        Assert.assertTrue(
                "configuredTimeoutMinutes=" + configuredTimeoutMinutes +
                ", transferredTimeoutMinutes=" + transferredTimeoutMinutes,
                configuredTimeoutMinutes - transferredTimeoutMinutes >= 0 &&
                configuredTimeoutMinutes - transferredTimeoutMinutes <= 1);
    }

    @Override
    @Ignore
    // TODO(anuraag): Enable after adding support in ServiceRequestContext to define custom timeout handling.
    public void deadlineExceededServerStreaming() {}

    @Override
    @Ignore
    // TODO(anuraag): Enable after adding support in ServiceRequestContext to define custom timeout handling.
    public void deadlineExceeded() {}

    // FIXME: This doesn't work yet and may require some complicated changes. Armeria should continue to accept
    // requests after a channel is gracefully closed but doesn't appear to (maybe because it supports both
    // HTTP1, which has no concept of graceful shutdown, and HTTP2).
    @Ignore
    @Override
    public void gracefulShutdown() {}

    @Ignore
    @Override
    public void customMetadata() {
        // Disable Metadata tests, which armeria does not support.
    }

    @Ignore
    @Override
    public void statusCodeAndMessage() {
        // TODO(trustin): Unignore once gRPC upgrades to a newer version of Mockito.
    }
}
