package com.linecorp.armeria.client.http.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.http.HttpClient;

public class ArmeriaRetrofitTest {
    @Test
    public void convertToSerializationFormatRemovedUri() throws Exception {
        URI uri = Clients.newClient(URI.create("none+http://example.com:8080/a/b/c"), HttpClient.class)
                         .uri();
        assertThat(ArmeriaRetrofit.convertToSerializationFormatRemovedUri(uri))
                .isEqualTo("http://example.com:8080/a/b/c");
    }

    @Test
    public void convertToSerializationFormatRemovedUri_noSerializationFormat() throws Exception {
        URI uri = URI.create("http://example.com:8080/");
        assertThat(ArmeriaRetrofit.convertToSerializationFormatRemovedUri(uri))
                .isEqualTo("http://example.com:8080/");
    }

}
