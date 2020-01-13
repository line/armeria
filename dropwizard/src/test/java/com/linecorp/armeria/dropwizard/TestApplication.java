/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.dropwizard;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

public class TestApplication extends Application<TestConfiguration> {
    @Override
    public void initialize(Bootstrap<TestConfiguration> bootstrap) {
        final ArmeriaBundle<TestConfiguration> bundle =
                new ArmeriaBundle<TestConfiguration>() {
                    @Override
                    public void configure(ServerBuilder builder) {
                        builder.service("/armeria", (ctx, res) -> HttpResponse.of("Hello, Armeria!"));
                    }
                };
        bootstrap.addBundle(bundle);
    }

    @Override
    public void run(TestConfiguration configuration, Environment environment) throws Exception {}
}
