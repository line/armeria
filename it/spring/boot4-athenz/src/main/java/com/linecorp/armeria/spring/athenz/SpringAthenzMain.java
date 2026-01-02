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

package com.linecorp.armeria.spring.athenz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

/**
 * An example of a configuration which provides beans for customizing the server and client.
 */
@SpringBootApplication
public class SpringAthenzMain {

    /**
     * A user can configure a {@link Server} by providing an {@link ArmeriaServerConfigurator} bean.
     */
    @Bean
    public ArmeriaServerConfigurator armeriaServerConfigurator(SimpleFileService service) {
        // Customize the server using the given ServerBuilder. For example:
        return builder -> builder.annotatedService(service);
    }

    public static void main(String[] args) {
        SpringApplication.run(SpringAthenzMain.class, args);
    }
}
