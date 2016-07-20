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

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

public class ExactPathMappingTest {

    @Test
    public void shouldReturnNullOnMismatch() {
        assertThat(new ExactPathMapping("/find/me").apply("/find/me/not"), is(nullValue()));
    }

    @Test
    public void shouldReturnExactPathOnMatch() {
        assertThat(new ExactPathMapping("/find/me").apply("/find/me"), is("/find/me"));
    }

    @Test
    public void routingVariableMappingTest() {
        Optional<HashMap<String, List<String>>> dict =
                new ExactPathMapping("/hello/:variable").getRoutingVariables("/hello/world");
        assert(!dict.equals(Optional.empty()));
        assertThat(dict.get().size(), is(2));
        assertThat(dict.get().containsKey("variable"), is(true));
        assertThat(dict.get().get("variable").size(), is(1));
        assertThat(dict.get().get("variable").get(0), is("world"));
        assertThat(dict.get().containsKey("*"), is(true));

        dict = new ExactPathMapping("/:variable/world").getRoutingVariables("/hello/world");
        assert(!dict.equals(Optional.empty()));
        assertThat(dict.get().size(), is(2));
        assertThat(dict.get().containsKey("variable"), is(true));
        assertThat(dict.get().get("variable").size(), is(1));
        assertThat(dict.get().get("variable").get(0), is("hello"));
        assertThat(dict.get().containsKey("*"), is(true));

        dict = new ExactPathMapping("/p1/*/:variable/p2/*").getRoutingVariables("/p1/foo/bar/value/p2/tail");
        assert(!dict.equals(Optional.empty()));
        assertThat(dict.get().size(), is(2));
        assertThat(dict.get().containsKey("variable"), is(true));
        assertThat(dict.get().get("variable").size(), is(1));
        assertThat(dict.get().get("variable").get(0), is("value"));
        assertThat(dict.get().containsKey("*"), is(true));
        assertThat(dict.get().get("*").size(), is(2));
        assertThat(dict.get().get("*").get(0), is("foo/bar"));
        assertThat(dict.get().get("*").get(1), is("tail"));

        dict = new ExactPathMapping("/:variable/p1").getRoutingVariables("/hello/world/p1");
        assertThat(dict, is(Optional.empty()));
    }
}
