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
package com.linecorp.armeria.internal.common.util;

import com.linecorp.armeria.common.util.Unwrappable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.annotation.AnnotatedService;

public final class ServiceNamingUtil {

    public static final String GRPC_SERVICE_NAME = "com.linecorp.armeria.internal.common.grpc.GrpcLogUtil";

    public static String fullTypeHttpServiceName(HttpService service) {
        Unwrappable unwrappable = service;
        for (;;) {
            final Unwrappable delegate = unwrappable.unwrap();
            if (delegate != unwrappable) {
                unwrappable = delegate;
                continue;
            }
            if (delegate instanceof AnnotatedService) {
                final AnnotatedService annotatedService = (AnnotatedService) delegate;
                final String serviceName = annotatedService.name();
                if (serviceName != null) {
                    return serviceName;
                }
                return annotatedService.serviceClass().getName();
            } else {
                return delegate.getClass().getName();
            }
        }
    }

    public static String trimTrailingDollarSigns(String serviceName) {
        int lastIndex = serviceName.length() - 1;
        if (serviceName.charAt(lastIndex) != '$') {
            return serviceName;
        }

        do {
            lastIndex--;
        } while (lastIndex > 0 && serviceName.charAt(lastIndex) == '$');
        return serviceName.substring(0, lastIndex + 1);
    }

    private ServiceNamingUtil() {}
}
