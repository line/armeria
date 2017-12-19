/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import org.junit.Test;

public class VirtualHostBuilderTest {

    @Test
    public void defaultVirtualHost() {
        final VirtualHost h = new VirtualHostBuilder().build();
        assertThat(h.hostnamePattern(), is("*"));
        assertThat(h.defaultHostname(), is(not("*")));
    }

    @Test
    public void defaultVirtualHostWithExplicitAsterisk() {
        final VirtualHost h = new VirtualHostBuilder("*").build();
        assertThat(h.hostnamePattern(), is("*"));
        assertThat(h.defaultHostname(), is(not("*")));
    }

    @Test
    public void defaultVirtualHostWithExplicitAsterisk2() {
        final VirtualHost h = new VirtualHostBuilder("foo", "*").build();
        assertThat(h.hostnamePattern(), is("*"));
        assertThat(h.defaultHostname(), is("foo"));
    }

    @Test
    public void virtualHostWithoutPattern() {
        final VirtualHost h = new VirtualHostBuilder("foo.com", "foo.com").build();
        assertThat(h.hostnamePattern(), is("foo.com"));
        assertThat(h.defaultHostname(), is("foo.com"));
    }

    @Test
    public void virtualHostWithPattern() {
        final VirtualHost h = new VirtualHostBuilder("bar.foo.com", "*.foo.com").build();
        assertThat(h.hostnamePattern(), is("*.foo.com"));
        assertThat(h.defaultHostname(), is("bar.foo.com"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void virtualHostWithMismatch() {
        new VirtualHostBuilder("bar.com", "foo.com").build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void virtualHostWithMismatch2() {
        new VirtualHostBuilder("bar.com", "*.foo.com").build();
    }
}
