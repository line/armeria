/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.manipulating;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.List;

import java.util.function.BiPredicate;
import java.util.function.Function;

import org.junit.Test;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;

import io.netty.util.AsciiString;


public class HttpHeaderManipulatingClientBuilderTest {

    // builder instance for null/empty argument tests only
    private static HttpHeaderManipulatingClientBuilder builder = newBuilder(); 
    
    @Test(expected = NullPointerException.class)
    public void addNullHeader() {
        builder.add(null, "value");
    }

    @Test(expected = NullPointerException.class)
    public void addNullValue() {
        builder.add(HEADER_0, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void addEmptyHeader() {
        builder.add(EMPTY_HEADER, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void addEmptyValue() {
        builder.add(HEADER_0, "");
    }

    @Test(expected = NullPointerException.class)
    public void setNullHeader() {
        builder.set(null, "value");
    }

    @Test(expected = NullPointerException.class)
    public void setNullValue() {
        builder.set(HEADER_0, (String) null);
    }

    @Test(expected = NullPointerException.class)
    public void setNullGenerator() {
        Function<String, String> func = null;
        builder.set(HEADER_0, func);
    }

    @Test(expected = IllegalArgumentException.class)
    public void setEmptyHeader() {
        builder.set(EMPTY_HEADER, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void setEmptyValue() {
        builder.set(HEADER_0, "");
    }


    @Test(expected = NullPointerException.class)
    public void removeNullHeader() {
        builder.remove((AsciiString)null);
    }

    @Test(expected = NullPointerException.class)
    public void removeNullPredicate() {
        BiPredicate<AsciiString, String> predicate = null;
        builder.remove(predicate);
    }

    @Test(expected = IllegalArgumentException.class)
    public void removeEmptyHeader() {
        builder.remove(EMPTY_HEADER);
    }

    @SuppressWarnings("unchecked")
    private static final Client dummyDelegate = mock(Client.class);
    private static final ClientRequestContext dummyContext = mock(ClientRequestContext.class);

    @Test
    public void testAdd() throws Exception {
        HttpRequest request = newRequest();
        Client client = newBuilder()
            .add(HEADER_1, "a1")
            .add(HEADER_0, "a0")
            .buildClient(dummyDelegate);

        client.execute(dummyContext, request);
        HttpHeaders headers = request.headers();
        
        List<String> value0 = headers.getAll(HEADER_0);
        List<String> value1 = headers.getAll(HEADER_1);

        assertThat(value0, is(Arrays.asList(VALUE_0, "a0")));
        assertThat(value1, is(Arrays.asList("a1")));
    }

    @Test
    public void testSet() throws Exception {
        HttpRequest request = newRequest();
        Client client = newBuilder()
                .add(HEADER_0, "a0").add(HEADER_0, "a00").add(HEADER_0, "a000")
                .set(HEADER_0, "s0") // should replace all entries
                .set(HEADER_1, "s1") // should set value of non-existing header
                .set(HEADER_1, value -> value + "!!") // can read old value and change it
                .set(HEADER_2, "s2")
                .set(HEADER_2, value -> "") // should ignore empty value
                .set(HEADER_2, value -> null) // should ignore null value
                .set(HEADER_3, value -> value == null ? "s3" : "BUG!") // should be called with null
                .buildClient(dummyDelegate);

        client.execute(dummyContext, request);
        HttpHeaders headers = request.headers();
        
        List<String> value0 = headers.getAll(HEADER_0);
        List<String> value1 = headers.getAll(HEADER_1);
        List<String> value2 = headers.getAll(HEADER_2);
        List<String> value3 = headers.getAll(HEADER_3);

        assertThat(value0, is(Arrays.asList("s0")));
        assertThat(value1, is(Arrays.asList("s1!!")));
        assertThat(value2, is(Arrays.asList("s2")));
        assertThat(value3, is(Arrays.asList("s3")));
    }

    @Test
    public void testRemove() throws Exception {
        HttpRequest request = newRequest();
        request.headers().add(HEADER_0, "a0")
                         .set(HEADER_1, "v1")
                         .set(X_HEADER_1, "x1")
                         .set(X_HEADER_2, "x2")
                         .set(X_HEADER_3, "x3");
        
        Client client = newBuilder()
                .remove(HEADER_0) // should remove all entries
                .remove((header, value) -> header.toString().startsWith("x-")) // conditional remove
                .add(X_HEADER_3, "X3")
                .buildClient(dummyDelegate);

        client.execute(dummyContext, request);
        HttpHeaders headers = request.headers();

        assertFalse(headers.contains(HEADER_0));
        assertEquals("v1", headers.get(HEADER_1));
        assertFalse(headers.contains(X_HEADER_1));
        assertFalse(headers.contains(X_HEADER_2));
        assertEquals("X3", headers.get(X_HEADER_3));
    }

    @Test
    public void testUpdate() throws Exception {
        HttpRequest request = newRequest();
        request.headers()
               .set(HEADER_1, "v1")
               .set(HEADER_2, "v2")
               .set(HEADER_3, "v3")
               .set(X_HEADER_1, "x1")
               .set(X_HEADER_2, "x2")
               .set(X_HEADER_3, "x3");


        Updater updater = new Updater("!!");
        Client client = newBuilder()
                                .update(updater::update)
                                .buildClient(dummyDelegate);

        client.execute(dummyContext, request);
        HttpHeaders headers = request.headers();

        assertFalse(headers.contains(X_HEADER_1));
        assertFalse(headers.contains(X_HEADER_2));
        assertFalse(headers.contains(X_HEADER_3));
        assertEquals("!!v0v1v2v3x1x2x3", headers.get(X_HEADER_0));
    }

    private static HttpRequest newRequest() {
        final DefaultHttpRequest req = new DefaultHttpRequest(HttpMethod.POST, "/hello");
        req.headers().set(HEADER_0, VALUE_0);
        req.close();
        return req;
    }

    private static HttpHeaderManipulatingClientBuilder newBuilder() {
        return new HttpHeaderManipulatingClientBuilder();
    }

    private static AsciiString EMPTY_HEADER = new AsciiString("");

    private static AsciiString HEADER_0 = new AsciiString("h0");
    private static AsciiString HEADER_1 = new AsciiString("h1");
    private static AsciiString HEADER_2 = new AsciiString("h2");
    private static AsciiString HEADER_3 = new AsciiString("h3");

    private static AsciiString X_HEADER_0 = new AsciiString("x-h0");
    private static AsciiString X_HEADER_1 = new AsciiString("x-h1");
    private static AsciiString X_HEADER_2 = new AsciiString("x-h2");
    private static AsciiString X_HEADER_3 = new AsciiString("x-h3");

    private static String VALUE_0 = "v0";

    private static class Updater {
        private String seed;

        Updater(String seed) {
            this.seed = seed;
        }

        void update(HttpHeaders headers) {
            StringBuilder sb = new StringBuilder(seed);
            headers.forEach(entry -> {
                if (!entry.getKey().startsWith(":")) {
                    sb.append(entry.getValue());
                }
            });
            headers.set(X_HEADER_0, sb.toString());
            headers.remove(X_HEADER_1);
            headers.remove(X_HEADER_2);
            headers.remove(X_HEADER_3);
        }
    }

}
