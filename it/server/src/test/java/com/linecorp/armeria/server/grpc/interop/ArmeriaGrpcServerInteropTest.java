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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Assert;
import org.junit.ClassRule;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.squareup.okhttp.ConnectionSpec;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit4.server.SelfSignedCertificateRule;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.internal.testing.TestUtils;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.okhttp.internal.Platform;
import io.grpc.testing.integration.AbstractInteropTest;
import io.grpc.testing.integration.TestServiceGrpc;
import io.grpc.testing.integration.TestServiceImpl;

/**
 * Interop test based on grpc-interop-testing. Should provide reasonable confidence in Armeria's
 * handling of the gRPC protocol.
 */
public class ArmeriaGrpcServerInteropTest extends AbstractInteropTest {

    static {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    private static final AtomicReference<ServiceRequestContext> ctxCapture = new AtomicReference<>();

    @ClassRule
    public static SelfSignedCertificateRule ssc = new SelfSignedCertificateRule("example.com");

    @ClassRule
    public static final ServerRule server = new ServerRule() {

        private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        private final HttpServiceWithRoutes grpcService =
                GrpcService.builder()
                           .addService(ServerInterceptors.intercept(new TestServiceImpl(executor),
                                                                    TestServiceImpl.interceptors()))
                           .build();

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.https(new InetSocketAddress("127.0.0.1", 0));
            sb.tls(ssc.certificateFile(), ssc.privateKeyFile());
            sb.tlsCustomizer(ssl -> {
                try {
                    ssl.trustManager(TestUtils.loadCert("ca.pem"));
                } catch (IOException e) {
                    Exceptions.throwUnsafely(e);
                }
            });
            sb.maxRequestLength(16 * 1024 * 1024);
            sb.serviceUnder("/", grpcService.decorate((delegate, ctx, req) -> {
                ctxCapture.set(ctx);
                return delegate.serve(ctx, req);
            }));
        }
    };

    @After
    public void clearCtxCapture() {
        ctxCapture.set(null);
    }

    @Override
    protected ManagedChannelBuilder<?> createChannelBuilder() {
        try {
            final int port = server.httpsPort();
            return OkHttpChannelBuilder
                    .forAddress("localhost", port)
                    .useTransportSecurity()
                    .maxInboundMessageSize(16 * 1024 * 1024)
                    .connectionSpec(ConnectionSpec.MODERN_TLS)
                    .overrideAuthority("example.com:" + port)
                    .sslSocketFactory(TestUtils.newSslSocketFactoryForCa(
                            Platform.get().getProvider(), ssc.certificateFile()));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    protected boolean metricsExpected() {
        // Armeria handles metrics using micrometer and does not support opencensus.
        return false;
    }

    // This base implementation is to check that the client sends the timeout as a request header, not that the
    // server respects it. We don't care about client behavior in this server test, but it doesn't hurt for us
    // to go ahead and check the server respected the header.
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
    public void deadlineExceeded() throws Exception {
        try {
            super.deadlineExceeded();
        } catch (AssertionError e) {
            // TODO(trustin): Remove once https://github.com/grpc/grpc-java/issues/7189 is resolved.
            final String message = e.getMessage();
            if (message != null && message.startsWith("ClientCall started after deadline exceeded")) {
                return;
            }

            throw e;
        }
    }
}
