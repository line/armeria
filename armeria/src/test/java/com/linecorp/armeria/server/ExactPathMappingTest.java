/*
 * Copyright 2015 LINE Corporation
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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import org.junit.Test;

public class ExactPathMappingTest {

    @Test
    public void shouldReturnNullOnMismatch() {
        assertThat(new ExactPathMapping("/find/me").apply("/find/me/not"), is(nullValue()));
    }

    @Test
    public void shouldReturnExactPathOnMatch() {
        assertThat(new ExactPathMapping("/find/me").apply("/find/me"), is("/find/me"));
    }
}
