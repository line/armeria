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
package com.linecorp.armeria.server.eureka;

import static com.linecorp.armeria.server.eureka.EurekaUpdatingListenerBuilder.DEFAULT_DATA_CENTER_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.Inet4Address;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class EurekaUpdatingListenerTest {

    private static final String INSTANCE_ID = "i-00000000";
    private static final String APP_NAME = "application0";

    private static final ObjectMapper mapper =
            new ObjectMapper().enable(DeserializationFeature.UNWRAP_ROOT_VALUE)
                              .setSerializationInclusion(Include.NON_NULL);

    private static final AtomicReference<HttpData> registerContentCaptor = new AtomicReference<>();

    private static final CompletableFuture<RequestHeaders> heartBeatHeadersCaptor = new CompletableFuture<>();
    private static final CompletableFuture<RequestHeaders> deregisterHeadersCaptor = new CompletableFuture<>();

    @RegisterExtension
    static final ServerExtension eurekaServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/apps/" + APP_NAME, (ctx, req) -> {
                if (req.method() != HttpMethod.POST) {
                    registerContentCaptor.set(null);
                    return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
                }
                final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                req.aggregate().handle((aggregatedRes, cause) -> {
                    registerContentCaptor.set(aggregatedRes.content());
                    future.complete(HttpResponse.of(HttpStatus.NO_CONTENT));
                    return null;
                });
                return HttpResponse.from(future);
            });
            final AtomicInteger heartBeatRequestCounter = new AtomicInteger();
            sb.service("/apps/" + APP_NAME + '/' + INSTANCE_ID, (ctx, req) -> {
                req.aggregate();
                if (req.method() == HttpMethod.PUT) {
                    final int count = heartBeatRequestCounter.getAndIncrement();
                    if (count == 0) {
                        // This is for the test that EurekaUpdatingListener automatically retries when
                        // RetryingClient is not used.
                        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                    heartBeatHeadersCaptor.complete(req.headers());
                } else if (req.method() == HttpMethod.DELETE) {
                    deregisterHeadersCaptor.complete(req.headers());
                }
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @Test
    void registerHeartBeatAndDeregisterAreSent() throws IOException {
        final EurekaUpdatingListener listener =
                EurekaUpdatingListener.builder(eurekaServer.httpUri())
                                      .instanceId(INSTANCE_ID)
                                      .renewalIntervalMillis(2000)
                                      .leaseDurationMillis(10000)
                                      .appName(APP_NAME)
                                      .build();

        final Server application = Server.builder()
                                         .http(0)
                                         .https(0)
                                         .tlsSelfSigned()
                                         .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                         .service("/health", HealthCheckService.of())
                                         .serverListener(listener)
                                         .build();
        application.start().join();
        await().until(() -> registerContentCaptor.get() != null);
        final InstanceInfo instanceInfo = mapper.readValue(registerContentCaptor.get().array(),
                                                           InstanceInfo.class);
        final InstanceInfo expected = expectedInstanceInfo(application);
        assertThat(instanceInfo).isEqualTo(expected);

        final RequestHeaders heartBeatHeaders = heartBeatHeadersCaptor.join();
        final QueryParams queryParams = QueryParams.fromQueryString(
                heartBeatHeaders.path().substring(heartBeatHeaders.path().indexOf('?') + 1));
        assertThat(queryParams.get("status")).isEqualTo("UP");
        assertThat(queryParams.get("lastDirtyTimestamp"))
                .isEqualTo(String.valueOf(instanceInfo.getLastDirtyTimestamp()));

        application.stop().join();
        final RequestHeaders deregisterHeaders = deregisterHeadersCaptor.join();
        assertThat(deregisterHeaders.path()).isEqualTo("/apps/application0/i-00000000");
    }

    private static InstanceInfo expectedInstanceInfo(Server application) {
        final InstanceInfoBuilder builder = new InstanceInfoBuilder().appName(APP_NAME)
                                                                     .instanceId(INSTANCE_ID)
                                                                     .hostname(application.defaultHostname())
                                                                     .renewalIntervalSeconds(2)
                                                                     .leaseDurationSeconds(10);
        final Inet4Address inet4Address = SystemInfo.defaultNonLoopbackIpV4Address();
        final String hostnameOrIpAddr;
        if (inet4Address != null) {
            final String ipAddr = inet4Address.getHostAddress();
            builder.ipAddr(ipAddr);
            hostnameOrIpAddr = ipAddr;
        } else {
            hostnameOrIpAddr = null;
        }
        final int port = application.activePort(SessionProtocol.HTTP).localAddress().getPort();
        final int securePort = application.activePort(SessionProtocol.HTTPS).localAddress().getPort();
        builder.vipAddress(application.defaultHostname() + ':' + port)
               .secureVipAddress(application.defaultHostname() + ':' + securePort)
               .port(port)
               .securePort(securePort)
               .healthCheckUrl("http://" + hostnameOrIpAddr + ':' + port + "/health")
               .secureHealthCheckUrl("https://" + hostnameOrIpAddr + ':' + securePort + "/health")
               .dataCenterName(DEFAULT_DATA_CENTER_NAME);
        return builder.build();
    }

    @Test
    void specifiedPortIsUsed() throws IOException {
        final EurekaUpdatingListener listener =
                EurekaUpdatingListener.builder(eurekaServer.httpUri())
                                      .instanceId(INSTANCE_ID)
                                      .renewalInterval(Duration.ofSeconds(2))
                                      .leaseDuration(Duration.ofSeconds(10))
                                      .port(1) // misconfigued!
                                      .appName(APP_NAME)
                                      .build();

        final Server application = Server.builder()
                                         .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                         .serverListener(listener)
                                         .build();
        application.start().join();
        await().until(() -> registerContentCaptor.get() != null);
        final InstanceInfo instanceInfo = mapper.readValue(registerContentCaptor.get().array(),
                                                           InstanceInfo.class);
        final int port = instanceInfo.getPort().getPort();
        // The specified port number is used although the port is not actually used.
        assertThat(port).isEqualTo(1);
        assertThat(instanceInfo.getLeaseInfo().getRenewalIntervalInSecs()).isEqualTo(2);
        assertThat(instanceInfo.getLeaseInfo().getDurationInSecs()).isEqualTo(10);
        application.stop().join();
    }
}
