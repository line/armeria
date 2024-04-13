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

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Version;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;

public enum AppInfoService implements HttpService {

    INSTANCE;

    @Nullable
    private AppInfo appInfo;

    public static AppInfoService of() {
        return INSTANCE;
    }

    void setAppInfo(@Nullable AppInfo appInfo) {
        this.appInfo = appInfo;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Version versionInfo = Version.get("armeria", Server.class.getClassLoader());

        final ImmutableMap<String, ImmutableMap<String, String>> appInfoMap;
        if (appInfo != null) {
            appInfoMap = ImmutableMap.of(
                    "app", ImmutableMap.of(
                            "version", appInfo.version,
                            "name", appInfo.name,
                            "description", appInfo.description
                    )
            );
        } else {
            appInfoMap = ImmutableMap.of();
        }

        final ImmutableMap<String, ImmutableMap<String, String>> armeriaInfoMap = ImmutableMap.of(
                "armeria", ImmutableMap.of(
                        "version", versionInfo.artifactVersion(),
                        "commit", versionInfo.longCommitHash(),
                        "repositoryStatus", versionInfo.repositoryStatus()
                )
        );

        final ImmutableMap<Object, Object> infoMap = ImmutableMap.builder()
                                                                 .putAll(appInfoMap)
                                                                 .putAll(armeriaInfoMap)
                                                                 .build();
        return HttpResponse.ofJson(infoMap);
    }
}
