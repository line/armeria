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

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.converters.wrappers.CodecWrappers;
import com.netflix.discovery.converters.wrappers.CodecWrappers.JacksonJson;
import com.netflix.discovery.converters.wrappers.EncoderWrapper;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.util.InstanceInfoGenerator;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;

class EurekaEndpointGroupTest {

    private static final EncoderWrapper encoder = CodecWrappers.getEncoder(JacksonJson.class);
    private static final String APP_WITH_METADATA = "with-metadata";

    @RegisterExtension
    static final ServerExtension eurekaServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final AtomicInteger requestCounter = new AtomicInteger();
            sb.service("/apps", (ctx, req) -> {
                final int count = requestCounter.getAndIncrement();
                if (count == 0) {
                    // This is for the test that EurekaUpdatingListener automatically retries when
                    // RetryingClient is not used.
                    return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                }
                final Applications apps = InstanceInfoGenerator.newBuilder(6, 2).build().toApplications();
                apps.getRegisteredApplications().get(0).getInstances().get(0).setStatus(InstanceStatus.DOWN);
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                encoder.encode(apps, bos);
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, bos.toByteArray());
            });
            sb.service("/apps/" + APP_WITH_METADATA, (ctx, req) -> {

                final Application app = InstanceInfoGenerator.newBuilder(1, APP_WITH_METADATA)
                                                             .withMetaData(true).build().toApplications()
                                                             .getRegisteredApplications(APP_WITH_METADATA);
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                encoder.encode(app, bos);
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, bos.toByteArray());
            });
        }
    };

    @Test
    void upStatusInstancesAreChosen() {
        final EurekaEndpointGroup eurekaEndpointGroup = EurekaEndpointGroup.builder(eurekaServer.httpUri())
                                                                           .build();

        final CompletableFuture<List<Endpoint>> endpointsCaptor = new CompletableFuture<>();
        eurekaEndpointGroup.addListener(endpointsCaptor::complete);

        // Created 6 instances but 1 is down, so 5 instances.
        assertThat(endpointsCaptor.join()).hasSize(5);
    }

    @Test
    void instanceWithMetadata() {
        final EurekaEndpointGroup eurekaEndpointGroup = EurekaEndpointGroup.builder(eurekaServer.httpUri())
                                                                           .appName(APP_WITH_METADATA)
                                                                           .build();
        final CompletableFuture<List<Endpoint>> endpointsCaptor = new CompletableFuture<>();
        eurekaEndpointGroup.addListener(endpointsCaptor::complete);

        final List<Endpoint> endpoints = endpointsCaptor.join();
        final AttributeKey<String> key = AttributeKey.valueOf("appKey0");
        assertThat(endpoints.get(0).attr(key))
                .isEqualTo("0");

        final @Nullable InstanceInfo instanceInfo = endpoints.get(0).attr(EurekaEndpointGroup.INSTANCE_INFO);
        assertThat(instanceInfo).isNotNull();
    }

    @Test
    void notStoreMetadata() {
        final EurekaEndpointGroup eurekaEndpointGroup = EurekaEndpointGroup.builder(eurekaServer.httpUri())
                                                                           .appName(APP_WITH_METADATA)
                                                                           .instanceMetadataAsAttrs(false)
                                                                           .build();
        final CompletableFuture<List<Endpoint>> endpointsCaptor = new CompletableFuture<>();
        eurekaEndpointGroup.addListener(endpointsCaptor::complete);

        final List<Endpoint> endpoints = endpointsCaptor.join();
        final AttributeKey<String> key = AttributeKey.valueOf("appKey0");
        assertThat(endpoints.get(0).attr(key)).isNull();

        final @Nullable InstanceInfo instanceInfo = endpoints.get(0).attr(EurekaEndpointGroup.INSTANCE_INFO);
        assertThat(instanceInfo).isNull();
    }
}
