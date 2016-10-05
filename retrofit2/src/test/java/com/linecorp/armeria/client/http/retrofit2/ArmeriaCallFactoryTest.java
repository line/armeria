/*
 * Copyright (c) 2016 LINE Corporation. All rights reserved.
 * LINE Corporation PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.linecorp.armeria.client.http.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.net.MediaType;
import com.google.common.util.concurrent.ListenableFuture;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import retrofit2.adapter.guava.GuavaCallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.GET;

public class ArmeriaCallFactoryTest {

    public static class Pojo {
        String name;
        int age;

        @JsonCreator
        public Pojo(@JsonProperty("name") String name, @JsonProperty("age") int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof Pojo)) {
                return false;
            }
            Pojo other = (Pojo) o;
            return name.equals(other.name) && age == other.age;
        }

        @Override
        public int hashCode() {
            final int PRIME = 59;
            int result = 1;
            result = result * 31 + (name == null ? 43 : name.hashCode());
            result = result * 31 + age;
            return result;
        }
    }

    interface Service {
        @GET("/pojo")
        ListenableFuture<Pojo> pojo();

        @GET("/pojos")
        ListenableFuture<List<Pojo>> pojos();
    }

    private static final Server server;

    private static int httpPort;

    static {
        final SelfSignedCertificate ssc;
        final ServerBuilder sb = new ServerBuilder()
                .port(0, SessionProtocol.HTTP)
                .serviceAt("/pojo", new AbstractHttpService() {
                    @Override
                    protected void doGet(ServiceRequestContext ctx,
                                         HttpRequest req, HttpResponseWriter res) throws Exception {
                        res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                                    "{\"name\":\"Cony\", \"age\":26}");
                    }
                })
                .serviceAt("/pojos", new AbstractHttpService() {
                    @Override
                    protected void doGet(ServiceRequestContext ctx,
                                         HttpRequest req, HttpResponseWriter res) throws Exception {
                        res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                                    "[{\"name\":\"Cony\", \"age\":26}," +
                                    "{\"name\":\"Leonard\", \"age\":21}]");
                    }
                });
        server = sb.build();
    }

    private Service service;

    @BeforeClass
    public static void init() throws Exception {
        server.start().get();

        httpPort = server.activePorts().values().stream()
                         .filter(p -> p.protocol() == SessionProtocol.HTTP).findAny().get().localAddress()
                         .getPort();
    }

    @AfterClass
    public static void destroy() throws Exception {
        server.stop();
    }

    @Before
    public void setUp() {
        service = ArmeriaRetrofit.builder(Clients.newClient(ClientFactory.DEFAULT,
                                                            "none+http://127.0.0.1:" + httpPort,
                                                            HttpClient.class))
                                 .addConverterFactory(JacksonConverterFactory.create())
                                 .addCallAdapterFactory(GuavaCallAdapterFactory.create())
                                 .build()
                                 .create(Service.class);
    }

    @Test
    public void pojo() throws Exception {
        Pojo pojo = service.pojo().get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 35));
    }

    @Test
    public void pojos() throws Exception {
        List<Pojo> pojos = service.pojos().get();
        assertThat(pojos.get(0)).isEqualTo(new Pojo("Cony", 35));
        assertThat(pojos.get(1)).isEqualTo(new Pojo("Leonard", 21));
    }
}
