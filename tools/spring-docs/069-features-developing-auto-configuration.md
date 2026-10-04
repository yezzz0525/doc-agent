<!-- 来源: https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html -->

# Creating Your Own Auto-configuration

If you work in a company that develops shared libraries, or if you work on an open-source or commercial library, you might want to develop your own auto-configuration.
Auto-configuration classes can be bundled in external jars and still be picked up by Spring Boot.

Auto-configuration can be associated to a “starter” that provides the auto-configuration code as well as the typical libraries that you would use with it.
We first cover what you need to know to build your own auto-configuration and then we move on to the typical steps required to create a custom starter.

## Understanding Auto-configured Beans

Classes that implement auto-configuration are annotated with @AutoConfiguration.
This annotation itself is meta-annotated with @Configuration, making auto-configurations standard @Configuration classes.
Additional @Conditional annotations are used to constrain when the auto-configuration should apply.
Usually, auto-configuration classes use @ConditionalOnClass and @ConditionalOnMissingBean annotations.
This ensures that auto-configuration applies only when relevant classes are found and when you have not declared your own @Configuration.

You can browse the source code of spring-boot-autoconfigure to see the core @AutoConfiguration classes that Spring provides (see the META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports file).
You can also look at the equivalent file in other modules to see the auto-configurations that they provide.

## Locating Auto-configuration Candidates

Spring Boot checks for the presence of a META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports file within your published jar.
The file should list your configuration classes, with one class name per line, as shown in the following example:

```
com.mycorp.libx.autoconfigure.LibXAutoConfiguration
com.mycorp.libx.autoconfigure.LibXWebAutoConfiguration
```

You can add comments to the imports file using the # character.

In the unusual case that an auto-configuration class is not a top-level class, its class name should use $ to separate it from its containing class, for example com.example.Outer$NestedAutoConfiguration.

Auto-configurations must be loaded only by being named in the imports file.
Make sure that they are defined in a specific package space and that they are never the target of component scanning.
Furthermore, auto-configuration classes should not enable component scanning to find additional components.
Specific @Import annotations should be used instead.

If your configuration needs to be applied in a specific order, you can use the before, beforeName, after and afterName attributes on the @AutoConfiguration annotation or the dedicated @AutoConfigureBefore and @AutoConfigureAfter annotations.
For example, if you provide web-specific configuration, your class may need to be applied after WebMvcAutoConfiguration.

If you want to order certain auto-configurations that should not have any direct knowledge of each other, you can also use @AutoConfigureOrder.
That annotation has the same semantic as the regular @Order annotation but provides a dedicated order for auto-configuration classes.

As with standard @Configuration classes, the order in which auto-configuration classes are applied only affects the order in which their beans are defined.
The order in which those beans are subsequently created is unaffected and is determined by each bean’s dependencies and any @DependsOn relationships.

### Deprecating and Replacing Auto-configuration Classes

You may need to occasionally deprecate auto-configuration classes and offer an alternative.
For example, you may want to change the package name where your auto-configuration class resides.

Since auto-configuration classes may be referenced in before/after ordering and excludes, you’ll need to add an additional file that tells Spring Boot how to deal with replacements.
To define replacements, create a META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.replacements file indicating the link between the old class and the new one.

For example:

```
com.mycorp.libx.autoconfigure.LibXAutoConfiguration=com.mycorp.libx.autoconfigure.core.LibXAutoConfiguration
```

The AutoConfiguration.imports file should also be updated to only reference the replacement class.

## Condition Annotations

You almost always want to include one or more @Conditional annotations on your auto-configuration class.
The @ConditionalOnMissingBean annotation is one common example that is used to allow developers to override auto-configuration if they are not happy with your defaults.

Spring Boot includes a number of @Conditional annotations that you can reuse in your own code by annotating @Configuration classes or individual @Bean methods.
These annotations include:

-
Class Conditions

-
Bean Conditions

-
Property Conditions

-
Resource Conditions

-
Web Application Conditions

-
SpEL Expression Conditions

### Class Conditions

The @ConditionalOnClass and @ConditionalOnMissingClass annotations let @Configuration classes be included based on the presence or absence of specific classes.
Due to the fact that annotation metadata is parsed by using ASM, you can use the value attribute to refer to the real class, even though that class might not actually appear on the running application classpath.
You can also use the name attribute if you prefer to specify the class name by using a String value.

This mechanism does not apply the same way to @Bean methods where typically the return type is the target of the condition: before the condition on the method applies, the JVM will have loaded the class and potentially processed method references which will fail if the class is not present.

To handle this scenario, a separate @Configuration class can be used to isolate the condition, as shown in the following example:

-
Java

-
Kotlin

```
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration
// Some conditions ...
public final class MyAutoConfiguration {

	// Auto-configured beans ...

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(SomeService.class)
	static class SomeServiceConfiguration {

		@Bean
		@ConditionalOnMissingBean
		SomeService someService() {
			return new SomeService();
		}

	}

}
```

```
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@AutoConfiguration
// Some conditions ...
class MyAutoConfiguration {

	// Auto-configured beans ...
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(SomeService::class)
	class SomeServiceConfiguration {

		@Bean
		@ConditionalOnMissingBean
		fun someService(): SomeService {
			return SomeService()
		}

	}

}
```

If you use @ConditionalOnClass or @ConditionalOnMissingClass as a part of a meta-annotation to compose your own composed annotations, you must use name as referring to the class in such a case is not handled.

### Bean Conditions

The @ConditionalOnBean and @ConditionalOnMissingBean annotations let a bean be included based on the presence or absence of specific beans.
You can use the value attribute to specify beans by type or name to specify beans by name.
The search attribute lets you limit the ApplicationContext hierarchy that should be considered when searching for beans.

When placed on a @Bean method, the target type defaults to the return type of the method, as shown in the following example:

-
Java

-
Kotlin

```
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public final class MyAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	SomeService someService() {
		return new SomeService();
	}

}
```

```
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

@AutoConfiguration
class MyAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	fun someService(): SomeService {
		return SomeService()
	}

}
```

In the preceding example, the someService bean is going to be created if no bean of type SomeService is already contained in the ApplicationContext.

You need to be very careful about the order in which bean definitions are added, as these conditions are evaluated based on what has been processed so far.
For this reason, we recommend using only @ConditionalOnBean and @ConditionalOnMissingBean annotations on auto-configuration classes (since these are guaranteed to load after any user-defined bean definitions have been added).

@ConditionalOnBean and @ConditionalOnMissingBean do not prevent @Configuration classes from being created.
The only difference between using these conditions at the class level and marking each contained @Bean method with the annotation is that the former prevents registration of the @Configuration class as a bean if the condition does not match.

When declaring a @Bean method, provide as much type information as possible in the method’s return type.
For example, if your bean’s concrete class implements an interface the bean method’s return type should be the concrete class and not the interface.
Providing as much type information as possible in @Bean methods is particularly important when using bean conditions as their evaluation can only rely upon to type information that is available in the method signature.

### Property Conditions

The @ConditionalOnProperty annotation lets configuration be included based on a Spring Environment property.
Use the prefix and name attributes to specify the property that should be checked.
By default, any property that exists and is not equal to false is matched.
There is also a dedicated @ConditionalOnBooleanProperty annotation specifically made for boolean properties.
With both annotations you can also create more advanced checks by using the havingValue and matchIfMissing attributes.

If multiple names are given in the name attribute, all of the properties have to pass the test for the condition to match.

### Resource Conditions

The @ConditionalOnResource annotation lets configuration be included only when a specific resource is present.
Resources can be specified by using the usual Spring conventions, as shown in the following example: file:/home/user/test.dat.

### Web Application Conditions

The @ConditionalOnWebApplication and @ConditionalOnNotWebApplication annotations let configuration be included depending on whether the application is a web application.
A servlet-based web application is any application that uses a Spring WebApplicationContext, defines a session scope, or has a ConfigurableWebEnvironment.
A reactive web application is any application that uses a ReactiveWebApplicationContext, or has a ConfigurableReactiveWebEnvironment.

The @ConditionalOnWarDeployment and @ConditionalOnNotWarDeployment annotations let configuration be included depending on whether the application is a traditional WAR application that is deployed to a servlet container.
This condition will not match for applications that are run with an embedded web server.

### SpEL Expression Conditions

The @ConditionalOnExpression annotation lets configuration be included based on the result of a SpEL expression.

Referencing a bean in the expression will cause that bean to be initialized very early in context refresh processing.
As a result, the bean won’t be eligible for post-processing (such as configuration properties binding) and its state may be incomplete.

## Testing your Auto-configuration

An auto-configuration can be affected by many factors: user configuration (@Bean definition and Environment customization), condition evaluation (presence of a particular library), and others.
Concretely, each test should create a well defined ApplicationContext that represents a combination of those customizations.
ApplicationContextRunner provides a great way to achieve that.

ApplicationContextRunner doesn’t work when running the tests in a native image.

ApplicationContextRunner is usually defined as a field of the test class to gather the base, common configuration.
The following example makes sure that MyServiceAutoConfiguration is always invoked:

-
Java

-
Kotlin

```
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(MyServiceAutoConfiguration.class));
```

```
	val contextRunner = ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(MyServiceAutoConfiguration::class.java))
```

If multiple auto-configurations have to be defined, there is no need to order their declarations as they are invoked in the exact same order as when running the application.

Each test can use the runner to represent a particular use case.
For instance, the sample below invokes a user configuration (UserConfiguration) and checks that the auto-configuration backs off properly.
Invoking run provides a callback context that can be used with AssertJ.

-
Java

-
Kotlin

```
	@Test
	void defaultServiceBacksOff() {
		this.contextRunner.withUserConfiguration(UserConfiguration.class).run((context) -> {
			assertThat(context).hasSingleBean(MyService.class);
			assertThat(context).getBean("myCustomService").isSameAs(context.getBean(MyService.class));
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class UserConfiguration {

		@Bean
		MyService myCustomService() {
			return new MyService("mine");
		}

	}
```

```
	@Test
	fun defaultServiceBacksOff() {
		contextRunner.withUserConfiguration(UserConfiguration::class.java)
			.run { context: AssertableApplicationContext ->
				assertThat(context).hasSingleBean(MyService::class.java)
				assertThat(context).getBean("myCustomService")
					.isSameAs(context.getBean(MyService::class.java))
			}
	}

	@Configuration(proxyBeanMethods = false)
	internal class UserConfiguration {

		@Bean
		fun myCustomService(): MyService {
			return MyService("mine")
		}

	}
```

It is also possible to easily customize the Environment, as shown in the following example:

-
Java

-
Kotlin

```
	@Test
	void serviceNameCanBeConfigured() {
		this.contextRunner.withPropertyValues("user.name=test123").run((context) -> {
			assertThat(context).hasSingleBean(MyService.class);
			assertThat(context.getBean(MyService.class).getName()).isEqualTo("test123");
		});
	}
```

```
	@Test
	fun serviceNameCanBeConfigured() {
		contextRunner.withPropertyValues("user.name=test123").run { context: AssertableApplicationContext ->
			assertThat(context).hasSingleBean(MyService::class.java)
			assertThat(context.getBean(MyService::class.java).name).isEqualTo("test123")
		}
	}
```

The runner can also be used to display the ConditionEvaluationReport.
The report can be printed at INFO or DEBUG level.
The following example shows how to use the ConditionEvaluationReportLoggingListener to print the report in auto-configuration tests.

-
Java

-
Kotlin

```
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.logging.ConditionEvaluationReportLoggingListener;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MyConditionEvaluationReportingTests {

	@Test
	void autoConfigTest() {
		new ApplicationContextRunner()
			.withInitializer(ConditionEvaluationReportLoggingListener.forLogLevel(LogLevel.INFO))
			.run((context) -> {
				// Test something...
			});
	}

}
```

```
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.logging.ConditionEvaluationReportLoggingListener
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MyConditionEvaluationReportingTests {

	@Test
	fun autoConfigTest() {
		ApplicationContextRunner()
			.withInitializer(ConditionEvaluationReportLoggingListener.forLogLevel(LogLevel.INFO))
			.run { context: AssertableApplicationContext? ->
				// Test something...
			}
	}

}
```

### Simulating a Web Context

If you need to test an auto-configuration that only operates in a servlet or reactive web application context, use the WebApplicationContextRunner or ReactiveWebApplicationContextRunner respectively.

### Overriding the Classpath

It is also possible to test what happens when a particular class and/or package is not present at runtime.
Spring Boot ships with a FilteredClassLoader that can easily be used by the runner.
In the following example, we assert that if MyService is not present, the auto-configuration is properly disabled:

-
Java

-
Kotlin

```
	@Test
	void serviceIsIgnoredIfLibraryIsNotPresent() {
		this.contextRunner.withClassLoader(new FilteredClassLoader(MyService.class))
			.run((context) -> assertThat(context).doesNotHaveBean("myService"));
	}
```

```
	@Test
	fun serviceIsIgnoredIfLibraryIsNotPresent() {
		contextRunner.withClassLoader(FilteredClassLoader(MyService::class.java))
			.run { context: AssertableApplicationContext? ->
				assertThat(context).doesNotHaveBean("myService")
			}
	}
```

## Creating Your Own Starter

A typical Spring Boot starter contains code to auto-configure and customize the infrastructure of a given technology, let’s call that "acme".
To make it easily extensible, a number of configuration keys in a dedicated namespace can be exposed to the environment.
Finally, a single "starter" dependency is provided to help users get started as easily as possible.

Concretely, a custom starter can contain the following:

-
The acme-spring-boot module that contains the auto-configuration code for "acme" as well as any API to use the feature.

-
The acme-spring-boot-starter module that provides a dependency to the other starters required by "acme", acme-spring-boot, and potentially additional dependencies that are typically useful.
In a nutshell, adding the starter should provide everything needed to start using that library.

This separation in two modules is in no way necessary.
If "acme" has several flavors, options or optional features, then it is better to separate the auto-configuration as you can clearly express the fact some features are optional.
Besides, you have the ability to craft a starter that provides an opinion about those optional dependencies.
At the same time, others can rely only on acme-spring-boot and craft their own starter with different opinions.

If the auto-configuration is relatively straightforward and does not have optional features, merging the two modules in the starter is definitely an option.

If "acme" has a basic set of dependencies that are required to work, but you want to express a more opinionated view, then having a separate starter is a good option.

When testing "acme" features, you may need test-specific auto-configurations.
For example, you could offer a way to replace external dependencies with in-memory alternatives.
A separate test-scoped starter can be created for this purpose, following the same principles.

### Naming

You should make sure to provide a proper namespace for your starter.
Do not start your module names with spring-boot, even if you use a different Maven groupId.
We may offer official support for the thing you auto-configure in the future.

As a rule of thumb, you should name a combined module after the starter.
For example, assume that you are creating a starter for "acme" and that you name the auto-configure module acme-spring-boot and the starter acme-spring-boot-starter.
If you only have one module that combines the two, name it acme-spring-boot-starter.

If "acme" also has a test-scoped starter, name it acme-spring-boot-starter-test.

### Configuration keys

If your starter provides configuration keys, use a unique namespace for them.
In particular, do not include your keys in the namespaces that Spring Boot uses (such as server, management, spring, and so on).
If you use the same namespace, we may modify these namespaces in the future in ways that break your modules.
As a rule of thumb, prefix all your keys with a namespace that you own (for example acme).

Make sure that configuration keys are documented by adding field Javadoc for each property, as shown in the following example:

-
Java

-
Kotlin

```
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("acme")
public class AcmeProperties {

	/**
	 * Whether to check the location of acme resources.
	 */
	private boolean checkLocation = true;

	/**
	 * Timeout for establishing a connection to the acme server.
	 */
	private Duration loginTimeout = Duration.ofSeconds(3);

	// getters/setters ...

	public boolean isCheckLocation() {
		return this.checkLocation;
	}

	public void setCheckLocation(boolean checkLocation) {
		this.checkLocation = checkLocation;
	}

	public Duration getLoginTimeout() {
		return this.loginTimeout;
	}

	public void setLoginTimeout(Duration loginTimeout) {
		this.loginTimeout = loginTimeout;
	}

}
```

```
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("acme")
class AcmeProperties(

	/**
	 * Whether to check the location of acme resources.
	 */
	var isCheckLocation: Boolean = true,

	/**
	 * Timeout for establishing a connection to the acme server.
	 */
	var loginTimeout:Duration = Duration.ofSeconds(3))
```

You should only use plain text with @ConfigurationProperties field Javadoc, since they are not processed before being added to the JSON.

If you use @ConfigurationProperties with record class then record components' descriptions should be provided via class-level Javadoc tag @param (there are no explicit instance fields in record classes to put regular field-level Javadocs on).

Here are some rules we follow internally to make sure descriptions are consistent:

-
Do not start the description by "The" or "A".

-
For boolean types, start the description with "Whether" or "Enable".

-
For collection-based types, start the description with "Comma-separated list"

-
Use Duration rather than long and describe the default unit if it differs from milliseconds, such as "If a duration suffix is not specified, seconds will be used".

-
Do not provide the default value in the description unless it has to be determined at runtime.

Make sure to trigger meta-data generation so that IDE assistance is available for your keys as well.
You may want to review the generated metadata (META-INF/spring-configuration-metadata.json) to make sure your keys are properly documented.
Using your own starter in a compatible IDE is also a good idea to validate that quality of the metadata.

### The “autoconfigure” Module

The autoconfigure module contains everything that is necessary to get started with the library.
It may also contain configuration key definitions (such as @ConfigurationProperties) and any callback interface that can be used to further customize how the components are initialized.

You should mark the dependencies to the library as optional so that you can include the autoconfigure module in your projects more easily.
If you do it that way, the library is not provided and, by default, Spring Boot backs off.

Spring Boot uses an annotation processor to collect the conditions on auto-configurations in a metadata file (META-INF/spring-autoconfigure-metadata.properties).
If that file is present, it is used to eagerly filter auto-configurations that do not match, which will improve startup time.

When building with Maven, configure the compiler plugin (3.12.0 or later) to add spring-boot-autoconfigure-processor to the annotation processor paths:

```
<project>
	<build>
		<plugins>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-compiler-plugin</artifactId>
				<configuration>
					<annotationProcessorPaths>
						<path>
							<groupId>org.springframework.boot</groupId>
							<artifactId>spring-boot-autoconfigure-processor</artifactId>
						</path>
					</annotationProcessorPaths>
				</configuration>
			</plugin>
		</plugins>
	</build>
</project>
```

With Gradle, the dependency should be declared in the annotationProcessor configuration, as shown in the following example:

```
dependencies {
	annotationProcessor "org.springframework.boot:spring-boot-autoconfigure-processor"
}
```

### Starter Module

The starter is really an empty jar.
Its only purpose is to provide the necessary dependencies to work with the library.
You can think of it as an opinionated view of what is required to get started.

Do not make assumptions about the project in which your starter is added.
If the library you are auto-configuring typically requires other starters, mention them as well.
Providing a proper set of default dependencies may be hard if the number of optional dependencies is high, as you should avoid including dependencies that are unnecessary for a typical usage of the library.
In other words, you should not include optional dependencies.

Either way, your starter must reference the core Spring Boot starter (spring-boot-starter) directly or indirectly (there is no need to add it if your starter relies on another starter).
If a project is created with only your custom starter, Spring Boot’s core features will be honoured by the presence of the core starter.