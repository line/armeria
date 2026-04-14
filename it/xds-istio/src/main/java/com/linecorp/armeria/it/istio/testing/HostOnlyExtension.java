/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.it.istio.testing;

import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.testing.junit5.common.AbstractAllOrEachExtension;

/**
 * Base class for JUnit extensions that must only run when executing on the host,
 * not inside a Kubernetes pod. Subclass this instead of {@link AbstractAllOrEachExtension}
 * when the extension manages host-side infrastructure (cluster lifecycle, container
 * management, etc.) that must not execute inside the in-cluster test job.
 *
 * <p>Implement {@link #setUp} and optionally {@link #tearDown}. Both are no-ops
 * when {@code RUNNING_IN_K8S_POD=true}.
 */
abstract class HostOnlyExtension extends AbstractAllOrEachExtension {

    static final String RUNNING_IN_K8S_POD_ENV = "RUNNING_IN_K8S_POD";

    static boolean isRunningInPod() {
        return Boolean.parseBoolean(System.getenv(RUNNING_IN_K8S_POD_ENV));
    }

    static boolean notRunningInPod() {
        return !isRunningInPod();
    }

    @Override
    protected final void before(ExtensionContext context) throws Exception {
        if (notRunningInPod()) {
            setUp(context);
        }
    }

    @Override
    protected final void after(ExtensionContext context) throws Exception {
        if (notRunningInPod()) {
            tearDown(context);
        }
    }

    abstract void setUp(ExtensionContext context) throws Exception;

    void tearDown(ExtensionContext context) throws Exception {}
}
