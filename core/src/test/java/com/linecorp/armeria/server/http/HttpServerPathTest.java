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

package com.linecorp.armeria.server.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.Assert.assertThat;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.junit.Test;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.NetUtil;

public class HttpServerPathTest extends AbstractServerTest {

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        sb.port(0, SessionProtocol.HTTP);
        sb.serviceAt("/service/foo", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                    throws Exception {
                res.respond(HttpStatus.OK);
            }
        });

        sb.service(PathMapping.ofPrefix("/"), new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                    throws Exception {
                res.respond(HttpStatus.OK);
            }
        });
    }

    // last space is workaround..
    private static final String HTTP11_PROTOCOL = "HTTP/1.1 ";
    
    private static final Map<HttpStatus, String> TEST_URLS = new HashMap<>();
    static	{
    	TEST_URLS.put(HttpStatus.OK, "/");
    	TEST_URLS.put(HttpStatus.OK, "/service//foo");
    	TEST_URLS.put(HttpStatus.OK, "/service/foo..bar");

    	TEST_URLS.put(HttpStatus.NOT_FOUND, "/..service/foobar");
    	TEST_URLS.put(HttpStatus.NOT_FOUND, "/service../foobar");
    	TEST_URLS.put(HttpStatus.NOT_FOUND, "/service/foobar..");

    	/**
    	 * TODO(krisjey) should move validation code to ArmeriaHttpUtil
    	 * Http1RequestDecoder, Http2RequestDecoder
    	 * expected 404. but 500 
    	 * @see com.linecorp.armeria.common.http.HttpHeaders.toArmeria(HttpMessage in) 
    	 */
    	TEST_URLS.put(HttpStatus.INTERNAL_SERVER_ERROR, "/service/foo>bar");

    	/**
    	 * TODO(krisjey) should move validation code to ArmeriaHttpUtil
    	 * Http1RequestDecoder, Http2RequestDecoder
    	 * expected 404. but 500 
    	 * @see com.linecorp.armeria.common.http.HttpHeaders.toArmeria(HttpMessage in) 
    	 */
    	TEST_URLS.put(HttpStatus.INTERNAL_SERVER_ERROR, "/service/foo<bar");

    	TEST_URLS.put(HttpStatus.NOT_FOUND, "/service/foo*bar");
    	TEST_URLS.put(HttpStatus.NOT_FOUND, "/service/foo|bar");
    	TEST_URLS.put(HttpStatus.NOT_FOUND, "/service/foo\bar");
    	TEST_URLS.put(HttpStatus.NOT_FOUND, "/service:name/hello");

    	TEST_URLS.put(HttpStatus.BAD_REQUEST, "");
    	TEST_URLS.put(HttpStatus.BAD_REQUEST, ".");
    	TEST_URLS.put(HttpStatus.BAD_REQUEST, "..");
    	TEST_URLS.put(HttpStatus.BAD_REQUEST, "something");
    	
    	TEST_URLS.put(HttpStatus.OK, "/service/foo:bar");
    }

    @Test(timeout = 10000)
    public void testDoubleSlashPath() throws Exception {
        try (Socket s = new Socket(NetUtil.LOCALHOST, httpPort())) {
            s.setSoTimeout(10000);
            s.getOutputStream().write("GET /service//foo HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertThat(new String(ByteStreams.toByteArray(s.getInputStream()), StandardCharsets.US_ASCII))
                    .startsWith(HTTP11_PROTOCOL + HttpStatus.OK.toString());
        }
    }

	@Test
	public void testPathOfUrl() throws Exception {
		for (Entry<HttpStatus, String> url : TEST_URLS.entrySet()) {
			urlPathAssertion(url.getValue(), url.getKey());
		}
	}

	private void urlPathAssertion(String path, HttpStatus expected) throws Exception {
		String requestString = "GET " + path + " HTTP/1.0\r\n\r\n";

		try (Socket s = new Socket(NetUtil.LOCALHOST, httpPort())) {
			s.setSoTimeout(10000);
			s.getOutputStream().write(requestString.getBytes(StandardCharsets.US_ASCII));
			assertThat(path, new String(ByteStreams.toByteArray(s.getInputStream()), StandardCharsets.US_ASCII), 
					startsWith(HTTP11_PROTOCOL + expected.toString()));
		}
	}
}
