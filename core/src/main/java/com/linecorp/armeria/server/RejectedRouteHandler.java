/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A handler that is invoked when a {@link ServerBuilder} rejects to bind an {@link HttpService} at
 * a certain {@link Route}. For example, the following code will trigger this handler:
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.service("/hello", serviceA);
 * sb.service("/hello", serviceB); // Tried to bind at the same path again.
 * sb.build();
 * }</pre>
 *
 * @see ServerBuilder#rejectedRouteHandler(RejectedRouteHandler)
 */
@FunctionalInterface
public interface RejectedRouteHandler {

    /**
     * A {@link RejectedRouteHandler} that does nothing for a problematic {@link Route}.
     */
    RejectedRouteHandler DISABLED = (virtualHost, route, existingRoute) -> {
    };

    /**
     * A {@link RejectedRouteHandler} that logs a warning message for a problematic {@link Route}.
     */
    RejectedRouteHandler WARN = (virtualHost, route, existingRoute) -> {
        final Logger logger = LoggerFactory.getLogger(RejectedRouteHandler.class);
        final String a = route.toString();
        final String b = existingRoute.toString();
        final String hostnamePattern = virtualHost.hostnamePattern();
        // TODO(trustin): Deduplicate message generation in 'WARN' and 'FAIL'
        //                once we can have private methods in an interface.
        if (a.equals(b)) {
            logger.warn("Virtual host '{}' has a duplicate route: {}",
                        hostnamePattern, a);
        } else {
            logger.warn("Virtual host '{}' has routes with a conflict: {} vs. {}",
                        hostnamePattern, a, b);
        }
    };

    /**
     * A {@link RejectedRouteHandler} that raises an {@link IllegalStateException} for a problematic
     * {@link Route}.
     */
    RejectedRouteHandler FAIL = (virtualHost, route, existingRoute) -> {
        final String a = route.toString();
        final String b = existingRoute.toString();
        final String hostnamePattern = virtualHost.hostnamePattern();
        if (a.equals(b)) {
            throw new IllegalStateException(
                    "Virtual host '" + hostnamePattern + "' has a duplicate route: " + a);
        } else {
            throw new IllegalStateException(
                    "Virtual host '" + hostnamePattern + "' has routes with a conflict: " +
                    a + " vs. " + b);
        }
    };

    /**
     * Invoked when a user attempts to bind an {@link HttpService} at the {@link Route} that conflicts with
     * an existing {@link Route}.
     * @param virtualHost   the {@link VirtualHost} where the {@link Route} belongs to
     * @param route         the {@link Route} being added
     * @param existingRoute the existing {@link Route}
     */
    void handleDuplicateRoute(VirtualHost virtualHost,
                              Route route, Route existingRoute) throws Exception;
}
