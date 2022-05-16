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

package com.linecorp.armeria.internal.testing;

import org.assertj.core.configuration.Configuration;
import org.assertj.core.configuration.PreferredAssumptionException;

public final class AssertjConfiguration extends Configuration {
    @Override
    public PreferredAssumptionException preferredAssumptionException() {
        // A workaround for 'org.testng.SkipException: assumption was not met due to'
        // See: https://github.com/assertj/assertj-core/issues/2267
        // We assume that JUnit5 is the primary tool for testing Armeria modules.
        // - TestNG is only used for Reactive Streams TCK.
        // - JUnit4, eventually, will be migrated to JUnit5 except for Spring
        //   so that Spring Boot 1 and 2 share the same test suites.
        return PreferredAssumptionException.JUNIT5;
    }
}
