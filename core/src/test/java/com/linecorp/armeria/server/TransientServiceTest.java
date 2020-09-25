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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.logging.LoggingService;

class TransientServiceTest {

    @Test
    void requestToTransientService_notLogged() throws InterruptedException {
        final CompletableFuture<RequestLog> accessLogFuture = new CompletableFuture<>();
        final Logger logger = mock(Logger.class);
        final Server server =
                Server.builder()
                      .service("/", (ctx, req) -> HttpResponse.of(200))
                      .accessLogWriter(accessLogFuture::complete, false)
                      .decorator(LoggingService.builder().logger(logger).newDecorator())
                      // TransientService.
                      .service("/health", HealthCheckService.of())
                      .route()
                      .path("/transient")
                      .transientService(true)
                      .build((ctx, req) -> HttpResponse.of(200))
                      .build();
        server.start().join();
        final ServerPort serverPort = server.activePort(SessionProtocol.HTTP);
        final WebClient client = WebClient.of("http://127.0.0.1:" + serverPort.localAddress().getPort());
        assertThat(client.head("/health").aggregate().join().status()).isSameAs(HttpStatus.OK);
        assertThat(client.get("/transient").aggregate().join().status()).isSameAs(HttpStatus.OK);

        Thread.sleep(1000);

        // accessLogFuture is not complete.
        assertThat(accessLogFuture.isDone()).isFalse();
        verifyNoInteractions(logger);

        server.stop().join();
    }

    @Test
    void requestToTransientService_setTransientServiceAsFalse() throws InterruptedException {
        final CompletableFuture<RequestLog> accessLogFuture = new CompletableFuture<>();
        final Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(false, false);
        final Server server =
                Server.builder()
                      .service("/", (ctx, req) -> HttpResponse.of(200))
                      .accessLogWriter(accessLogFuture::complete, false)
                      .decorator(LoggingService.builder().logger(logger).newDecorator())
                      .route().head("/health")
                      .transientService(false)
                      .build(HealthCheckService.of())
                      .build();
        server.start().join();
        final ServerPort serverPort = server.activePort(SessionProtocol.HTTP);
        final WebClient client = WebClient.of("http://127.0.0.1:" + serverPort.localAddress().getPort());
        assertThat(client.head("/health").aggregate().join().status()).isSameAs(HttpStatus.OK);

        // accessLogFuture is complete.
        accessLogFuture.join();
        await().untilAsserted(() -> {
            verify(logger, times(2)).isDebugEnabled();
            verifyNoMoreInteractions(logger);
        });

        server.stop().join();
    }
}
