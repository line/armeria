/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server;

import java.util.concurrent.ConcurrentHashMap;

enum SimpleTypeServiceNaming implements ServiceNaming {

    INSTANCE;

    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    @Override
    public String serviceName(ServiceRequestContext ctx) {
        final String fullTypeName = ServiceNaming.fullTypeName().serviceName(ctx);
        assert fullTypeName != null;

        final String cachedValue = cache.get(fullTypeName);
        if (cachedValue != null) {
            return cachedValue;
        }

        return cache.computeIfAbsent(fullTypeName, key -> {
            String simpleTypeName = "";
            final int packageIndex = key.lastIndexOf('.');
            if (packageIndex >= 0) {
                simpleTypeName = key.substring(packageIndex + 1);
            }


            // Trim a trailing dollar sign from simpleTypeName
            int lastCharIndex = simpleTypeName.length() - 1;
            while (simpleTypeName.charAt(lastCharIndex) == '$') {
                lastCharIndex--;
            }
            return simpleTypeName.substring(0, lastCharIndex + 1);
        });
    }
}
