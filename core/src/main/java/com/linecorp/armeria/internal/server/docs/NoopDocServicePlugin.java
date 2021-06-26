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
 * under the License.
 */

package com.linecorp.armeria.internal.server.docs;

import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.ServiceSpecification;

/**
 * A {@link DocServicePlugin} implementation that generate empty {@link ServiceSpecification}.
 */
public final class NoopDocServicePlugin implements DocServicePlugin {

    private static final NoopDocServicePlugin INSTANCE = new NoopDocServicePlugin();

    /**
     * Returns the singleton instance.
     */
    public static NoopDocServicePlugin get() {
        return INSTANCE;
    }

    private NoopDocServicePlugin() { }

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public Set<Class<? extends Service<?, ?>>> supportedServiceTypes() {
        return ImmutableSet.of();
    }

    @Override
    public ServiceSpecification generateSpecification(Set<ServiceConfig> serviceConfigs,
                                                      DocServiceFilter filter) {
        return ServiceSpecification.generate(ImmutableList.of(), typeSignature -> null /* ignored */);
    }
}
