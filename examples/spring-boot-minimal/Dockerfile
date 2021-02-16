FROM openjdk:8-jdk-alpine
RUN apk --update add git openssh && \
  rm -rf /var/lib/apt/lists/* && \
  rm /var/cache/apk/* && \
  mkdir -p /opt/armeria
RUN addgroup -S armeria && adduser -S armeria -G armeria
ARG JAR_FILE=build/libs/spring-boot-minimal.jar
ADD ${JAR_FILE} /opt/armeria/app.jar
USER armeria:armeria
EXPOSE 8080
ENTRYPOINT ["java","-jar","/opt/armeria/app.jar"]