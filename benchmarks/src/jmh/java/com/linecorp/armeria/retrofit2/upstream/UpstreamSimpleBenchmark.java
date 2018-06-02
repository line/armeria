package com.linecorp.armeria.retrofit2.upstream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import com.linecorp.armeria.retrofit2.shared.SimpleBenchmarkBase;
import com.linecorp.armeria.retrofit2.shared.SimpleBenchmarkClient;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import okhttp3.OkHttpClient;
import okhttp3.internal.platform.Platform;
import retrofit2.Retrofit;
import retrofit2.adapter.java8.Java8CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

@State(Scope.Benchmark)
public class UpstreamSimpleBenchmark extends SimpleBenchmarkBase {

  @Override
  protected SimpleBenchmarkClient client() throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
    OkHttpClient client = new OkHttpClient.Builder()
            .sslSocketFactory(context.getSocketFactory(),
                              (X509TrustManager) InsecureTrustManagerFactory.INSTANCE.getTrustManagers()[0])
            .hostnameVerifier((s, session) -> true)
            .build();

    return new Retrofit.Builder()
        .baseUrl(baseUrl())
        .client(client)
        .addConverterFactory(JacksonConverterFactory.create())
        .addCallAdapterFactory(Java8CallAdapterFactory.create())
        .build()
        .create(SimpleBenchmarkClient.class);
  }
}
