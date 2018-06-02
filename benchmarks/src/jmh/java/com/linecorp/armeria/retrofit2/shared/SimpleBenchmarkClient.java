package com.linecorp.armeria.retrofit2.shared;

import java.util.concurrent.CompletableFuture;

import retrofit2.http.GET;

public interface SimpleBenchmarkClient {
    @GET("/empty")
    CompletableFuture<String> empty();
}
