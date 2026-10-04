<!-- 来源: https://docs.spring.io/spring-boot/reference/io/spring-batch.html -->

# Spring Batch

Spring Boot offers several conveniences for working with Spring Batch, including running a Job on startup.

When building a batch application, the following stores can be auto-configured:

-
In-memory

-
JDBC

-
MongoDB

Each store has specific additional settings.
For instance, it is possible to customize the tables prefix for the JDBC store, as shown in the following example:

-
Properties

-
YAML

```
spring.batch.jdbc.table-prefix=CUSTOM_
```

```
spring:
  batch:
    jdbc:
      table-prefix: "CUSTOM_"
```

When using the MongoDB store, you can enable initialization of the Spring Batch job repository schema (collections and indexes):

-
Properties

-
YAML

```
spring.batch.data.mongodb.schema.initialize=true
```

```
spring:
  batch:
    data:
      mongodb:
        schema:
          initialize: true
```

To disable Spring Boot’s auto-configuration and take complete control of Spring Batch’s configuration, add @EnableBatchProcessing to one of your @Configuration classes or extend DefaultBatchConfiguration.
This will cause the auto-configuration to back off, including initialization of Spring Batch’s database schema (JDBC or MongoDB).
Spring Batch can then be configured using the @Enable*JobRepository annotation’s attributes rather than the previously described configuration properties.

To learn more about manually configuring Spring Batch, see the API documentation of:

-
DefaultBatchConfiguration

-
@EnableBatchProcessing

-
@EnableJdbcJobRepository

-
@EnableMongoJobRepository

For more information about Spring Batch, see the Spring Batch project page.

## Running Spring Batch Jobs on Startup

When Spring Boot auto-configures Spring Batch, and if a single Job bean is found in the application context, it is executed on startup (see JobLauncherApplicationRunner for details).
If multiple Job beans are found, the job that should be executed must be specified using spring.batch.job.name.

You can disable running a Job found in the application context, as shown in the following example:

-
Properties

-
YAML

```
spring.batch.job.enabled=false
```

```
spring:
  batch:
    job:
      enabled: false
```

See BatchAutoConfiguration, BatchJdbcAutoConfiguration, and BatchDataMongoAutoConfiguration for more details.