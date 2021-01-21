/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.jaxrs.samples;

import java.util.Optional;

import javax.ws.rs.CookieParam;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/calc")
public class CalculatorService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @GET
    @Path("/context")
    @Produces(MediaType.TEXT_PLAIN)
    public Response context(@Context Request request,
                            @Context UriInfo uriInfo,
                            @Context HttpHeaders headers,
                            @Context SecurityContext securityContext,
                            @Context Application application,
                            @CookieParam("param1") Optional<Integer> param1,
                            @CookieParam("param2") Optional<String> param2,
                            @CookieParam("param3") @DefaultValue("bar") String param3,
                            @CookieParam("other") String other) {
        if (logger.isInfoEnabled()) {
            logger.info("Request: " + request.getMethod()); // org.jboss.resteasy.specimpl.RequestImpl
            logger.info("UriInfo: " + uriInfo.getRequestUri()); // ResteasyUriInfo
            logger.info("Headers: " + headers.getRequestHeaders()); // ResteasyHttpHeaders
            logger.info("Cookies: " + headers.getCookies());
            logger.info("SecurityContext: " + securityContext); // SecurityContextImpl
            logger.info("Application: [" + application + "], " + application.getProperties()); // JaxRsApp
            logger.info("param1: " + param1);
            logger.info("param2: " + param2);
            logger.info("param3: " + param3);
            logger.info("other: " + other);
        }

        final NewCookie setCookie = new NewCookie("serverCookie", "123");
        return Response.ok().cookie(setCookie).build();
    }

    @GET
    @Path("/sum")
    @Produces(MediaType.TEXT_PLAIN)
    public int add(@QueryParam("x") int x, @QueryParam("y") int y) {
        return x + y;
    }

    @GET
    @Path("/div")
    @Produces(MediaType.TEXT_PLAIN)
    public int div(@QueryParam("x") int x, @QueryParam("y") int y) {
        return x / y;
    }
}
