<!-- 来源: https://docs.spring.io/spring-boot/reference/features/internationalization.html -->

# Internationalization

Spring Boot supports localized messages so that your application can cater to users of different language preferences.
By default, Spring Boot looks for the presence of a messages resource bundle at the root of the classpath.

The auto-configuration applies when the default properties file for the configured resource bundle is available (messages.properties by default).
If your resource bundle contains only language-specific properties files, you are required to add the default.
If no properties file is found that matches any of the configured base names, there will be no auto-configured MessageSource.

The basename of the resource bundle as well as several other attributes can be configured using the spring.messages namespace, as shown in the following example:

-
Properties

-
YAML

```
spring.messages.basename=messages, config.i18n.messages
spring.messages.common-messages=classpath:my-common-messages.properties
spring.messages.fallback-to-system-locale=false
```

```
spring:
  messages:
    basename: "messages, config.i18n.messages"
    common-messages: "classpath:my-common-messages.properties"
    fallback-to-system-locale: false
```

The spring.messages.basename property supports a list of locations, either a package qualifier or a resource resolved from the classpath root.
The spring.messages.common-messages property supports a list of property file resources.

See MessageSourceProperties for more supported options.