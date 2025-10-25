/*
 * Copyright 2024 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal.nacos;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;

/**
 * A Nacos client that is responsible for
 * <a href="https://nacos.io/en-us/docs/v2/guide/user/open-api.html">Nacos Open-Api - Register instance</a>.
 */
final class RegisterInstanceClient {

    private final WebClient webClient;
    private final String instanceApiPath;
    private final String serviceName;

    @Nullable
    private final String namespaceId;

    @Nullable
    private final String groupName;

    @Nullable
    private final String clusterName;

    @Nullable
    private final String app;

    RegisterInstanceClient(WebClient webClient, String nacosApiVersion, String serviceName,
                           @Nullable String namespaceId, @Nullable String groupName,
                           @Nullable String clusterName, @Nullable String app) {
        this.webClient = webClient;
        instanceApiPath = new StringBuilder("/").append(nacosApiVersion).append("/ns/instance").toString();

        this.serviceName = requireNonNull(serviceName, "serviceName");
        this.namespaceId = namespaceId;
        this.groupName = groupName;
        this.clusterName = clusterName;
        this.app = app;
    }

    static RegisterInstanceClient of(WebClient webClient, String nacosApiVersion, String serviceName,
                                     @Nullable String namespaceId, @Nullable String groupName,
                                     @Nullable String clusterName, @Nullable String app) {
        return new RegisterInstanceClient(webClient, nacosApiVersion, serviceName, namespaceId, groupName,
                                          clusterName, app);
    }

    /**
     * Registers a service into the Nacos.
     */
    HttpResponse register(String ip, int port, int weight) {
        final QueryParams params = NacosClientUtil.queryParams(serviceName, namespaceId, groupName, clusterName,
                                                               null, app, requireNonNull(ip, "ip"), port,
                                                               weight);

        return webClient.prepare().post(instanceApiPath).content(MediaType.FORM_DATA, params.toQueryString())
                        .execute();
    }

    /**
     * De-registers a service from the Nacos.
     */
    HttpResponse deregister(String ip, int port, int weight) {
        final QueryParams params = NacosClientUtil.queryParams(serviceName, namespaceId, groupName, clusterName,
                                                               null, app, requireNonNull(ip, "ip"), port,
                                                               weight);

        return webClient.prepare().delete(instanceApiPath).content(MediaType.FORM_DATA, params.toQueryString())
                        .execute();
    }
}
