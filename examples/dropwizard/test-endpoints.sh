#!/usr/bin/env bash

curl -v -w "\n\n" http://localhost:8080
curl -v -w "\n\n" http://localhost:8080/armeria
curl -v -w "\n\n" http://localhost:8080/jersey
curl -v -w "\n\n" http://localhost:8080/hello
curl -v -w "\n\n" -H 'Accept: application/json' http://localhost:8080/hello

curl -v -w "\n\n" http://localhost:8080/unknown

curl -kL -v -w "\n\n" http://localhost:8080/admin
curl -v -w "\n\n" http://localhost:8080/admin/ping
curl -v -w "\n\n" http://localhost:8080/admin/threads
curl -v -w "\n\n" http://localhost:8080/admin/healthcheck
curl -v -w "\n\n" http://localhost:8080/admin/metrics