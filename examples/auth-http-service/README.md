vscode request.http
### armeria
GET  http://127.0.0.1:8089/welcom HTTP/1.1
AUTHORIZATION:token
### auth1a
GET  http://127.0.0.1:8089/auth1a/croco HTTP/1.1
authorization:OAuth realm="dummy_realm",oauth_consumer_key="dummy_consumer_key@#$!",oauth_token="dummy_oauth1a_token",oauth_signature_method="dummy",oauth_signature="dummy_signature",oauth_timestamp="0",oauth_nonce="dummy_nonce",version="1.0"
### auth2
GET  http://127.0.0.1:8089/auth2/croco HTTP/1.1
authorization:Bearer accessToken