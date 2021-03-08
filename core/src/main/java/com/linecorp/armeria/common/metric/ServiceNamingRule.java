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
package com.linecorp.armeria.common.metric;

import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * A naming rule that will be applied to {@link RequestOnlyLog#serviceName()}.
 */
@FunctionalInterface
public interface ServiceNamingRule {

    /**
     * Converts the specified {@linkplain RequestOnlyLog#serviceName() serviceName}
     * into another service name which is used as a meter tag or distributed trace's span name.
     *
     * <p>A naming rule can be set by {@link ServerBuilder#serviceNaming(ServiceNamingRule)}.
     * One of pre-defined naming rules is able to be used as follows.
     * <pre>{@code
     * Server server = Server
     *   .builder()
     *   .service(...)
     *   .serviceNaming(ServiceNamingRule.simpleName)
     *   .build()
     * }</pre>
     *
     * <p>If customizing is needed out of given rules, a lambda expression would be applied as follows.
     * <pre>{@code
     * Server server = Server
     *   .builder()
     *   .service(...)
     *   .serviceNaming(serviceName -> {
     *     return serviceName.substring(serviceName.lastIndexOf('.') + 1);
     *   })
     *   .build()
     * }</pre>
     */
    String convert(String serviceName);

    /**
     * Returns the {@link ServiceNamingRule} which converts into the simple name of given service name.
     *
     * @see Class#getSimpleName()
     */
    static ServiceNamingRule simpleName() {
        return serviceName -> serviceName.substring(serviceName.lastIndexOf('.') + 1);
    }
}
