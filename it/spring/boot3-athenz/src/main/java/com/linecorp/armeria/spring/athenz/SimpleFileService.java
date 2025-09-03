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

import org.springframework.stereotype.Component;

import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.athenz.RequiresAthenzRole;

// Make sure that DependencyInjector.ofReflective() is set up to create TestDecorator
@Decorator(TestDecorator.class)
@Component
public class SimpleFileService {

    @RequiresAthenzRole(resource = "files", action = "obtain")
    @Get("/files/{name}")
    public String files(@Param String name) {
        return String.format("file:%s", name);
    }
}
