FROM gradle:jdk8 as build-stage
RUN mkdir /app
COPY . /app/
WORKDIR /app
RUN gradle build -x test --parallel

FROM openjdk:8-jre-alpine
RUN mkdir /app
COPY --from=build-stage /app/marklogic-data-hub-central/build/libs/*.war /app/hc.war
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "hc.war"]
