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

package com.linecorp.armeria.server.resteasy;

import static org.assertj.core.api.Assertions.anyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.assertj.core.api.Condition;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.resteasy.ArmeriaJaxrsClientEngine;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.resteasy.jaxrs.samples.JaxRsApp;
import testing.resteasy.jaxrs.samples.books.Book;

public class BookServiceClientServerTest {

    private static final Logger logger = LoggerFactory.getLogger(BookServiceClientServerTest.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    static ServerExtension restServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder serverBuilder) throws Exception {
            logger.info("Configuring HTTP Server with RESTEasy Service");
            serverBuilder.accessLogger(logger);
            final ResteasyDeployment deployment = new ResteasyDeploymentImpl();
            //deployment.setApplicationClass(JaxRsApp.class.getName());
            deployment.setApplication(new JaxRsApp());
            ResteasyService.builder(deployment).path("/resteasy").build().register(serverBuilder);
        }
    };

    @Test
    void testBooksContext() throws Exception {
        final WebTarget webTarget = newWebTarget();

        final String contextPath = "/resteasy/app/books/context";
        final Response context = webTarget.path(contextPath).request(MediaType.TEXT_PLAIN_TYPE)
                                          .cookie("param1", "1234")
                                          .cookie("other", "xyz")
                                          .get();
        assertThat(context.getStatus()).isEqualTo(Status.OK.getStatusCode());
        assertThat(context.getMediaType()).isNull();
        assertThat(context.hasEntity()).isFalse();
        final Map<String, NewCookie> cookies = context.getCookies();
        assertThat(cookies).containsOnly(
                Maps.immutableEntry("serverCookie", NewCookie.valueOf("serverCookie=123")));
    }

    @Test
    void testBooks() throws Exception {
        final WebTarget webTarget = newWebTarget();

        final String booksPath = "/resteasy/app/books/";
        final Response getBooks = webTarget.path(booksPath)
                                           .queryParam("author", "John%20Doe")
                                           .queryParam("title", "Java")
                                           .request(MediaType.APPLICATION_JSON_TYPE).get();
        assertThat(getBooks.getStatus()).isEqualTo(Status.OK.getStatusCode());
        checkMediaType(getBooks.getMediaType(), MediaType.APPLICATION_JSON_TYPE);
        assertThat(getBooks.hasEntity()).isTrue();
        final Condition<Response> nonEmpty =
                new Condition<>(r -> r.getLength() > 0, "NonEmptyContent");
        final Condition<Response> noContentLength =
                new Condition<>(r -> r.getLength() < 0, "NoContentLength");
        assertThat(getBooks).is(anyOf(nonEmpty, noContentLength)); //content-length may not be defined
        final String getBooksEntry = getBooks.readEntity(String.class);
        assertThatThrownBy(getBooks::hasEntity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RESTEASY003765: Response is closed.");
        assertThat(getBooksEntry).contains("John Doe");
        assertThat(getBooksEntry).contains("Java");
        final Book[] getBooksEntryArray = JSON.readValue(getBooksEntry, Book[].class);
        assertThat(getBooksEntryArray).hasSize(3);

        final Response getBooks2 = webTarget.path(booksPath)
                                            .queryParam("author", "John%20Doe")
                                            .queryParam("title", "Java")
                                            .request(MediaType.APPLICATION_JSON_TYPE).get();
        assertThat(getBooks2.getStatus()).isEqualTo(Status.OK.getStatusCode());
        checkMediaType(getBooks2.getMediaType(), MediaType.APPLICATION_JSON_TYPE);
        assertThat(getBooks2.hasEntity()).isTrue();
        assertThat(getBooks2).is(anyOf(nonEmpty, noContentLength)); //content-length may not be defined
        final Book[] getBooksEntryArray2 = getBooks2.readEntity(Book[].class);
        assertThat(getBooksEntryArray2).hasSize(3);
        assertThat(getBooksEntryArray2[0]).isInstanceOf(Book.class);
        assertThat(getBooksEntryArray2).containsExactlyInAnyOrder(getBooksEntryArray);
        assertThatThrownBy(getBooks2::hasEntity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RESTEASY003765: Response is closed.");
        final String getBooksEntry2 = JSON.writeValueAsString(getBooksEntryArray2);
        assertThat(getBooksEntry2).isEqualTo(getBooksEntry);

        final Response getAllBooks = webTarget.path(booksPath)
                                              .request(MediaType.APPLICATION_JSON_TYPE).get();
        assertThat(getAllBooks.getStatus()).isEqualTo(Status.OK.getStatusCode());
        checkMediaType(getAllBooks.getMediaType(), MediaType.APPLICATION_JSON_TYPE);
        assertThat(getAllBooks.hasEntity()).isTrue();
        final Book[] getAllBooksEntryArray = getAllBooks.readEntity(Book[].class);
        assertThatThrownBy(getAllBooks::hasEntity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RESTEASY003765: Response is closed.");
        assertThat(getAllBooksEntryArray).hasSize(4);
        assertThat(getAllBooksEntryArray[0]).isInstanceOf(Book.class);
        assertThat(getAllBooksEntryArray).contains(getBooksEntryArray2);
        final String getAllBooksEntry = JSON.writeValueAsString(getBooksEntryArray2);

        final String getBookPath = "/resteasy/app/books/978-3-16-148410-0";
        final Response getBook = webTarget.path(getBookPath)
                                          .request(MediaType.APPLICATION_JSON_TYPE).get();
        assertThat(getBook.getStatus()).isEqualTo(Status.OK.getStatusCode());
        checkMediaType(getBook.getMediaType(), MediaType.APPLICATION_JSON_TYPE);
        assertThat(getBook.hasEntity()).isTrue();
        final Book bookGetBook = getBook.readEntity(Book.class);
        assertThatThrownBy(getBook::hasEntity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RESTEASY003765: Response is closed.");
        assertThat(bookGetBook.getTitle()).isEqualTo("Java Fun");
        assertThat(bookGetBook.getAuthor()).isEqualTo("John Doe");
        assertThat(bookGetBook.getPrice()).isEqualTo(10f);

        final Response post1 = webTarget.path(booksPath)
                                        .request()
                                        .cookie("issue", "1234")
                                        .cookie("param1", "xyz")
                                        .post(Entity.entity(
                                                "isbn=1-9999-9999-9&author=Jane%20Austen&" +
                                                "title=Emma&price=15.50",
                                                MediaType.APPLICATION_FORM_URLENCODED_TYPE));
        assertThat(post1.getStatus()).isEqualTo(Status.NO_CONTENT.getStatusCode());

        final Response post2 = webTarget.path(booksPath)
                                        .request()
                                        .cookie("issue", "1234")
                                        .post(Entity.json(Book.ofHtmlForm(
                                                "isbn=1-9999-9999-8&author=Jane%20Austen&" +
                                                "title=Sense%20and%20Sensibility&price=15.60")));
        assertThat(post2.getStatus()).isEqualTo(Status.NO_CONTENT.getStatusCode());

        final String postBookPath = "/resteasy/app/books/1-9999-9999-9";
        final Response getPost = webTarget.path(postBookPath)
                                          .request(MediaType.APPLICATION_JSON_TYPE).get();
        assertThat(getPost.getStatus()).isEqualTo(Status.OK.getStatusCode());
        checkMediaType(getPost.getMediaType(), MediaType.APPLICATION_JSON_TYPE);
        assertThat(getPost.hasEntity()).isTrue();
        final Book bookGetPost = getPost.readEntity(Book.class);
        assertThatThrownBy(getPost::hasEntity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RESTEASY003765: Response is closed.");
        assertThat(bookGetPost.getTitle()).isEqualTo("Emma");
        assertThat(bookGetPost.getAuthor()).isEqualTo("Jane Austen");
        assertThat(bookGetPost.getPrice()).isEqualTo(15.50f);

        final Response put = webTarget.path(postBookPath)
                                      .request()
                                      .put(Entity.form(new Form("price", "15.58")));
        assertThat(put.getStatus()).isEqualTo(Status.NO_CONTENT.getStatusCode());

        final Response getPut = webTarget.path(postBookPath)
                                          .request(MediaType.APPLICATION_JSON_TYPE).get();
        assertThat(getPut.getStatus()).isEqualTo(Status.OK.getStatusCode());
        checkMediaType(getPut.getMediaType(), MediaType.APPLICATION_JSON_TYPE);
        assertThat(getPut.hasEntity()).isTrue();
        final Book bookGetPut = getPut.readEntity(Book.class);
        assertThatThrownBy(getPut::hasEntity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RESTEASY003765: Response is closed.");
        assertThat(bookGetPut.getTitle()).isEqualTo("Emma");
        assertThat(bookGetPut.getAuthor()).isEqualTo("Jane Austen");
        assertThat(bookGetPut.getPrice()).isEqualTo(15.58f);

        final Response delete = webTarget.path(postBookPath)
                                         .request()
                                         .delete();
        assertThat(delete.getStatus()).isEqualTo(Status.NO_CONTENT.getStatusCode());

        final Response getDelete = webTarget.path(postBookPath)
                                            .request(MediaType.APPLICATION_JSON_TYPE).get();
        assertThat(getDelete.getStatus()).isEqualTo(Status.NOT_FOUND.getStatusCode());
    }

    private static void checkMediaType(MediaType type, MediaType expected) {
        assertThat(type.getType()).isEqualTo(expected.getType());
        assertThat(type.getSubtype()).isEqualTo(expected.getSubtype());
        final Map<String, String> expectedParameters = expected.getParameters();
        if (!expectedParameters.isEmpty()) {
            assertThat(type.getParameters()).containsAllEntriesOf(expectedParameters);
        }
    }

    private static WebTarget newWebTarget() {
        final LogWriter logWriter = LogWriter.builder()
                                             .logger(logger)
                                             .requestLogLevel(LogLevel.INFO)
                                             .successfulResponseLogLevel(LogLevel.INFO)
                                             .failureResponseLogLevel(LogLevel.WARN)
                                             .build();
        final WebClient httpClient = WebClient.builder()
                                              .decorator(LoggingClient.builder()
                                                                      .logWriter(logWriter)
                                                                      .newDecorator())
                                              .build();
        final Client restClient = ((ResteasyClientBuilder) ClientBuilder.newBuilder())
                .httpEngine(new ArmeriaJaxrsClientEngine(httpClient))
                .build();
        return restClient.target(restServer.httpUri());
    }
}
