.. _`com.linecorp.armeria.client.http.retrofit2`: apidocs/index.html?com/linecorp/armeria/client/http/retrofit2/package-summary.html

Using Armeria as a Retrofit2 HTTP client
========================================
For more information, please refer to the API documentation of the `com.linecorp.armeria.client.http.retrofit2`_ package.

.. code-block:: java

    import com.linecorp.armeria.client.Clients;
    import com.linecorp.armeria.client.http.HttpClient;

    import retrofit2.Retrofit;
    import retrofit2.converter.jackson.JacksonConverterFactory;
    import retrofit2.http.GET;

    class UserInfo {}

    interface MyService {
        @GET("/userInfo")
        UserInfo getUserInfo(String id);
    }

    HttpClient httpClient = Clients.newClient("none+http://localhost:8080", HttpClient.class);

    Retrofit retrofit = ArmeriaRetrofit.builder(httpClient)
                                       .addConverterFactory(JacksonConverterFactory.create())
                                       .build();

    MyService service = retrofit.create(MyService.class);
    service.getUserInfo("foo");
