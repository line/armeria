#!/bin/bash

# Client certificate
keytool -genkeypair -alias client -keyalg RSA -keystore client_keystore.jks -storepass 123456

# Server certificate
keytool -genkeypair -alias server -keyalg RSA -keystore server_keystore.jks -storepass 123456

keytool -export -alias client -keystore client_keystore.jks -file client_cert.cer

keytool -export -alias server -keystore server_keystore.jks -file server_cert.cer

keytool -import -alias client -file client_cert.cer -keystore server_truststore.jks -storepass 123456

keytool -import -alias server -file server_cert.cer -keystore client_truststore.jks -storepass 123456

