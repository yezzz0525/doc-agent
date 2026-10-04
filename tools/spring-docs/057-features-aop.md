<!-- 来源: https://docs.spring.io/spring-boot/reference/features/aop.html -->

# Aspect-Oriented Programming

Spring Boot provides auto-configuration for aspect-oriented programming (AOP).
You can learn more about AOP with Spring in the Spring Framework reference documentation.

By default, Spring Boot’s auto-configuration configures Spring AOP to use CGLib proxies.
To use JDK proxies instead, set spring.aop.proxy-target-class to false.

If AspectJ is on the classpath, Spring Boot’s auto-configuration will automatically enable AspectJ auto proxy such that @EnableAspectJAutoProxy is not required.