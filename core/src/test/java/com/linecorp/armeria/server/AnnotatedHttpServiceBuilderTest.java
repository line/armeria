/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;

public class AnnotatedHttpServiceBuilderTest {

    @Test
    public void successfulOf() {
        new ServerBuilder().annotatedService(new Object() {
            @Get("/")
            public Object root() {
                return null;
            }
        });
        new ServerBuilder().annotatedService(new Object() {
            @Path("/")
            @Get
            public Object root() {
                return null;
            }
        });
        new ServerBuilder().annotatedService(new Object() {
            @Path("/")
            @Get
            @Post
            public Object root() {
                return null;
            }
        });
        new ServerBuilder().annotatedService(new Object() {
            @Options
            @Get
            @Post("/")
            public Object root() {
                return null;
            }
        });
    }

    @Test
    public void failedOf() {
        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Path("/")
            @Get("/")
            public Object root() {
                return null;
            }
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Post("/")
            @Get("/")
            public Object root() {
                return null;
            }
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get
            public Object root() {
                return null;
            }
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get("")
            public Object root() {
                return null;
            }
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get("  ")
            public Object root() {
                return null;
            }
        })).isInstanceOf(IllegalArgumentException.class);
    }
}
