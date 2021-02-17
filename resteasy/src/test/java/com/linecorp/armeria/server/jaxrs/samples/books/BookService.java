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

package com.linecorp.armeria.server.jaxrs.samples.books;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javax.ws.rs.Consumes;
import javax.ws.rs.CookieParam;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/books")
public class BookService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Books books = new Books();

    public void start() {
        if (logger.isInfoEnabled()) {
            logger.info("{} has started", getClass().getSimpleName());
        }

        books.provision(
                new Book("978-3-16-148410-0", "Java Fun", "John Doe", 10f),
                new Book("978-1-56619-909-4", "Java 101", "John Doe", 11f),
                new Book("1-56619-909-3", "Java Expert", "John Doe", 12f),
                new Book("1-4028-9462-7", "Java EE 8", "James Jameson", 13f)
        );
    }

    public void stop() {
        if (logger.isInfoEnabled()) {
            logger.info("{} has stopped", getClass().getSimpleName());
        }
    }

    @GET
    @Path("/context")
    @Produces(MediaType.TEXT_PLAIN)
    public Response context(@Context Request request,
                            @Context UriInfo uriInfo,
                            @Context HttpHeaders headers,
                            @Context SecurityContext securityContext,
                            @Context Application application,
                            @Context com.linecorp.armeria.server.ServiceRequestContext armeriaContext,
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
            logger.info("ServiceRequestContext: " + armeriaContext); // ServiceRequestContext
            logger.info("param1: " + param1);
            logger.info("param2: " + param2);
            logger.info("param3: " + param3);
            logger.info("other: " + other);
        }

        final NewCookie setCookie = new NewCookie("serverCookie", "123");
        return Response.ok().cookie(setCookie).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public void getBooks(
            @QueryParam("author") Optional<String> author,
            @QueryParam("title") Optional<String> title,
            @Suspended AsyncResponse asyncResponse) {
        // Suspended context
        CompletableFuture.supplyAsync(() -> {
            final Collection<Book> found;
            if (author.isPresent()) {
                found = books.getBookBy(author.get(), title.orElse(null));
            } else {
                found = books.getAllBooks();
            }
            return found;
        }).thenApply(asyncResponse::resume).exceptionally(e -> handleAsyncException(e, asyncResponse));
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public void addBookForm(@Context UriInfo uriInfo,
                            @Context HttpHeaders headers,
                            @FormParam("isbn") String isbn,
                            @FormParam("title") String title,
                            @FormParam("author") String author,
                            @FormParam("price") Float price,
                            @CookieParam("issue") Cookie issue,
                            @CookieParam("param1") Optional<String> param1,
                            @CookieParam("param2") @DefaultValue("456") int param2,
                            @Suspended AsyncResponse asyncResponse) {
        // CAUTION: Context parameters have to be handled inside the request context only!
        final Map<String, Cookie> cookies = headers.getCookies();
        if (logger.isInfoEnabled()) {
            logger.info("UriInfo: " + uriInfo.getRequestUri());
            logger.info("Headers: " + headers.getRequestHeaders());
            logger.info("Cookies: " + cookies);
            logger.info("issue: " + issue);
            logger.info("param1: " + param1.orElse("NULL"));
            logger.info("param2: " + param2);
        }
        // Suspended context
        CompletableFuture.supplyAsync(() -> {
            final Book book = new Book(isbn, title, author, price);
            if (!books.addBook(book)) {
                return Response.status(Status.CONFLICT)
                               .entity(String.format("Book [%s] already exist", isbn)).build();
            }
            return Response.noContent().build();
        }).thenApply(asyncResponse::resume).exceptionally(e -> handleAsyncException(e, asyncResponse));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void addBookJson(@Context UriInfo uriInfo,
                            @Context HttpHeaders headers,
                            Book book,
                            @CookieParam("issue") Cookie issue,
                            @CookieParam("param1") Optional<String> param1,
                            @CookieParam("param2") @DefaultValue("456") int param2,
                            @Suspended AsyncResponse asyncResponse) {
        // CAUTION: Context parameters have to be handled inside the request context only!
        final Map<String, Cookie> cookies = headers.getCookies();
        if (logger.isInfoEnabled()) {
            logger.info("UriInfo: " + uriInfo.getRequestUri());
            logger.info("Headers: " + headers.getRequestHeaders());
            logger.info("Cookies: " + cookies);
            logger.info("issue: " + issue);
            logger.info("param1: " + param1.orElse("NULL"));
            logger.info("param2: " + param2);
        }
        // Suspended context
        CompletableFuture.supplyAsync(() -> {
            if (!books.addBook(book)) {
                return Response.status(Status.CONFLICT)
                               .entity(String.format("Book [%s] already exist", book.getIsbn())).build();
            }
            return Response.noContent().build();
        }).thenApply(asyncResponse::resume).exceptionally(e -> handleAsyncException(e, asyncResponse));
    }

    @GET
    @Path("/{isbn}")
    @Produces(MediaType.APPLICATION_JSON)
    public Book getBook(@PathParam("isbn") String isbn) {
        final Book book = books.getBook(isbn);
        if (book == null) {
            throw new NotFoundException(String.format("Book [%s] not found", isbn));
        }
        return book;
    }

    @PUT
    @Path("/{isbn}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public void updateBook(
            @PathParam("isbn") String isbn,
            @FormParam("title") Optional<String> title,
            @FormParam("author") Optional<String> author,
            @FormParam("price") Optional<Float> price,
            @Suspended AsyncResponse asyncResponse) {
        // Suspended context
        CompletableFuture.supplyAsync(() -> {
            final Book book = books.getBook(isbn);
            if (book == null) {
                return Response.status(Status.NOT_FOUND)
                               .entity(String.format("Book [%s] not found", isbn)).build();
            }

            title.ifPresent(book::setTitle);
            author.ifPresent(book::setAuthor);
            price.ifPresent(book::setPrice);
            if (books.updateBook(book) == null) {
                return Response.status(Status.GONE)
                               .entity(String.format("Book [%s] is gone", isbn)).build();
            }
            return Response.noContent().build();
        }).thenApply(asyncResponse::resume).exceptionally(e -> handleAsyncException(e, asyncResponse));
    }

    @DELETE
    @Path("/{isbn}")
    public void deleteBook(
            @PathParam("isbn") String isbn,
            @Suspended AsyncResponse asyncResponse) {
        // Suspended context
        CompletableFuture.supplyAsync(() -> {
            if (books.deleteBook(isbn) == null) {
                return Response.status(Status.NOT_FOUND)
                               .entity(String.format("Book [%s] not found", isbn)).build();
            }
            return Response.noContent().build();
        }).thenApply(asyncResponse::resume).exceptionally(e -> handleAsyncException(e, asyncResponse));
    }

    private static boolean handleAsyncException(Throwable e, AsyncResponse asyncResponse) {
        if (e instanceof WebApplicationException) {
            return asyncResponse.resume(((WebApplicationException) e).getResponse());
        }
        return asyncResponse.resume(e);
    }
}
