/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.docs;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;

/**
 * Generates the {@link ServiceSpecification}s of the supported {@link Service}s.
 */
public interface ServiceSpecificationGenerator {
    /**
     * Returns the {@link Set} of the {@link Service} types supported by this generator.
     */
    Set<Class<? extends Service<?, ?>>> supportedServiceTypes();

    /**
     * Generates a new {@link ServiceSpecification} that provides the information about the supported
     * {@link Service}s.
     *
     * @param serviceConfigs the {@link ServiceConfig}s of the {@link Service}s that are instances of the
     *                       {@link #supportedServiceTypes()}
     * @param exampleHeaders the {@link Map} of the example {@link HttpHeaders} whose key is the
     *                       fully specified name of the relevant services
     */
    ServiceSpecification generate(Set<ServiceConfig> serviceConfigs,
                                  Map<String, List<HttpHeaders>> exampleHeaders);
}
