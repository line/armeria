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
package com.linecorp.armeria.internal.common.eureka;

import static java.util.Objects.requireNonNull;

import java.net.URI;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo.InstanceStatus;

/**
 * A Eureka {@link WebClient} which communicates to the
 * <a href="https://github.com/Netflix/eureka/wiki/Eureka-REST-operations">Eureka registry</a>.
 */
public final class EurekaWebClient {

    private static final String APPS = "/apps/";
    private static final String VIPS = "/vips/";
    private static final String SVIPS = "/svips/";
    private static final String INSTANCES = "/instances/";

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.WRAP_ROOT_VALUE)
                                                          .setSerializationInclusion(Include.NON_NULL);

    /**
     * Creates a new instance.
     */
    public EurekaWebClient(WebClient webClient) {
        this.webClient = requireNonNull(webClient, "webClient");
    }

    /**
     * Returns the {@link URI} of the Eureka registry.
     */
    public URI uri() {
        return webClient.uri();
    }

    /**
     * Registers the specified {@link InstanceInfo} to the Eureka registry.
     */
    @CheckReturnValue
    public HttpResponse register(InstanceInfo info) {
        requireNonNull(info, "info");
        final String path = APPS + info.getAppName();
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, path)
                                                     .contentType(MediaType.JSON)
                                                     .build();
        try {
            return webClient.execute(headers, mapper.writeValueAsBytes(info));
        } catch (JsonProcessingException e) {
            return HttpResponse.ofFailure(e);
        }
    }

    /**
     * Sends the heart beat to the Eureka registry.
     */
    @CheckReturnValue
    public HttpResponse sendHeartBeat(String appName, String instanceId, InstanceInfo instanceInfo,
                                      @Nullable InstanceStatus overriddenStatus) {
        requireNonNull(appName, "appName");
        requireNonNull(instanceId, "instanceId");
        requireNonNull(instanceInfo, "instanceInfo");
        final String path = APPS + appName + '/' + instanceId;
        final QueryParamsBuilder queryBuilder =
                QueryParams.builder()
                           .add("status", instanceInfo.getStatus().toString())
                           .addLong("lastDirtyTimestamp", instanceInfo.getLastDirtyTimestamp());
        if (overriddenStatus != null) {
            queryBuilder.add("overriddenstatus", overriddenStatus.toString());
        }
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.PUT, path + '?' + queryBuilder.toQueryString())
                              .accept(MediaType.JSON)
                              .build();
        return webClient.execute(headers);
    }

    /**
     * Deregisters the specified {@code instanceId} in {@code appName} from the Eureka registry.
     */
    @CheckReturnValue
    public HttpResponse cancel(String appName, String instanceId) {
        requireNonNull(appName, "appName");
        requireNonNull(instanceId, "instanceId");
        final String path = APPS + appName + '/' + instanceId;
        return webClient.delete(path);
    }

    /**
     * Retrieves the registry information whose regions are the specified {@code regions} from the Eureka.
     */
    @CheckReturnValue
    public HttpResponse getApplications(Iterable<String> regions) {
        return getApplications(APPS, requireNonNull(regions, "regions"));
    }

    private HttpResponse getApplications(String path, Iterable<String> regions) {
        if (!Iterables.isEmpty(regions)) {
            final QueryParams queryParams = QueryParams.of("regions", String.join(",", regions));
            path = path + '?' + queryParams.toQueryString();
        }
        return sendGetRequest(path);
    }

    private HttpResponse sendGetRequest(String path) {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.GET, path)
                                                     .accept(MediaType.JSON)
                                                     .build();
        return webClient.execute(headers);
    }

    /**
     * Retrieves the delta updates between the last fetch and the current one. See
     * https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#fetch-registry.
     */
    @CheckReturnValue
    public HttpResponse getDelta(Iterable<String> regions) {
        return getApplications(APPS + "delta", requireNonNull(regions, "regions"));
    }

    /**
     * Retrieves the registry information whose application name is the specified {@code appName}
     * from the Eureka.
     */
    @CheckReturnValue
    public HttpResponse getApplication(String appName) {
        return sendGetRequest(APPS + requireNonNull(appName, "appName"));
    }

    /**
     * Retrieves the registry information whose VIP address is the specified {@code vipAddress} and regions
     * are the specified {@code regions} from the Eureka.
     */
    @CheckReturnValue
    public HttpResponse getVip(String vipAddress, Iterable<String> regions) {
        return getApplications(VIPS + requireNonNull(vipAddress, "vipAddress"),
                               requireNonNull(regions, "regions"));
    }

    /**
     * Retrieves the registry information whose VIP address is the specified {@code secureVipAddress}
     * and regions are the specified {@code regions} from the Eureka.
     */
    @CheckReturnValue
    public HttpResponse getSecureVip(String secureVipAddress, Iterable<String> regions) {
        return getApplications(SVIPS + requireNonNull(secureVipAddress, "secureVipAddress"),
                               requireNonNull(regions, "regions"));
    }

    /**
     * Retrieves the registry information whose application name is the specified {@code appName}
     * and instance ID is the specified {@code instanceId} from the Eureka.
     */
    @CheckReturnValue
    public HttpResponse getInstance(String appName, String instanceId) {
        return sendGetRequest(APPS + requireNonNull(appName, "appName") + '/' +
                              requireNonNull(instanceId, "instanceId"));
    }

    /**
     * Retrieves the registry information whose instance ID is the specified {@code instanceId} from the Eureka.
     */
    @CheckReturnValue
    public HttpResponse getInstance(String instanceId) {
        return sendGetRequest(INSTANCES + requireNonNull(instanceId, "instanceId"));
    }
}
