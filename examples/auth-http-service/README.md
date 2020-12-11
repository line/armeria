#AuthService
## Hello Word
``` java
final Authorizer<HttpRequest> authorizer = (ctx, req) ->
        CompletableFuture.supplyAsync(
                () -> "token".equals(req.headers().get(AUTHORIZATION)));
sb.service("/hello",
        service.decorate(AuthService.newDecorator(authorizer)));

```
 request
```
curl -H "AUTHORIZATION:token" -X GET http://127.0.0.1:8089/hello 
```
 response
```
200 OK
```


### Auth1a
``` java
    final AuthServiceBuilder auth1aServiceBuilder = AuthService.builder();
    auth1aServiceBuilder.add(new Auth1aHandler());
    final AuthService auth1aService = auth1aServiceBuilder.build(new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            final String name = ctx.pathParam("name");
            return HttpResponse.of("Hello, %s!", name);
        }
    });
    sb.service("/auth1a/{name}",auth1aService);
```
 request
```
curl -H "authorization:OAuth realm=\"dummy_realm\",oauth_consumer_key=\"dummy_consumer_key@#$!\",oauth_token=\"dummy_oauth1a_token\",oauth_signature_method=\"dummy\",oauth_signature=\"dummy_signature\",oauth_timestamp=\"0\",oauth_nonce=\"dummy_nonce\",version=\"1.0\"" -X GET http://127.0.0.1:8089/auth1a/armeria
```
 response
```
Hello, armeria!
```

### auth2
``` java
    final AuthServiceBuilder auth2ServiceBuilder = AuthService.builder();
    auth2ServiceBuilder.add(new Auth2Handler());
    final AuthService auth2Service = auth2ServiceBuilder.build(new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
            final String name = ctx.pathParam("name");
            return HttpResponse.of("Hello, %s!", name);
        }
    });
    sb.service("/auth2/{name}",auth2Service);
```
 request
```
curl -H "authorization:Bearer accessToken" http://127.0.0.1:8089/auth2/armeria
```
 response
```
Hello, armeria!
```
