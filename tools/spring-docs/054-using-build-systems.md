<!-- 来源: https://docs.spring.io/spring-boot/reference/using/build-systems.html -->

# Build Systems

It is strongly recommended that you choose a build system that supports dependency management and that can consume artifacts published to the Maven Central repository.
We would recommend that you choose Maven or Gradle.
It is possible to get Spring Boot to work with other build systems (Ant, for example), but they are not particularly well supported.

## Dependency Management

Each release of Spring Boot provides a curated list of dependencies that it supports.
In practice, you do not need to provide a version for any of these dependencies in your build configuration, as Spring Boot manages that for you.
When you upgrade Spring Boot itself, these dependencies are upgraded as well in a consistent way.

You can still specify a version and override Spring Boot’s recommendations if you need to do so.

The curated list contains all the Spring modules that you can use with Spring Boot as well as a refined list of third party libraries.
The list is available as a standard Bills of Materials (spring-boot-dependencies) that can be used with both Maven and Gradle.

Each release of Spring Boot is associated with a base version of the Spring Framework.
We highly recommend that you do not specify its version.

## Maven

To learn about using Spring Boot with Maven, see the documentation for Spring Boot’s Maven plugin:

-
Reference

-
API

## Gradle

To learn about using Spring Boot with Gradle, see the documentation for Spring Boot’s Gradle plugin:

-
Reference

-
API

## Ant

It is possible to build a Spring Boot project using Apache Ant+Ivy.
The spring-boot-antlib “AntLib” module is also available to help Ant create executable jars.

To declare dependencies, a typical ivy.xml file looks something like the following example:

```
<ivy-module version="2.0">
	<info organisation="org.springframework.boot" module="spring-boot-sample-ant" />
	<configurations>
		<conf name="compile" description="everything needed to compile this module" />
		<conf name="runtime" extends="compile" description="everything needed to run this module" />
	</configurations>
	<dependencies>
		<dependency org="org.springframework.boot" name="spring-boot-starter"
			rev="${spring-boot.version}" conf="compile" />
	</dependencies>
</ivy-module>
```

A typical build.xml looks like the following example:

```
<project
	xmlns:ivy="antlib:org.apache.ivy.ant"
	xmlns:spring-boot="antlib:org.springframework.boot.ant"
	name="myapp" default="build">

	<property name="spring-boot.version" value="4.1.1" />

	<target name="resolve" description="--> retrieve dependencies with ivy">
		<ivy:retrieve pattern="lib/[conf]/[artifact]-[type]-[revision].[ext]" />
	</target>

	<target name="classpaths" depends="resolve">
		<path id="compile.classpath">
			<fileset dir="lib/compile" includes="*.jar" />
		</path>
	</target>

	<target name="init" depends="classpaths">
		<mkdir dir="build/classes" />
	</target>

	<target name="compile" depends="init" description="compile">
		<javac srcdir="src/main/java" destdir="build/classes" classpathref="compile.classpath" />
	</target>

	<target name="build" depends="compile">
		<spring-boot:exejar destfile="build/myapp.jar" classes="build/classes">
			<spring-boot:lib>
				<fileset dir="lib/runtime" />
			</spring-boot:lib>
		</spring-boot:exejar>
	</target>
</project>
```

If you do not want to use the spring-boot-antlib module, see the Build an Executable Archive From Ant without Using spring-boot-antlib section of “How-to Guides”.

## Starters

Starters are a set of convenient dependency descriptors that you can include in your application.
You get a one-stop shop for all the Spring and related technologies that you need without having to hunt through sample code and copy-paste loads of dependency descriptors.
For example, if you want to get started using Spring and JPA for database access, include the spring-boot-starter-data-jpa dependency in your project.

The starters contain a lot of the dependencies that you need to get a project up and running quickly and with a consistent, supported set of managed transitive dependencies.

What is in a name

All official starters follow a similar naming pattern; spring-boot-starter-*, where * is a particular type of application.
This naming structure is intended to help when you need to find a starter.
The Maven integration in many IDEs lets you search dependencies by name.
For example, with the appropriate Eclipse or Spring Tools plugin installed, you can press ctrl-space in the POM editor and type “spring-boot-starter” for a complete list.

As explained in the Creating Your Own Starter section, third party starters should not start with spring-boot, as it is reserved for official Spring Boot artifacts.
Rather, a third-party starter typically starts with the name of the project.
For example, a third-party starter project called thirdpartyproject would typically be named thirdpartyproject-spring-boot-starter.

The following application starters are provided by Spring Boot under the org.springframework.boot group:

Table 1. Spring Boot application starters

Name
Description

spring-boot-starter

Core starter, including auto-configuration support, logging and YAML

spring-boot-starter-activemq

Starter for using Apache ActiveMQ and JMS

spring-boot-starter-activemq-test

Starter for testing using Apache ActiveMQ and JMS

spring-boot-starter-actuator-test

Starter for testing Spring Boot’s Actuator which provides production ready features to help you monitor and manage your application

spring-boot-starter-amqp

Starter for using Spring AMQP and Rabbit MQ

spring-boot-starter-amqp-test

Starter for testing Spring AMQP and Rabbit MQ

spring-boot-starter-artemis

Starter for using Apache Artemis and JMS

spring-boot-starter-artemis-test

Starter for testing Apache Artemis and JMS

spring-boot-starter-aspectj

Starter for using aspect-oriented programming with AspectJ

spring-boot-starter-aspectj-test

Starter for testing aspect-oriented programming with AspectJ

spring-boot-starter-batch

Starter for using Spring Batch

spring-boot-starter-batch-data-mongodb

Starter for using Spring Batch with Data MongoDB

spring-boot-starter-batch-data-mongodb-test

Starter for testing using Spring Batch with Data MongoDB

spring-boot-starter-batch-jdbc

Starter for using Spring Batch with JDBC

spring-boot-starter-batch-jdbc-test

Starter for testing using Spring Batch with JDBC

spring-boot-starter-batch-test

Starter for testing using Spring Batch

spring-boot-starter-cache

Starter for using Spring’s caching support

spring-boot-starter-cache-test

Starter for testing Spring’s caching support

spring-boot-starter-cassandra

Starter for using Cassandra distributed database

spring-boot-starter-cassandra-test

Starter for testing Cassandra distributed database

spring-boot-starter-classic

Core classic starter, including full auto-configuration support, logging and YAML

spring-boot-starter-cloudfoundry

Starter for using Cloud Foundry

spring-boot-starter-cloudfoundry-test

Starter for testing Cloud Foundry

spring-boot-starter-couchbase

Starter for using Couchbase document-oriented database

spring-boot-starter-couchbase-test

Starter for testing Couchbase document-oriented database

spring-boot-starter-data-cassandra

Starter for using Cassandra distributed database and Spring Data Cassandra

spring-boot-starter-data-cassandra-reactive

Starter for using Cassandra distributed database and Spring Data Cassandra Reactive

spring-boot-starter-data-cassandra-reactive-test

Starter for testing Cassandra distributed database and Spring Data Cassandra Reactive

spring-boot-starter-data-cassandra-test

Starter for testing Cassandra distributed database and Spring Data Cassandra

spring-boot-starter-data-couchbase

Starter for using Couchbase document-oriented database and Spring Data Couchbase

spring-boot-starter-data-couchbase-reactive

Starter for using Couchbase document-oriented database and Spring Data Couchbase Reactive

spring-boot-starter-data-couchbase-reactive-test

Starter for testing Couchbase document-oriented database and Spring Data Couchbase Reactive

spring-boot-starter-data-couchbase-test

Starter for testing Couchbase document-oriented database and Spring Data Couchbase

spring-boot-starter-data-elasticsearch

Starter for using Elasticsearch search and analytics engine and Spring Data Elasticsearch

spring-boot-starter-data-elasticsearch-test

Starter for testing Elasticsearch search and analytics engine and Spring Data Elasticsearch

spring-boot-starter-data-jdbc

Starter for using Spring Data JDBC

spring-boot-starter-data-jdbc-test

Starter for testing Spring Data JDBC

spring-boot-starter-data-jpa

Starter for using Spring Data JPA with Hibernate

spring-boot-starter-data-jpa-test

Starter for testing Spring Data JPA with Hibernate

spring-boot-starter-data-ldap

Starter for using Spring Data LDAP

spring-boot-starter-data-ldap-test

Starter for testing Spring Data LDAP

spring-boot-starter-data-mongodb

Starter for using MongoDB document-oriented database and Spring Data MongoDB

spring-boot-starter-data-mongodb-reactive

Starter for using MongoDB document-oriented database and Spring Data MongoDB Reactive

spring-boot-starter-data-mongodb-reactive-test

Starter for using MongoDB document-oriented database and Spring Data MongoDB Reactive

spring-boot-starter-data-mongodb-test

Starter for testing MongoDB document-oriented database and Spring Data MongoDB

spring-boot-starter-data-neo4j

Starter for using Neo4j graph database and Spring Data Neo4j

spring-boot-starter-data-neo4j-test

Starter for testing Neo4j graph database and Spring Data Neo4j

spring-boot-starter-data-r2dbc

Starter for using Spring Data R2DBC

spring-boot-starter-data-r2dbc-test

Starter for testing Spring Data R2DBC

spring-boot-starter-data-redis

Starter for using Redis key-value data store with Spring Data Redis and the Lettuce client

spring-boot-starter-data-redis-reactive

Starter for using Redis key-value data store with Spring Data Redis reactive and the Lettuce client

spring-boot-starter-data-redis-reactive-test

Starter for testing Redis key-value data store with Spring Data Redis reactive and the Lettuce client

spring-boot-starter-data-redis-test

Starter for testing Redis key-value data store with Spring Data Redis and the Lettuce client

spring-boot-starter-data-rest

Starter for using Spring Data repositories exposed over REST using Spring Data REST and Spring MVC

spring-boot-starter-data-rest-test

Starter for testing Spring Data repositories exposed over REST using Spring Data REST and Spring MVC

spring-boot-starter-elasticsearch

Starter for using Elasticsearch search and analytics engine

spring-boot-starter-elasticsearch-test

Starter for testing Elasticsearch search and analytics engine

spring-boot-starter-flyway

Starter for using Flyway database migrations

spring-boot-starter-flyway-test

Starter for testing Flyway database migrations

spring-boot-starter-freemarker

Starter for using FreeMarker

spring-boot-starter-freemarker-test

Starter for testing FreeMarker

spring-boot-starter-graphql

Starter using Spring GraphQL

spring-boot-starter-graphql-test

Starter for testing Spring GraphQL

spring-boot-starter-groovy-templates

Starter for using Groovy Templates

spring-boot-starter-groovy-templates-test

Starter for testing Groovy Templates

spring-boot-starter-grpc-client

Starter for using Spring gRPC client

spring-boot-starter-grpc-client-test

Starter for testing gRPC client

spring-boot-starter-grpc-server

Starter for using Spring gRPC server

spring-boot-starter-grpc-server-test

Starter for testing gRPC server

spring-boot-starter-gson

Starter for using GSON

spring-boot-starter-gson-test

Starter for testing GSON

spring-boot-starter-hateoas

Starter for using Spring HATEOS to build hypermedia-based RESTful Spring MVC web applications

spring-boot-starter-hateoas-test

Starter for testing Spring HATEOS to build hypermedia-based RESTful Spring MVC web applications

spring-boot-starter-hazelcast

Starter for using Hazelcast

spring-boot-starter-hazelcast-test

Starter for testing Hazelcast

spring-boot-starter-integration

Starter for using Spring Integration

spring-boot-starter-integration-test

Starter for testing Spring Integration

spring-boot-starter-jackson

Starter for using Jackson

spring-boot-starter-jackson-test

Starter for testing Jackson

spring-boot-starter-jdbc

Starter for using JDBC with the HikariCP connection pool

spring-boot-starter-jdbc-test

Starter for testing JDBC with the HikariCP connection pool

spring-boot-starter-jersey

Starter for using JAX-RS and Jersey

spring-boot-starter-jersey-test

Starter for testing JAX-RS and Jersey

spring-boot-starter-jetty

Starter for using Jetty as the embedded servlet container

spring-boot-starter-jms

Starter for using JMS

spring-boot-starter-jms-test

Starter for testing JMS

spring-boot-starter-jooq

Starter for using jOOQ to access SQL databases with JDBC

spring-boot-starter-jooq-test

Starter for testing jOOQ to access SQL databases with JDBC

spring-boot-starter-json

Starter for reading and writing JSON

spring-boot-starter-jsonb

Starter for using JSON-B

spring-boot-starter-jsonb-test

Starter for testing JSON-B

spring-boot-starter-kafka

Starter for using Apache Kafka

spring-boot-starter-kafka-test

Starter for testing Apache Kafka

spring-boot-starter-kotlinx-serialization-json

Starter for using Kotlinx Serialization JSON

spring-boot-starter-kotlinx-serialization-json-test

Starter for testing Kotlinx Serialization JSON

spring-boot-starter-ldap

Starter for using LDAP

spring-boot-starter-ldap-test

Starter for testing LDAP

spring-boot-starter-liquibase

Starter for using Liquibase database migrations

spring-boot-starter-liquibase-test

Starter for testing Liquibase database migrations

spring-boot-starter-mail

Starter for using Java Mail and Spring Framework’s email sending support

spring-boot-starter-mail-test

Starter for testing Java Mail and Spring Framework’s email sending support

spring-boot-starter-micrometer-metrics

Starter for using Micrometer Metrics

spring-boot-starter-micrometer-metrics-test

Starter for testing Micrometer Metrics

spring-boot-starter-mongodb

Starter for using MongoDB document-oriented database

spring-boot-starter-mongodb-test

Starter for testing MongoDB document-oriented database

spring-boot-starter-mustache

Starter for using Mustache

spring-boot-starter-mustache-test

Starter for testing Mustache

spring-boot-starter-neo4j

Starter for using Neo4j graph database

spring-boot-starter-neo4j-test

Starter for testing Neo4j graph database

spring-boot-starter-oauth2-authorization-server

Starter for using Spring Authorization Server features (deprecated in favor of spring-boot-starter-security-oauth2-authorization-server)

spring-boot-starter-oauth2-client

Starter for using Spring Security’s OAuth2/OpenID Connect client features (deprecated in favor of spring-boot-starter-security-oauth2-client)

spring-boot-starter-oauth2-resource-server

Starter for using Spring Security’s OAuth2 resource server features (deprecated in favor of spring-boot-starter-security-oauth2-resource-server)

spring-boot-starter-opentelemetry

Starter for using OpenTelemetry

spring-boot-starter-opentelemetry-test

Starter for testing OpenTelemetry

spring-boot-starter-pulsar

Starter for using Spring for Apache Pulsar

spring-boot-starter-pulsar-test

Starter for testing Spring for Apache Pulsar

spring-boot-starter-quartz

Starter for using the Quartz scheduler

spring-boot-starter-quartz-test

Starter for testing the Quartz scheduler

spring-boot-starter-r2dbc

Starter for using R2DBC

spring-boot-starter-r2dbc-test

Starter for testing R2DBC

spring-boot-starter-reactor-netty

Starter for Reactor Netty

spring-boot-starter-restclient

Starter using Spring’s blocking HTTP clients (RestClient, RestTemplate and HTTP Service Clients)

spring-boot-starter-restclient-test

Starter for testing Spring’s blocking HTTP clients (RestClient, RestTemplate and HTTP Service Clients)

spring-boot-starter-rsocket

Starter for using RSocket

spring-boot-starter-rsocket-test

Starter for testing RSocket

spring-boot-starter-security

Starter for using Spring Security

spring-boot-starter-security-oauth2-authorization-server

Starter for using Spring Authorization Server features

spring-boot-starter-security-oauth2-authorization-server-test

Starter for testing Spring Authorization Server features

spring-boot-starter-security-oauth2-client

Starter for using Spring Security’s OAuth2/OpenID Connect client features

spring-boot-starter-security-oauth2-client-test

Starter for testing Spring Security’s OAuth2/OpenID Connect client features

spring-boot-starter-security-oauth2-resource-server

Starter for using Spring Security’s OAuth2 resource server features

spring-boot-starter-security-oauth2-resource-server-test

Starter for testing Spring Security’s OAuth2 resource server features

spring-boot-starter-security-saml2

Starter for using Spring Security with SAML2

spring-boot-starter-security-saml2-test

Starter for testing Spring Security with SAML2

spring-boot-starter-security-test

Starter for testing Spring Security

spring-boot-starter-sendgrid

Starter for using Spring Session with Sendgrid

spring-boot-starter-sendgrid-test

Starter for testing Spring Session with Sendgrid

spring-boot-starter-session-data-redis

Starter for using Spring Session with Spring Data Redis

spring-boot-starter-session-data-redis-test

Starter for testing Spring Session with Spring Data Redis

spring-boot-starter-session-jdbc

Starter for using Spring Session with JDBC

spring-boot-starter-session-jdbc-test

Starter for testing Spring Session with JDBC

spring-boot-starter-test

Starter for testing Spring Boot applications with libraries including JUnit Jupiter, Hamcrest and Mockito

spring-boot-starter-test-classic

Classic starter for testing Spring Boot applications with libraries including JUnit Jupiter, Hamcrest and Mockito

spring-boot-starter-thymeleaf

Starter for using Thymeleaf

spring-boot-starter-thymeleaf-test

Starter for testing Thymeleaf

spring-boot-starter-tomcat

Starter for using Tomcat as the embedded servlet container

spring-boot-starter-validation

Starter for using Java Bean Validation with Hibernate Validator

spring-boot-starter-validation-test

Starter for testing Java Bean Validation with Hibernate Validator

spring-boot-starter-web

Starter for building web, including RESTful, applications using Spring MVC. Uses Tomcat as the default embedded container (deprecated in favor of spring-boot-starter-webmvc)

spring-boot-starter-web-server-test

Starter for testing Spring Web Server

spring-boot-starter-web-services

Starter for using Spring Web Services (deprecated in favor of spring-boot-starter-webservices)

spring-boot-starter-webclient

Starter using Spring’s reactive HTTP clients (WebClient and HTTP Service Clients)

spring-boot-starter-webclient-test

Starter for testing Spring’s reactive HTTP clients (WebClient and HTTP Service Clients)

spring-boot-starter-webflux

Starter for using WebFlux and Reactor Netty

spring-boot-starter-webflux-test

Starter for testing WebFlux and Reactor Netty

spring-boot-starter-webmvc

Starter for using Spring MVC and Tomcat

spring-boot-starter-webmvc-test

Starter for testing Spring MVC and Tomcat

spring-boot-starter-webservices

Starter for using Spring Web Services

spring-boot-starter-webservices-test

Starter for testing Spring Web Services

spring-boot-starter-websocket

Starter for using Spring MVC WebSocket support

spring-boot-starter-websocket-test

Starter for testing Spring MVC WebSocket support

spring-boot-starter-zipkin

Starter for using Zipkin

spring-boot-starter-zipkin-test

Starter for testing Zipkin

In addition to the application starters, the following starters can be used to add production ready features:

Table 2. Spring Boot production starters

Name
Description

spring-boot-starter-actuator

Starter for using Spring Boot’s Actuator which provides production ready features to help you monitor and manage your application

Finally, Spring Boot also includes the following starters that can be used if you want to exclude or swap specific technical facets:

Table 3. Spring Boot technical starters

Name
Description

spring-boot-starter-jetty-runtime

Starter for the Jetty runtime

spring-boot-starter-log4j2

Starter for using Log4j2

spring-boot-starter-logback

Starter for logging using Logback

spring-boot-starter-logging

Starter for logging default logging

spring-boot-starter-restdocs

Starter for using Spring REST Docs

spring-boot-starter-tomcat-runtime

Starter for the Tomcat runtime

To learn how to swap technical facets, please see the how-to documentation for swapping web server and logging system.

For a list of additional community contributed starters, see the README file in the spring-boot-starters module on GitHub.