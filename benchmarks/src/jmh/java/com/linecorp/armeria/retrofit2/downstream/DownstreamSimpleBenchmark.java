package com.linecorp.armeria.retrofit2.downstream;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofitBuilder;
import com.linecorp.armeria.retrofit2.shared.SimpleBenchmarkBase;
import com.linecorp.armeria.retrofit2.shared.SimpleBenchmarkClient;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import retrofit2.adapter.java8.Java8CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

@State(Scope.Benchmark)
public class DownstreamSimpleBenchmark extends SimpleBenchmarkBase {

  @Override
  protected SimpleBenchmarkClient client() {
    ClientFactory factory = new ClientFactoryBuilder()
            .sslContextCustomizer(ssl -> ssl.trustManager(InsecureTrustManagerFactory.INSTANCE))
            .build();
    return new ArmeriaRetrofitBuilder(factory)
            .baseUrl(baseUrl())
            .addConverterFactory(JacksonConverterFactory.create())
            .addCallAdapterFactory(Java8CallAdapterFactory.create())
            .build()
            .create(SimpleBenchmarkClient.class);
  }
}
