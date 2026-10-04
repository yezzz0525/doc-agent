<!-- 来源: https://docs.spring.io/spring-boot/reference/testing/test-scope-dependencies.html -->

# Test Scope Dependencies

The spring-boot-starter-test starter (in the test scope) contains the following provided libraries:

-
JUnit: The de-facto standard for unit testing Java applications.

-
Spring Test & Spring Boot Test: Utilities and integration test support for Spring Boot applications.

-
AssertJ: A fluent assertion library.

-
Hamcrest: A library of matcher objects (also known as constraints or predicates).

-
Mockito: A Java mocking framework.

-
JSONassert: An assertion library for JSON.

-
JsonPath: XPath for JSON.

-
Awaitility: A library for testing asynchronous systems.

We generally find these common libraries to be useful when writing tests.
If these libraries do not suit your needs, you can add additional test dependencies of your own.