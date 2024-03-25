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

package com.linecorp.armeria;

import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;

public class NoJavaDoc {
    @Post("/a")
    public void a(@Param("x") String x,
                  @Param("y") String y) {
        System.out.println(x + y);
    }

    @Put("/b")
    public void b(@Param("x") String x,
                  @Param("y") String y) {
        System.out.println(x + y);
    }

    @Delete("/c/{x}/{y}")
    public void c(@Param("x") String x,
                  @Param("y") String y) {
        System.out.println(x + y);
    }

    @Get("/d/{x}/{y}")
    public void d(@Param("x") String x,
                  @Param("y") String y) {
        System.out.println(x + y);
    }
}
