.. _`an API gateway`: http://microservices.io/patterns/apigateway.html
.. _`Netty`: https://netty.io/
.. _`OkHttp`: https://square.github.io/okhttp/
.. _`Retrofit`: https://square.github.io/retrofit/

.. _client-retrofit:

Retrofit integration
====================

`Retrofit`_ is a library that simplifies the access to RESTful services by turning an HTTP API into a Java
interface.

Armeria provides a builder class called :api:`ArmeriaRetrofitBuilder` that builds an alternative
``Retrofit`` implementation that replaces the networking engine of from `OkHttp`_ to Armeria. By doing so,
you get the following benefits:

- Better performance, thanks to `Netty`_ and its JNI-based I/O and TLS implementation
- Leverage other advanced features of Armeria, such as client-side load-balancing and service discovery
- Cleartext HTTP/2 support, as known as ``h2c``

.. code-block:: java

    import com.linecorp.armeria.client.Clients;
    import com.linecorp.armeria.client.HttpClient;

    import retrofit2.Retrofit;
    import retrofit2.converter.jackson.JacksonConverterFactory;
    import retrofit2.http.GET;
    import retrofit2.http.Path;

    class UserInfo { ... }

    interface MyService {
        @GET("/userInfo/{id}")
        CompletableFuture<UserInfo> getUserInfo(@Path("id") String id);
    }

    Retrofit retrofit = new ArmeriaRetrofitBuilder()
            .baseUrl("http://localhost:8080/")
            .addConverterFactory(JacksonConverterFactory.create())
            .build();

    MyService service = retrofit.create(MyService.class);
    UserInfo userInfo = service.getUserInfo("foo").get();

For more information, please refer to the API documentation of the
:api:`com.linecorp.armeria.client.retrofit2` package.
