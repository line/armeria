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

/**
 * A naming rule that will be applied to {@link RequestOnlyLog#serviceName()}.
 */
public interface ServiceNamingRule {

    /**
     * A default service naming rule is an identity function.
     */
    static ServiceNamingRule ofDefault() {
        return serviceName -> serviceName;
    }

    /**
     * Returns a new service name whose pre-defined naming rule is applied.
     */
    String apply(String serviceName);
}
