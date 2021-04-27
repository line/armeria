/*
 *  Copyright 2021 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.linecorp.armeria.internal.common.util.TargetLengthBasedClassNameAbbreviator;

final class LengthBasedServiceNaming implements ServiceNaming {

    static LengthBasedServiceNaming of(int shortenedServiceNameLength) {
        return new LengthBasedServiceNaming(shortenedServiceNameLength);
    }

    private final TargetLengthBasedClassNameAbbreviator abbreviator;
    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    private LengthBasedServiceNaming(int shortenedServiceNameLength) {
        checkArgument(shortenedServiceNameLength >= 0, "value: %s (expected >= 0)", shortenedServiceNameLength);
        abbreviator = new TargetLengthBasedClassNameAbbreviator(shortenedServiceNameLength);
    }

    @Override
    public String serviceName(ServiceRequestContext ctx) {
        final String fullTypeName = ServiceNaming.fullTypeName().serviceName(ctx);
        return abbreviate(fullTypeName);
    }

    private String abbreviate(String serviceName) {
        final String abbreviation = cache.get(serviceName);
        if (abbreviation != null) {
            return abbreviation;
        }
        return cache.computeIfAbsent(serviceName, abbreviator::abbreviate);
    }
}
