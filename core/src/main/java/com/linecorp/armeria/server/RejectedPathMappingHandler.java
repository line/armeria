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
 * A handler that is invoked when a {@link ServerBuilder} rejects to bind a {@link Service} at
 * a certain {@link PathMapping}. For example, the following code will trigger this handler:
 *
 * <pre>{@code
 * ServerBuilder sb = new ServerBuilder();
 * sb.service("/hello", serviceA);
 * sb.service("/hello", serviceB); // Tried to bind at the same path mapping again.
 * sb.build();
 * }</pre>
 *
 * @see ServerBuilder#rejectedPathMappingHandler(RejectedPathMappingHandler)
 */
@FunctionalInterface
public interface RejectedPathMappingHandler {

    /**
     * A {@link RejectedPathMappingHandler} that logs a warning message for a problematic {@link PathMapping}.
     */
    RejectedPathMappingHandler WARN = (virtualHost, mapping, existingMapping) -> {
        final Logger logger = LoggerFactory.getLogger(RejectedPathMappingHandler.class);
        final String a = mapping.toString();
        final String b = existingMapping.toString();
        final String hostnamePattern = virtualHost.hostnamePattern();
        // TODO(trustin): Deduplicate message generation in 'WARN' and 'FAIL'
        //                once we can have private methods in an interface.
        if (a.equals(b)) {
            logger.warn("Virtual host '{}' has a duplicate path mapping: {}",
                        hostnamePattern, a);
        } else {
            logger.warn("Virtual host '{}' has path mappings with a conflict: {} vs. {}",
                        hostnamePattern, a, b);
        }
    };

    /**
     * A {@link RejectedPathMappingHandler} that raises an {@link IllegalStateException} for a problematic
     * {@link PathMapping}.
     */
    RejectedPathMappingHandler FAIL = (virtualHost, mapping, existingMapping) -> {
        final String a = mapping.toString();
        final String b = existingMapping.toString();
        final String hostnamePattern = virtualHost.hostnamePattern();
        if (a.equals(b)) {
            throw new IllegalStateException(
                    "Virtual host '" + hostnamePattern + "' has a duplicate path mapping: " + a);
        } else {
            throw new IllegalStateException(
                    "Virtual host '" + hostnamePattern + "' has path mappings with a conflict: " +
                    a + " vs. " + b);
        }
    };

    /**
     * Invoked when a user attempts to bind a {@link Service} at the {@link PathMapping} that conflicts with
     * an existing {@link PathMapping}.
     *
     * @param virtualHost     the {@link VirtualHost} where the {@link PathMapping} belong to
     * @param mapping         the {@link PathMapping} being added
     * @param existingMapping the existing {@link PathMapping}
     */
    void handleDuplicatePathMapping(VirtualHost virtualHost,
                                    PathMapping mapping, PathMapping existingMapping) throws Exception;
}
