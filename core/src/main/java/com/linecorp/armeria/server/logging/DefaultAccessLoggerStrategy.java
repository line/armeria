/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.logging;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.VirtualHost;

/**
 * .
 */
public final class DefaultAccessLoggerStrategy {
    static final String ACCESS = "com.linecorp.armeria.logging.access";
    static final Function<VirtualHost, Logger> instance = (host) -> LoggerFactory.getLogger(
            ACCESS + "." + reverseName(host.hostnamePattern()));

    /**
     * Return the result of converting virtual host pattern to reversed domain name.
     * e.g)
     * <ul>
     *     <li>com.example for *.example.com</li>
     *     <li>com.linecorp for *.linecorp.com</li>
     * </ul>
     */
    static String reverseName(String hostPattern) {
        requireNonNull(hostPattern, "hostPattern");
        final String[] elements = (hostPattern.split("\\."));
        final StringBuilder name = new StringBuilder();
        for (int i = elements.length - 1; i >= 0; i--) {
            final String element = elements[i];
            if (element.isEmpty() || "*".equals(element)) {
                continue;
            }
            name.append(element);
            name.append(".");
        }
        return name.length() > 0 ? name.substring(0, name.length() - 1) : "";
    }

    /**
     * Get the instance of default access logger strategy.
     */
    public static Function<VirtualHost, Logger> get() {
        return instance;
    }

    private DefaultAccessLoggerStrategy() {}
}
