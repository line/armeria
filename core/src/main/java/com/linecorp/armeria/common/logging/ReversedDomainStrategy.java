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
package com.linecorp.armeria.common.logging;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.linecorp.armeria.server.VirtualHost;

/**
 * the strategy class for logger name.
 */
public class ReversedDomainStrategy implements LoggerNameStrategy {

    private final String loggerNamePrefix;

    /**
     * reversed domain name strategy.
     * @param loggerNamePrefix logger name prefix
     */
    public ReversedDomainStrategy(String loggerNamePrefix) {
        this.loggerNamePrefix = loggerNamePrefix;
    }

    /**
     * return the name of Logger.
     */
    public String name(VirtualHost virtualHost) {
        return loggerNamePrefix + "." + reverseName(
                virtualHost.hostnamePattern()
        );
    }

    /**
     * return the result of converting virtual host pattern to reversed domain name.
     * e.g) *.example.com - com.example
     * e.g) *.linecorp.com - com.linecorp
     */
    public static String reverseName(String hostPattern) {
        final List<String> elements = Arrays.asList(hostPattern.split("\\."));
        final StringBuilder name = new StringBuilder();
        Collections.reverse(elements);
        for (final String element : elements) {
            if ("*".equals(element)) {
                continue;
            }
            name.append(element);
            name.append(".");
        }
        return name.length() > 0 ?
                name.substring(0, name.length() - 1) : name.toString();
    }
}
