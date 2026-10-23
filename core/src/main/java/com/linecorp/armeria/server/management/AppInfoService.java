/*
 * Copyright 2024 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.server.management;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Version;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * An {@link HttpService} that provides additional information about configured server.
 * This functionality is can be used by binding {@link ManagementService}
 * It provides information about not only deployed application information, which can be specified by user,
 * but also one about Armeria artifact itself.
 */
public enum AppInfoService implements HttpService {

    INSTANCE;

    private static final Version armeriaVersionInfo = Version.get("armeria", Server.class.getClassLoader());

    @Nullable
    private AggregatedHttpResponse infoAggregatedResponse;

    void setAppInfo(@Nullable AppInfo appInfo) {
        final byte[] data;

        try {
            if (appInfo == null) {
                data = JacksonUtil.writeValueAsBytes(buildArmeriaInfoMap());
            } else {
                data = JacksonUtil.writeValueAsBytes(buildInfoMap(appInfo));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.toString(), e);
        }

        infoAggregatedResponse = AggregatedHttpResponse.of(HttpStatus.OK, MediaType.JSON, data);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        assert infoAggregatedResponse != null;
        return infoAggregatedResponse.toHttpResponse();
    }

    private static ImmutableMap<Object, Object> buildInfoMap(AppInfo appInfo) {
        requireNonNull(appInfo, "appInfo");
        return ImmutableMap.builder()
                    .putAll(buildArmeriaInfoMap())
                    .putAll(buildAppInfoMap(appInfo))
                    .build();
    }

    private static ImmutableMap<String, ImmutableMap<String, String>> buildArmeriaInfoMap() {
        return ImmutableMap.of(
                "armeria", ImmutableMap.of(
                        "version", armeriaVersionInfo.artifactVersion(),
                        "commit", armeriaVersionInfo.longCommitHash(),
                        "repositoryStatus", armeriaVersionInfo.repositoryStatus()
                )
        );
    }

    private static ImmutableMap<String, ImmutableMap<String, String>> buildAppInfoMap(AppInfo appInfo) {
        requireNonNull(appInfo, "appInfo");
        return ImmutableMap.of(
                "app", ImmutableMap.of(
                        "version", appInfo.version,
                        "name", appInfo.name,
                        "description", appInfo.description
                )
        );
    }
}
