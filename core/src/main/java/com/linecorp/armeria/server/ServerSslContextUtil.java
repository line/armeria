/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;

/**
 * Utilities for server {@link SslContext}.
 */
final class ServerSslContextUtil {

    /**
     * Makes sure the specified {@link SslContext} is configured properly. If configured as client context or
     * key store password is not given to key store when {@link SslContext} was created using
     * {@link KeyManagerFactory}, the validation will fail and an {@link IllegalStateException} will be raised.
     */
    static SSLSession validateSslContext(SslContext sslContext, TlsEngineType tlsEngineType) {
        if (!sslContext.isServer()) {
            throw new IllegalArgumentException("sslContext: " + sslContext + " (expected: server context)");
        }

        SSLEngine serverEngine = null;
        SSLEngine clientEngine = null;
        final SSLSession sslSession;

        try {
            serverEngine = sslContext.newEngine(ByteBufAllocator.DEFAULT);
            serverEngine.setUseClientMode(false);
            serverEngine.setNeedClientAuth(false);

            // Create a client-side engine with very permissive settings.
            final SslContext sslContextClient =
                    buildSslContext(() -> SslContextBuilder.forClient()
                                                           .trustManager(InsecureTrustManagerFactory.INSTANCE),
                                    tlsEngineType, true, ImmutableList.of());
            clientEngine = sslContextClient.newEngine(ByteBufAllocator.DEFAULT);
            clientEngine.setUseClientMode(true);
            clientEngine.setEnabledProtocols(clientEngine.getSupportedProtocols());
            clientEngine.setEnabledCipherSuites(clientEngine.getSupportedCipherSuites());

            final ByteBuffer packetBuf = ByteBuffer.allocate(clientEngine.getSession().getPacketBufferSize());

            // Wrap an empty app buffer to initiate handshake.
            wrap(clientEngine, packetBuf);

            // Feed the handshake packet to the server engine.
            packetBuf.flip();
            unwrap(serverEngine, packetBuf);

            // See if the server has something to say.
            packetBuf.clear();
            wrap(serverEngine, packetBuf);

            sslSession = serverEngine.getHandshakeSession();
        } catch (SSLException e) {
            throw new IllegalStateException("failed to validate SSL/TLS configuration: " + e.getMessage(), e);
        } finally {
            ReferenceCountUtil.release(serverEngine);
            ReferenceCountUtil.release(clientEngine);
        }

        return sslSession;
    }

    static SslContext buildSslContext(
            Supplier<SslContextBuilder> sslContextBuilderSupplier,
            TlsEngineType tlsEngineType,
            boolean tlsAllowUnsafeCiphers,
            Iterable<? extends Consumer<? super SslContextBuilder>> tlsCustomizers) {
        return SslContextUtil
                .createSslContext(sslContextBuilderSupplier,/* forceHttp1 */ false, tlsEngineType,
                                  tlsAllowUnsafeCiphers, tlsCustomizers, null);
    }

    private static void unwrap(SSLEngine engine, ByteBuffer packetBuf) throws SSLException {
        final ByteBuffer appBuf = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
        // Limit the number of unwrap() calls to 8 times, to prevent a potential infinite loop.
        // 8 is an arbitrary number, it can be any number greater than 2.
        for (int i = 0; i < 8; i++) {
            appBuf.clear();
            final SSLEngineResult result = engine.unwrap(packetBuf, appBuf);
            switch (result.getHandshakeStatus()) {
                case NEED_UNWRAP:
                    continue;
                case NEED_TASK:
                    engine.getDelegatedTask().run();
                    continue;
            }
            break;
        }
    }

    private static void wrap(SSLEngine sslEngine, ByteBuffer packetBuf) throws SSLException {
        final ByteBuffer appBuf = ByteBuffer.allocate(0);
        // Limit the number of wrap() calls to 8 times, to prevent a potential infinite loop.
        // 8 is an arbitrary number, it can be any number greater than 2.
        for (int i = 0; i < 8; i++) {
            final SSLEngineResult result = sslEngine.wrap(appBuf, packetBuf);
            switch (result.getHandshakeStatus()) {
                case NEED_WRAP:
                    continue;
                case NEED_TASK:
                    sslEngine.getDelegatedTask().run();
                    continue;
            }
            break;
        }
    }

    private ServerSslContextUtil() {}
}
