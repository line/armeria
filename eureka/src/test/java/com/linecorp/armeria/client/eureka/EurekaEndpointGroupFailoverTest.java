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
package com.linecorp.armeria.client.eureka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.converters.wrappers.CodecWrappers;
import com.netflix.discovery.converters.wrappers.CodecWrappers.JacksonJson;
import com.netflix.discovery.converters.wrappers.EncoderWrapper;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.util.InstanceInfoGenerator;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.testing.FlakyTest;
import com.linecorp.armeria.server.Server;

import io.netty.util.concurrent.ScheduledFuture;

class EurekaEndpointGroupFailoverTest {

    private static final EncoderWrapper encoder = CodecWrappers.getEncoder(JacksonJson.class);

    @FlakyTest
    @Test
    void shouldRefreshEndpointsAfterFailure() {
        final AtomicBoolean firstHealth = new AtomicBoolean();
        final AtomicReference<Server> serverRef = new AtomicReference<>();
        await().untilAsserted(() -> {
            final int randomPort = ThreadLocalRandom.current().nextInt(32768, 65536);
            final Server eurekaServer =
                    Server.builder()
                          .http(randomPort)
                          .service("/apps", (ctx, req) -> {
                              final Applications apps =
                                      InstanceInfoGenerator.newBuilder(6, 2).build().toApplications();
                              if (!firstHealth.get()) {
                                  apps.getRegisteredApplications().get(0).getInstances().get(0)
                                      .setStatus(InstanceStatus.DOWN);
                              }
                              final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                              encoder.encode(apps, bos);
                              return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                     bos.toByteArray());
                          })
                          .build();
            eurekaServer.start().join();
            serverRef.set(eurekaServer);
        });

        final EurekaEndpointGroup eurekaEndpointGroup =
                EurekaEndpointGroup.builder("http://127.0.0.1:" + serverRef.get().activeLocalPort())
                                   .registryFetchIntervalMillis(1000)
                                   .build();

        final CompletableFuture<List<Endpoint>> endpointsCaptor = new CompletableFuture<>();
        eurekaEndpointGroup.addListener(endpointsCaptor::complete);

        // Created 6 instances but 1 is down, so 5 instances.
        assertThat(endpointsCaptor.join()).hasSize(5);
        serverRef.get().stop().join();
        final ScheduledFuture<?> lastScheduledFuture = eurekaEndpointGroup.scheduledFuture();
        await().untilAsserted(() -> {
            // Wait until a newly scheduled request receives an UnprocessedRequestException
            assertThat((Future<?>) eurekaEndpointGroup.scheduledFuture()).isNotNull()
                                                                         .isNotSameAs(lastScheduledFuture);
        });
        firstHealth.set(true);
        serverRef.get().start().join();

        await().untilAsserted(() -> {
            // Make sure EurekaEndpointGroup refreshes the old endpoints after failures
            assertThat(eurekaEndpointGroup.endpoints()).hasSize(6);
        });
    }
}
