# Stage 1 for Build
FROM openjdk:jdk8u282-b08 as buildimage

# SSH key as a build argument, if passed
ARG SSH_PRIVATE_KEY

# Install dependencies required to git clone
RUN apk update && \
    apk add --update git && \
    apk add --update openssh

# 1. Create the SSH directory.
# 2. Populate the private key file.
# 3. Set the required permissions.
# 4. Add github to list of known hosts for ssh.
RUN mkdir -p /root/.ssh/ && \
    echo "$SSH_KEY" > /root/.ssh/id_rsa && \
    chmod -R 600 /root/.ssh/ && \
    ssh-keyscan -t rsa github.com >> ~/.ssh/known_hosts

# Clone armeria examples repository & build
RUN mkdir -p /opt/armeria
RUN git clone https://github.com/line/armeria-examples.git /opt/armeria
RUN cd /opt/armeria/spring-boot-minimal && \
    ./gradlew assemble


# Stage 2 for Docker Image
FROM openjdk:jre8u282-b08
RUN apk --no-cache add ca-certificates git openssh && \
  mkdir -p /opt/armeria
RUN addgroup -S armeria && adduser -S armeria -G armeria

# Copy JAR file from Build
COPY --from=buildimage /opt/armeria/spring-boot-minimal/build/libs/spring-boot-minimal.jar /opt/armeria/app.jar

USER armeria:armeria

EXPOSE 8080
ENTRYPOINT ["java","-jar","/opt/armeria/app.jar"] 
