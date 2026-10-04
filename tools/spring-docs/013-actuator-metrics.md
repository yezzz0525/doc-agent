<!-- 来源: https://docs.spring.io/spring-boot/reference/actuator/metrics.html -->

# Metrics

Spring Boot Actuator provides dependency management and auto-configuration for Micrometer, an application metrics facade that supports numerous monitoring systems, including:

-
AppOptics

-
Atlas

-
Datadog

-
Dynatrace

-
Elastic

-
Ganglia

-
Graphite

-
Humio

-
Influx

-
JMX

-
KairosDB

-
New Relic

-
OTLP

-
Prometheus

-
Simple (in-memory)

-
Stackdriver

-
StatsD

To learn more about Micrometer’s capabilities, see its reference documentation, in particular the concepts section.

## Getting Started

Spring Boot auto-configures a composite MeterRegistry and adds a registry to the composite for each of the supported implementations that it finds on the classpath.
Having a dependency on micrometer-registry-{system} in your runtime classpath is enough for Spring Boot to configure the registry.

Most registries share common features.
For instance, you can disable a particular registry even if the Micrometer registry implementation is on the classpath.
The following example disables Datadog:

-
Properties

-
YAML

```
management.datadog.metrics.export.enabled=false
```

```
management:
  datadog:
    metrics:
      export:
        enabled: false
```

You can also disable all registries unless stated otherwise by the registry-specific property, as the following example shows:

-
Properties

-
YAML

```
management.defaults.metrics.export.enabled=false
```

```
management:
  defaults:
    metrics:
      export:
        enabled: false
```

Spring Boot also adds any auto-configured registries to the global static composite registry on the Metrics class, unless you explicitly tell it not to:

-
Properties

-
YAML

```
management.metrics.use-global-registry=false
```

```
management:
  metrics:
    use-global-registry: false
```

You can register any number of MeterRegistryCustomizer beans to further configure the registry, such as applying common tags, before any meters are registered with the registry:

-
Java

-
Kotlin

```
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyMeterRegistryConfiguration {

	@Bean
	public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
		return (registry) -> registry.config().commonTags("region", "us-east-1");
	}

}
```

```
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MyMeterRegistryConfiguration {

	@Bean
	fun metricsCommonTags(): MeterRegistryCustomizer<MeterRegistry> {
		return MeterRegistryCustomizer { registry ->
			registry.config().commonTags("region", "us-east-1")
		}
	}

}
```

You can apply customizations to particular registry implementations by being more specific about the generic type:

-
Java

-
Kotlin

```
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.graphite.GraphiteMeterRegistry;

import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyMeterRegistryConfiguration {

	@Bean
	public MeterRegistryCustomizer<GraphiteMeterRegistry> graphiteMetricsNamingConvention() {
		return (registry) -> registry.config().namingConvention(this::name);
	}

	private String name(String name, Meter.Type type, String baseUnit) {
		return ...
	}

}
```

```
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.NamingConvention
import io.micrometer.graphite.GraphiteMeterRegistry
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MyMeterRegistryConfiguration {

	@Bean
	fun graphiteMetricsNamingConvention(): MeterRegistryCustomizer<GraphiteMeterRegistry> {
		return MeterRegistryCustomizer { registry: GraphiteMeterRegistry ->
			registry.config().namingConvention(this::name)
		}
	}

	private fun name(name: String, type: Meter.Type, baseUnit: String?): String {
		return  ...
	}

}
```

Spring Boot also configures built-in instrumentation that you can control through configuration or dedicated annotation markers.

## Supported Monitoring Systems

This section briefly describes each of the supported monitoring systems.

### AppOptics

By default, the AppOptics registry periodically pushes metrics to api.appoptics.com/v1/measurements.
To export metrics to SaaS AppOptics, your API token must be provided:

-
Properties

-
YAML

```
management.appoptics.metrics.export.api-token=YOUR_TOKEN
```

```
management:
  appoptics:
    metrics:
      export:
        api-token: "YOUR_TOKEN"
```

### Atlas

By default, metrics are exported to Atlas running on your local machine.
You can provide the location of the Atlas server:

-
Properties

-
YAML

```
management.atlas.metrics.export.uri=https://atlas.example.com:7101/api/v1/publish
```

```
management:
  atlas:
    metrics:
      export:
        uri: "https://atlas.example.com:7101/api/v1/publish"
```

### Datadog

A Datadog registry periodically pushes metrics to datadoghq.
To export metrics to Datadog, you must provide your API key:

-
Properties

-
YAML

```
management.datadog.metrics.export.api-key=YOUR_KEY
```

```
management:
  datadog:
    metrics:
      export:
        api-key: "YOUR_KEY"
```

If you additionally provide an application key (optional), then metadata such as meter descriptions, types, and base units will also be exported:

-
Properties

-
YAML

```
management.datadog.metrics.export.api-key=YOUR_API_KEY
management.datadog.metrics.export.application-key=YOUR_APPLICATION_KEY
```

```
management:
  datadog:
    metrics:
      export:
        api-key: "YOUR_API_KEY"
        application-key: "YOUR_APPLICATION_KEY"
```

By default, metrics are sent to the Datadog US site (api.datadoghq.com).
If your Datadog project is hosted on one of the other sites, or you need to send metrics through a proxy, configure the URI accordingly:

-
Properties

-
YAML

```
management.datadog.metrics.export.uri=https://api.datadoghq.eu
```

```
management:
  datadog:
    metrics:
      export:
        uri: "https://api.datadoghq.eu"
```

You can also change the interval at which metrics are sent to Datadog:

-
Properties

-
YAML

```
management.datadog.metrics.export.step=30s
```

```
management:
  datadog:
    metrics:
      export:
        step: "30s"
```

### Dynatrace

Dynatrace offers two metrics ingest APIs, both of which are implemented for Micrometer.
You can find the Dynatrace documentation on Micrometer metrics ingest here.

Configuration properties in the v1 namespace apply only when exporting to the Timeseries v1 API.
Support for the V1 API is deprecated.

Configuration properties in the v2 namespace apply only when exporting to the Metrics v2 API.

Note that this integration can export only to either the v1 or v2 version of the API at a time, with v2 being strongly recommended due to the deprecation of the v1 API.
If the device-id (required for v1 but not used in v2) is set in the v1 namespace, metrics are exported to the v1 endpoint.
Otherwise, v2 is assumed.

#### v2 API

You can use the v2 API in two ways.

##### Auto-configuration

Dynatrace auto-configuration is available for hosts that are monitored by the OneAgent or by the Dynatrace Operator for Kubernetes.

Local OneAgent: If a OneAgent is running on the host, metrics are automatically exported to the local OneAgent ingest endpoint.
The ingest endpoint forwards the metrics to the Dynatrace backend.

Dynatrace Kubernetes Operator: When running in Kubernetes with the Dynatrace Operator installed, the registry will automatically pick up your endpoint URI and API token from the operator instead.

This is the default behavior and requires no special setup beyond a dependency on io.micrometer:micrometer-registry-dynatrace.

##### Manual Configuration

If no auto-configuration is available, the endpoint of the Metrics v2 API and an API token are required.
The API token must have the “Ingest metrics” (metrics.ingest) permission set.
We recommend limiting the scope of the token to this one permission.
You must ensure that the endpoint URI contains the path (for example, /api/v2/metrics/ingest):

The URL of the Metrics API v2 ingest endpoint is different according to your deployment option:

-
SaaS: https://{your-environment-id}.live.dynatrace.com/api/v2/metrics/ingest

-
Managed deployments: https://{your-domain}/e/{your-environment-id}/api/v2/metrics/ingest

The example below configures metrics export using the example environment id:

-
Properties

-
YAML

```
management.dynatrace.metrics.export.uri=https://example.live.dynatrace.com/api/v2/metrics/ingest
management.dynatrace.metrics.export.api-token=YOUR_TOKEN
```

```
management:
  dynatrace:
    metrics:
      export:
        uri: "https://example.live.dynatrace.com/api/v2/metrics/ingest"
        api-token: "YOUR_TOKEN"
```

When using the Dynatrace v2 API, the following optional features are available (more details can be found in the Dynatrace documentation):

-
Metric key prefix: Sets a prefix that is prepended to all exported metric keys.

-
Enrich with Dynatrace metadata: If a OneAgent or Dynatrace operator is running, enrich metrics with additional metadata (for example, about the host, process, or pod).

-
Default dimensions: Specify key-value pairs that are added to all exported metrics.
If tags with the same key are specified with Micrometer, they overwrite the default dimensions.

-
Use Dynatrace Summary instruments: In some cases the Micrometer Dynatrace registry created metrics that were rejected.
In Micrometer 1.9.x, this was fixed by introducing Dynatrace-specific summary instruments.
Setting this toggle to false forces Micrometer to fall back to the behavior that was the default before 1.9.x.
It should only be used when encountering problems while migrating from Micrometer 1.8.x to 1.9.x.

-
Export meter metadata: Starting from Micrometer 1.12.0, the Dynatrace exporter will also export meter metadata, such as unit and description by default.
Use the export-meter-metadata toggle to turn this feature off.

It is possible to not specify a URI and API token, as shown in the following example.
In this scenario, the automatically configured endpoint is used:

-
Properties

-
YAML

```
management.dynatrace.metrics.export.v2.metric-key-prefix=your.key.prefix
management.dynatrace.metrics.export.v2.enrich-with-dynatrace-metadata=true
management.dynatrace.metrics.export.v2.default-dimensions.key1=value1
management.dynatrace.metrics.export.v2.default-dimensions.key2=value2
management.dynatrace.metrics.export.v2.use-dynatrace-summary-instruments=true
management.dynatrace.metrics.export.v2.export-meter-metadata=true
```

```
management:
  dynatrace:
    metrics:
      export:
        # Specify uri and api-token here if not using the local OneAgent endpoint.
        v2:
          metric-key-prefix: "your.key.prefix"
          enrich-with-dynatrace-metadata: true
          default-dimensions:
            key1: "value1"
            key2: "value2"
          use-dynatrace-summary-instruments: true # (default: true)
          export-meter-metadata: true             # (default: true)
```

#### v1 API (Deprecated)

The Dynatrace v1 API metrics registry pushes metrics to the configured URI periodically by using the Timeseries v1 API.
For backwards-compatibility with existing setups, when device-id is set (required for v1, but not used in v2), metrics are exported to the Timeseries v1 endpoint.
To export metrics to Dynatrace, your API token, device ID, and URI must be provided:

-
Properties

-
YAML

```
management.dynatrace.metrics.export.uri=https://{your-environment-id}.live.dynatrace.com
management.dynatrace.metrics.export.api-token=YOUR_TOKEN
management.dynatrace.metrics.export.v1.device-id=YOUR_DEVICE_ID
```

```
management:
  dynatrace:
    metrics:
      export:
        uri: "https://{your-environment-id}.live.dynatrace.com"
        api-token: "YOUR_TOKEN"
        v1:
          device-id: "YOUR_DEVICE_ID"
```

For the v1 API, you must specify the base environment URI without a path, as the v1 endpoint path is added automatically.

#### Version-independent Settings

In addition to the API endpoint and token, you can also change the interval at which metrics are sent to Dynatrace.
The default export interval is 60s.
The following example sets the export interval to 30 seconds:

-
Properties

-
YAML

```
management.dynatrace.metrics.export.step=30s
```

```
management:
  dynatrace:
    metrics:
      export:
        step: "30s"
```

You can find more information on how to set up the Dynatrace exporter for Micrometer in the Micrometer documentation and the Dynatrace documentation.

### Elastic

By default, metrics are exported to Elastic running on your local machine.
You can provide the location of the Elastic server to use by using the following property:

-
Properties

-
YAML

```
management.elastic.metrics.export.host=https://elastic.example.com:8086
```

```
management:
  elastic:
    metrics:
      export:
        host: "https://elastic.example.com:8086"
```

### Ganglia

By default, metrics are exported to Ganglia running on your local machine.
You can provide the Ganglia server host and port, as the following example shows:

-
Properties

-
YAML

```
management.ganglia.metrics.export.host=ganglia.example.com
management.ganglia.metrics.export.port=9649
```

```
management:
  ganglia:
    metrics:
      export:
        host: "ganglia.example.com"
        port: 9649
```

### Graphite

By default, metrics are exported to Graphite running on your local machine.
You can provide the Graphite server host and port, as the following example shows:

-
Properties

-
YAML

```
management.graphite.metrics.export.host=graphite.example.com
management.graphite.metrics.export.port=9004
```

```
management:
  graphite:
    metrics:
      export:
         host: "graphite.example.com"
         port: 9004
```

Micrometer provides a default HierarchicalNameMapper that governs how a dimensional meter ID is mapped to flat hierarchical names.

To take control over this behavior, define your GraphiteMeterRegistry and supply your own HierarchicalNameMapper.
Auto-configured GraphiteConfig and Clock beans are provided unless you define your own:

-
Java

-
Kotlin

```
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;
import io.micrometer.graphite.GraphiteConfig;
import io.micrometer.graphite.GraphiteMeterRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyGraphiteConfiguration {

	@Bean
	public GraphiteMeterRegistry graphiteMeterRegistry(GraphiteConfig config, Clock clock) {
		return new GraphiteMeterRegistry(config, clock, this::toHierarchicalName);
	}

	private String toHierarchicalName(Meter.Id id, NamingConvention convention) {
		return ...
	}

}
```

```
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.NamingConvention
import io.micrometer.core.instrument.util.HierarchicalNameMapper
import io.micrometer.graphite.GraphiteConfig
import io.micrometer.graphite.GraphiteMeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MyGraphiteConfiguration {

	@Bean
	fun graphiteMeterRegistry(config: GraphiteConfig, clock: Clock): GraphiteMeterRegistry {
		return GraphiteMeterRegistry(config, clock, this::toHierarchicalName)
	}
	private fun toHierarchicalName(id: Meter.Id, convention: NamingConvention): String {
		return  ...
	}

}
```

### Humio

By default, the Humio registry periodically pushes metrics to cloud.humio.com.
To export metrics to SaaS Humio, you must provide your API token:

-
Properties

-
YAML

```
management.humio.metrics.export.api-token=YOUR_TOKEN
```

```
management:
  humio:
    metrics:
      export:
        api-token: "YOUR_TOKEN"
```

You should also configure one or more tags to identify the data source to which metrics are pushed:

-
Properties

-
YAML

```
management.humio.metrics.export.tags.alpha=a
management.humio.metrics.export.tags.bravo=b
```

```
management:
  humio:
    metrics:
      export:
        tags:
          alpha: "a"
          bravo: "b"
```

### Influx

By default, metrics are exported to an Influx v1 instance running on your local machine with the default configuration.
To export metrics to InfluxDB v2, configure the org, bucket, and authentication token for writing metrics.
You can provide the location of the Influx server to use by using:

-
Properties

-
YAML

```
management.influx.metrics.export.uri=https://influx.example.com:8086
```

```
management:
  influx:
    metrics:
      export:
        uri: "https://influx.example.com:8086"
```

### JMX

Micrometer provides a hierarchical mapping to JMX, primarily as a cheap and portable way to view metrics locally.
By default, metrics are exported to the metrics JMX domain.
You can provide the domain to use by using:

-
Properties

-
YAML

```
management.jmx.metrics.export.domain=com.example.app.metrics
```

```
management:
  jmx:
    metrics:
      export:
        domain: "com.example.app.metrics"
```

Micrometer provides a default HierarchicalNameMapper that governs how a dimensional meter ID is mapped to flat hierarchical names.

To take control over this behavior, define your JmxMeterRegistry and supply your own HierarchicalNameMapper.
Auto-configured JmxConfig and Clock beans are provided unless you define your own:

-
Java

-
Kotlin

```
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;
import io.micrometer.jmx.JmxConfig;
import io.micrometer.jmx.JmxMeterRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyJmxConfiguration {

	@Bean
	public JmxMeterRegistry jmxMeterRegistry(JmxConfig config, Clock clock) {
		return new JmxMeterRegistry(config, clock, this::toHierarchicalName);
	}

	private String toHierarchicalName(Meter.Id id, NamingConvention convention) {
		return ...
	}

}
```

```
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.NamingConvention
import io.micrometer.core.instrument.util.HierarchicalNameMapper
import io.micrometer.jmx.JmxConfig
import io.micrometer.jmx.JmxMeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MyJmxConfiguration {

	@Bean
	fun jmxMeterRegistry(config: JmxConfig, clock: Clock): JmxMeterRegistry {
		return JmxMeterRegistry(config, clock, this::toHierarchicalName)
	}

	private fun toHierarchicalName(id: Meter.Id, convention: NamingConvention): String {
		return  ...
	}

}
```

### KairosDB

By default, metrics are exported to KairosDB running on your local machine.
You can provide the location of the KairosDB server to use by using:

-
Properties

-
YAML

```
management.kairos.metrics.export.uri=https://kairosdb.example.com:8080/api/v1/datapoints
```

```
management:
  kairos:
    metrics:
      export:
        uri: "https://kairosdb.example.com:8080/api/v1/datapoints"
```

### New Relic

A New Relic registry periodically pushes metrics to New Relic.
To export metrics to New Relic, you must provide your API key and account ID:

-
Properties

-
YAML

```
management.newrelic.metrics.export.api-key=YOUR_KEY
management.newrelic.metrics.export.account-id=YOUR_ACCOUNT_ID
```

```
management:
  newrelic:
    metrics:
      export:
        api-key: "YOUR_KEY"
        account-id: "YOUR_ACCOUNT_ID"
```

You can also change the interval at which metrics are sent to New Relic:

-
Properties

-
YAML

```
management.newrelic.metrics.export.step=30s
```

```
management:
  newrelic:
    metrics:
      export:
        step: "30s"
```

By default, metrics are published through REST calls, but you can also use the Java Agent API if you have it on the classpath:

-
Properties

-
YAML

```
management.newrelic.metrics.export.client-provider-type=insights-agent
```

```
management:
  newrelic:
    metrics:
      export:
        client-provider-type: "insights-agent"
```

Finally, you can take full control by defining your own NewRelicClientProvider bean.

### OTLP

By default, metrics are exported over the OpenTelemetry protocol (OTLP) to a consumer running on your local machine.
To export to another location, provide the location of the OTLP metrics endpoint using management.otlp.metrics.export.url:

-
Properties

-
YAML

```
management.otlp.metrics.export.url=https://otlp.example.com:4318/v1/metrics
```

```
management:
  otlp:
    metrics:
      export:
        url: "https://otlp.example.com:4318/v1/metrics"
```

Custom headers, for example for authentication, can also be provided using management.otlp.metrics.export.headers.* properties.

If an OtlpMetricsSender bean is available, it will be configured on the OtlpMeterRegistry that Spring Boot auto-configures.

OTLP Exemplars are also supported.
To enable this feature, an ExemplarContextProvider bean should be present.
If you use Micrometer Tracing, this will be auto-configured for you.
By default, only sampled traces are included as exemplars.
You can control this behavior using the management.tracing.exemplars.include property.

### Prometheus

Prometheus expects to scrape or poll individual application instances for metrics.
Spring Boot provides an actuator endpoint at /actuator/prometheus to present a Prometheus scrape with the appropriate format.

By default, the endpoint is not available and must be exposed. See exposing endpoints for more details.

The following example scrape_config adds to prometheus.yml:

```
scrape_configs:
- job_name: "spring"
  metrics_path: "/actuator/prometheus"
  static_configs:
  - targets: ["HOST:PORT"]
```

Prometheus Exemplars are also supported.
To enable this feature, a SpanContext bean should be present.
If you’re using the deprecated Prometheus simpleclient support and want to enable that feature, a SpanContextSupplier bean should be present.
If you use Micrometer Tracing, this will be auto-configured for you, but you can always create your own if you want.
By default, only sampled traces are included as exemplars.
You can control this behavior using the management.tracing.exemplars.include property.
The value all is not supported with Prometheus.
Please check the Prometheus Docs, since this feature needs to be explicitly enabled on Prometheus' side, and it is only supported using the OpenMetrics format.

For ephemeral or batch jobs that may not exist long enough to be scraped, you can use Prometheus Pushgateway support to expose the metrics to Prometheus.

To enable Prometheus Pushgateway support, add the following dependency to your project:

```
<dependency>
	<groupId>io.prometheus</groupId>
	<artifactId>prometheus-metrics-exporter-pushgateway</artifactId>
</dependency>
```

When the Prometheus Pushgateway dependency is present on the classpath and the management.prometheus.metrics.export.pushgateway.enabled property is set to true, a PrometheusPushGatewayManager bean is auto-configured.
This manages the pushing of metrics to a Prometheus Pushgateway.

You can tune the PrometheusPushGatewayManager by using properties under management.prometheus.metrics.export.pushgateway.
For advanced configuration, you can also provide your own PrometheusPushGatewayManager bean.

### Simple

Micrometer ships with a simple, in-memory backend that is automatically used as a fallback if no other registry is configured.
This lets you see what metrics are collected in the metrics endpoint.

The in-memory backend disables itself as soon as you use any other available backend.
You can also disable it explicitly:

-
Properties

-
YAML

```
management.simple.metrics.export.enabled=false
```

```
management:
  simple:
    metrics:
      export:
        enabled: false
```

### Stackdriver

The Stackdriver registry periodically pushes metrics to Stackdriver.
To export metrics to SaaS Stackdriver, you must provide your Google Cloud project ID:

-
Properties

-
YAML

```
management.stackdriver.metrics.export.project-id=my-project
```

```
management:
  stackdriver:
    metrics:
      export:
        project-id: "my-project"
```

You can also change the interval at which metrics are sent to Stackdriver:

-
Properties

-
YAML

```
management.stackdriver.metrics.export.step=30s
```

```
management:
  stackdriver:
    metrics:
      export:
        step: "30s"
```

### StatsD

The StatsD registry eagerly pushes metrics over UDP to a StatsD agent.
By default, metrics are exported to a StatsD agent running on your local machine.
You can provide the StatsD agent host, port, and protocol to use by using:

-
Properties

-
YAML

```
management.statsd.metrics.export.host=statsd.example.com
management.statsd.metrics.export.port=9125
management.statsd.metrics.export.protocol=udp
```

```
management:
  statsd:
    metrics:
      export:
        host: "statsd.example.com"
        port: 9125
        protocol: "udp"
```

You can also change the StatsD line protocol to use (it defaults to Datadog):

-
Properties

-
YAML

```
management.statsd.metrics.export.flavor=etsy
```

```
management:
  statsd:
    metrics:
      export:
        flavor: "etsy"
```

## Supported Metrics and Meters

Spring Boot provides automatic meter registration for a wide variety of technologies.
In most situations, the defaults provide sensible metrics that can be published to any of the supported monitoring systems.

### JVM Metrics

Auto-configuration enables JVM Metrics by using core Micrometer classes.
JVM metrics are published under the jvm. meter name.

The following JVM metrics are provided:

-
Various memory and buffer pool details

-
Statistics related to garbage collection

-
Thread utilization

-
Virtual threads statistics (for this, io.micrometer:micrometer-java21 has to be on the classpath)

-
The number of classes loaded and unloaded

-
JVM version information

-
JIT compilation time

### System Metrics

Auto-configuration enables system metrics by using core Micrometer classes.
System metrics are published under the system., process., and disk. meter names.

The following system metrics are provided:

-
CPU metrics

-
File descriptor metrics

-
Uptime metrics (both the amount of time the application has been running and a fixed gauge of the absolute start time)

-
Disk space available

### Application Startup Metrics

Auto-configuration exposes application startup time metrics:

-
application.started.time: time taken to start the application.

-
application.ready.time: time taken for the application to be ready to service requests.

Metrics are tagged by the fully qualified name of the application class.

### Logger Metrics

Auto-configuration enables the event metrics for both Logback and Log4J2.
The details are published under the log4j2.events. or logback.events. meter names.

### Task Execution and Scheduling Metrics

Auto-configuration enables the instrumentation of all available ThreadPoolTaskExecutor and ThreadPoolTaskScheduler beans, as long as the underling ThreadPoolExecutor is available.
Metrics are tagged by the name of the executor, which is derived from the bean name.

### JMS Metrics

Auto-configuration enables the instrumentation of all available JmsTemplate beans and @JmsListener annotated methods.
This will produce "jms.message.publish" and "jms.message.process" metrics respectively.
See the Spring Framework reference documentation for more information on produced observations.

JmsClient and JmsMessagingTemplate that uses a JmsTemplate bean are also instrumented.

### Spring MVC Metrics

Auto-configuration enables the instrumentation of all requests handled by Spring MVC controllers and functional handlers.
By default, metrics are generated with the name, http.server.requests.
You can customize the name by setting the management.observations.http.server.requests.name property.

See the Spring Framework reference documentation for more information on produced observations.

To add to the default tags, provide a @Bean that extends DefaultServerRequestObservationConvention from the org.springframework.http.server.observation package.
To replace the default tags, provide a @Bean that implements ServerRequestObservationConvention.

In some cases, exceptions handled in web controllers are not recorded as request metrics tags.
Applications can opt in and record exceptions by setting handled exceptions as request attributes.

By default, all requests are handled.
To customize the filter, provide a @Bean that implements FilterRegistrationBean<ServerHttpObservationFilter>.

### Spring WebFlux Metrics

Auto-configuration enables the instrumentation of all requests handled by Spring WebFlux controllers and functional handlers.
By default, metrics are generated with the name, http.server.requests.
You can customize the name by setting the management.observations.http.server.requests.name property.

See the Spring Framework reference documentation for more information on produced observations.

To add to the default tags, provide a @Bean that extends DefaultServerRequestObservationConvention from the org.springframework.http.server.reactive.observation package.
To replace the default tags, provide a @Bean that implements ServerRequestObservationConvention.

In some cases, exceptions handled in controllers and handler functions are not recorded as request metrics tags.
Applications can opt in and record exceptions by setting handled exceptions as request attributes.

### Jersey Server Metrics

Auto-configuration enables the instrumentation of all requests handled by the Jersey JAX-RS implementation.
By default, metrics are generated with the name, http.server.requests.
You can customize the name by setting the management.observations.http.server.requests.name property.

By default, Jersey server metrics are tagged with the following information:

Tag
Description

exception

The simple class name of any exception that was thrown while handling the request.

method

The request’s method (for example, GET or POST)

outcome

The request’s outcome, based on the status code of the response.
  1xx is INFORMATIONAL, 2xx is SUCCESS, 3xx is REDIRECTION, 4xx is CLIENT_ERROR, and 5xx is SERVER_ERROR

status

The response’s HTTP status code (for example, 200 or 500)

uri

The request’s URI template prior to variable substitution, if possible (for example, /api/person/{id})

To customize the tags, provide a @Bean that implements JerseyObservationConvention.

### SSL Bundle Metrics

Spring Boot Actuator publishes expiry metrics about SSL bundles.
The metric ssl.chain.expiry gauges the expiry date of each certificate chain in key stores and trust stores in seconds.
This number will be negative if the chain has already expired.
This metric is tagged with the following information:

Tag
Description

bundle

The name of the bundle which contains the certificate chain

certificate

The serial number (in hex format) of the certificate which is the soonest to expire in the chain

chain

The name of the certificate chain.

source

Whether the certificate chain comes from the key store (keystore) or trust store (truststore)

### HTTP Client Metrics

Spring Boot Actuator manages the instrumentation of RestTemplate, WebClient and RestClient.
For that, you have to inject the auto-configured builder and use it to create instances:

-
RestTemplateBuilder for RestTemplate

-
WebClient.Builder for WebClient

-
RestClient.Builder for RestClient

You can also manually apply the customizers responsible for this instrumentation, namely ObservationRestTemplateCustomizer, ObservationWebClientCustomizer and ObservationRestClientCustomizer.

By default, metrics are generated with the name, http.client.requests.
You can customize the name by setting the management.observations.http.client.requests.name property.

See the Spring Framework reference documentation for more information on produced observations.

To customize the tags when using RestTemplate or RestClient, provide a @Bean that implements ClientRequestObservationConvention from the org.springframework.http.client.observation package.
To customize the tags when using WebClient, provide a @Bean that implements ClientRequestObservationConvention from the org.springframework.web.reactive.function.client package.

### Tomcat Metrics

Auto-configuration enables the instrumentation of Tomcat only when an MBean Registry is enabled.
By default, the MBean registry is disabled, but you can enable it by setting server.tomcat.mbeanregistry.enabled to true.

Tomcat metrics are published under the tomcat. meter name.

### Cache Metrics

Auto-configuration enables the instrumentation of all available Cache instances on startup, with metrics prefixed with cache.
Cache instrumentation is standardized for a basic set of metrics.
Additional, cache-specific metrics are also available.

The following cache libraries are supported:

-
Cache2k

-
Caffeine

-
Hazelcast

-
Any compliant JCache (JSR-107) implementation

-
Redis

Metrics should be enabled for the auto-configuration to pick them up.
Refer to the documentation of the cache library you are using for more details.

Metrics are tagged by the name of the cache and by the name of the CacheManager, which is derived from the bean name.

Only caches that are configured on startup are bound to the registry.
For caches not defined in the cache’s configuration, such as caches created on the fly or programmatically after the startup phase, an explicit registration is required.
A CacheMetricsRegistrar bean is made available to make that process easier.

### Spring Batch Metrics

See the Spring Batch reference documentation.

### Spring GraphQL Metrics

See the Spring GraphQL reference documentation.

### DataSource Metrics

Auto-configuration enables the instrumentation of all available DataSource objects with metrics prefixed with jdbc.connections.
Data source instrumentation results in gauges that represent the currently active, idle, maximum allowed, and minimum allowed connections in the pool.

Metrics are also tagged by the name of the DataSource computed based on the bean name.

By default, Spring Boot provides metadata for all supported data sources.
You can add additional DataSourcePoolMetadataProvider beans if your favorite data source is not supported.
See DataSourcePoolMetadataProvidersConfiguration for examples.

Also, Hikari-specific metrics are exposed with a hikaricp prefix.
Each metric is tagged by the name of the pool (you can control it with spring.datasource.name).

### Hibernate Metrics

If org.hibernate.orm:hibernate-micrometer is on the classpath, all available Hibernate EntityManagerFactory instances that have statistics enabled are instrumented with a metric named hibernate.

Metrics are also tagged by the name of the EntityManagerFactory, which is derived from the bean name.

To enable statistics, the standard JPA property hibernate.generate_statistics must be set to true.
You can enable that on the auto-configured EntityManagerFactory:

-
Properties

-
YAML

```
spring.jpa.properties[hibernate.generate_statistics]=true
```

```
spring:
  jpa:
    properties:
      "[hibernate.generate_statistics]": true
```

### Spring Data Repository Metrics

Auto-configuration enables the instrumentation of all Spring Data Repository method invocations.
By default, metrics are generated with the name, spring.data.repository.invocations.
You can customize the name by setting the management.metrics.data.repository.metric-name property.

The @Timed annotation from the io.micrometer.core.annotation package is supported on Repository interfaces and methods.
If you do not want to record metrics for all Repository invocations, you can set management.metrics.data.repository.autotime.enabled to false and exclusively use @Timed annotations instead.

A @Timed annotation with longTask = true enables a long task timer for the method.
Long task timers require a separate metric name and can be stacked with a short task timer.

By default, repository invocation related metrics are tagged with the following information:

Tag
Description

repository

The simple class name of the source Repository.

method

The name of the Repository method that was invoked.

state

The result state (SUCCESS, ERROR, CANCELED, or RUNNING).

exception

The simple class name of any exception that was thrown from the invocation.

To replace the default tags, provide a @Bean that implements RepositoryTagsProvider.

### RabbitMQ Metrics

Auto-configuration enables the instrumentation of all available RabbitMQ connection factories with a metric named rabbitmq.

### Spring Integration Metrics

Spring Integration automatically provides Micrometer support whenever a MeterRegistry bean is available.
Metrics are published under the spring.integration. meter name.

### Kafka Metrics

Auto-configuration registers a MicrometerConsumerListener and MicrometerProducerListener for the auto-configured consumer factory and producer factory, respectively.
It also registers a KafkaStreamsMicrometerListener for StreamsBuilderFactoryBean.
For more detail, see the Micrometer Native Metrics section of the Spring Kafka documentation.

### MongoDB Metrics

This section briefly describes the available metrics for MongoDB.

#### MongoDB Command Metrics

Auto-configuration registers a MongoMetricsCommandListener with the auto-configured MongoClient.

A timer metric named mongodb.driver.commands is created for each command issued to the underlying MongoDB driver.
Each metric is tagged with the following information by default:

Tag
Description

command

The name of the command issued.

cluster.id

The identifier of the cluster to which the command was sent.

server.address

The address of the server to which the command was sent.

status

The outcome of the command (SUCCESS or FAILED).

To replace the default metric tags, define a MongoCommandTagsProvider bean, as the following example shows:

-
Java

-
Kotlin

```
import io.micrometer.core.instrument.binder.mongodb.MongoCommandTagsProvider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyCommandTagsProviderConfiguration {

	@Bean
	public MongoCommandTagsProvider customCommandTagsProvider() {
		return new CustomCommandTagsProvider();
	}

}
```

```
import io.micrometer.core.instrument.binder.mongodb.MongoCommandTagsProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MyCommandTagsProviderConfiguration {

	@Bean
	fun customCommandTagsProvider(): MongoCommandTagsProvider? {
		return CustomCommandTagsProvider()
	}

}
```

To disable the auto-configured command metrics, set the following property:

-
Properties

-
YAML

```
management.metrics.mongodb.command.enabled=false
```

```
management:
  metrics:
    mongodb:
      command:
        enabled: false
```

#### MongoDB Connection Pool Metrics

Auto-configuration registers a MongoMetricsConnectionPoolListener with the auto-configured MongoClient.

The following gauge metrics are created for the connection pool:

-
mongodb.driver.pool.size reports the current size of the connection pool, including idle and in-use members.

-
mongodb.driver.pool.checkedout reports the count of connections that are currently in use.

-
mongodb.driver.pool.waitqueuesize reports the current size of the wait queue for a connection from the pool.

Each metric is tagged with the following information by default:

Tag
Description

cluster.id

The identifier of the cluster to which the connection pool corresponds.

server.address

The address of the server to which the connection pool corresponds.

To replace the default metric tags, define a MongoConnectionPoolTagsProvider bean:

-
Java

-
Kotlin

```
import io.micrometer.core.instrument.binder.mongodb.MongoConnectionPoolTagsProvider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyConnectionPoolTagsProviderConfiguration {

	@Bean
	public MongoConnectionPoolTagsProvider customConnectionPoolTagsProvider() {
		return new CustomConnectionPoolTagsProvider();
	}

}
```

```
import io.micrometer.core.instrument.binder.mongodb.MongoConnectionPoolTagsProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MyConnectionPoolTagsProviderConfiguration {

	@Bean
	fun customConnectionPoolTagsProvider(): MongoConnectionPoolTagsProvider {
		return CustomConnectionPoolTagsProvider()
	}

}
```

To disable the auto-configured connection pool metrics, set the following property:

-
Properties

-
YAML

```
management.metrics.mongodb.connectionpool.enabled=false
```

```
management:
  metrics:
    mongodb:
      connectionpool:
        enabled: false
```

### Neo4j Metrics

Auto-configuration registers a MicrometerObservationProvider for the auto-configured Driver.

To override this behavior, you can register a ConfigBuilderCustomizer bean with an order higher than zero.

### Jetty Metrics

Auto-configuration binds metrics for Jetty’s ThreadPool by using Micrometer’s JettyServerThreadPoolMetrics.
Metrics for Jetty’s Connector instances are bound by using Micrometer’s JettyConnectionMetrics and, when server.ssl.enabled is set to true, Micrometer’s JettySslHandshakeMetrics.

### Redis Metrics

Auto-configuration registers a MicrometerTracing for the auto-configured LettuceConnectionFactory.
For more detail, see the Observability section of the Lettuce documentation.

## Registering Custom Metrics

To register custom metrics, inject MeterRegistry into your component:

-
Java

-
Kotlin

```
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import org.springframework.stereotype.Component;

@Component
public class MyBean {

	private final Dictionary dictionary;

	public MyBean(MeterRegistry registry) {
		this.dictionary = Dictionary.load();
		registry.gauge("dictionary.size", Tags.empty(), this.dictionary.getWords().size());
	}

}
```

```
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component

@Component
class MyBean(registry: MeterRegistry) {

	private val dictionary: Dictionary

	init {
		dictionary = Dictionary.load()
		registry.gauge("dictionary.size", Tags.empty(), dictionary.words.size)
	}

}
```

If your metrics depend on other beans, we recommend that you use a MeterBinder to register them:

-
Java

-
Kotlin

```
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.springframework.context.annotation.Bean;

public class MyMeterBinderConfiguration {

	@Bean
	public MeterBinder queueSize(Queue queue) {
		return (registry) -> Gauge.builder("queueSize", queue::size).register(registry);
	}

}
```

```
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.context.annotation.Bean

class MyMeterBinderConfiguration {

	@Bean
	fun queueSize(queue: Queue): MeterBinder {
		return MeterBinder { registry ->
			Gauge.builder("queueSize", queue::size).register(registry)
		}
	}

}
```

Using a MeterBinder ensures that the correct dependency relationships are set up and that the bean is available when the metric’s value is retrieved.
A MeterBinder implementation can also be useful if you find that you repeatedly instrument a suite of metrics across components or applications.

By default, metrics from all MeterBinder beans are automatically bound to the Spring-managed MeterRegistry.

## Customizing Individual Metrics

If you need to apply customizations to specific Meter instances, you can use the MeterFilter interface.

For example, if you want to rename the mytag.region tag to mytag.area for all meter IDs beginning with com.example, you can do the following:

-
Java

-
Kotlin

```
import io.micrometer.core.instrument.config.MeterFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyMetricsFilterConfiguration {

	@Bean
	public MeterFilter renameRegionTagMeterFilter() {
		return MeterFilter.renameTag("com.example", "mytag.region", "mytag.area");
	}

}
```

```
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MyMetricsFilterConfiguration {

	@Bean
	fun renameRegionTagMeterFilter(): MeterFilter {
		return MeterFilter.renameTag("com.example", "mytag.region", "mytag.area")
	}

}
```

By default, all MeterFilter beans are automatically bound to the Spring-managed MeterRegistry.
Make sure to register your metrics by using the Spring-managed MeterRegistry and not any of the static methods on Metrics.
These use the global registry that is not Spring-managed.

### Common Tags

Common tags are generally used for dimensional drill-down on the operating environment, such as host, instance, region, stack, and others.
Commons tags are applied to all meters and can be configured, as the following example shows:

-
Properties

-
YAML

```
management.metrics.tags.region=us-east-1
management.metrics.tags.stack=prod
```

```
management:
  metrics:
    tags:
      region: "us-east-1"
      stack: "prod"
```

The preceding example adds region and stack tags to all meters with a value of us-east-1 and prod, respectively.

The order of common tags is important if you use Graphite.
As the order of common tags cannot be guaranteed by using this approach, Graphite users are advised to define a custom MeterFilter instead.

### Per-meter Properties

In addition to MeterFilter beans, you can apply a limited set of customization on a per-meter basis using properties.
Per-meter customizations are applied, using Spring Boot’s PropertiesMeterFilter, to any meter IDs that start with the given name.
The following example filters out any meters that have an ID starting with example.remote.

-
Properties

-
YAML

```
management.metrics.enable.example.remote=false
```

```
management:
  metrics:
    enable:
      example:
        remote: false
```

The following properties allow per-meter customization:

Table 1. Per-meter customizations

Property
Description

management.metrics.enable

Whether to accept meters with certain IDs.
  Meters that are not accepted are filtered from the MeterRegistry.

management.metrics.distribution.percentiles-histogram

Whether to publish a histogram suitable for computing aggregable (across dimension) percentile approximations.

management.metrics.distribution.minimum-expected-value, management.metrics.distribution.maximum-expected-value

Publish fewer histogram buckets by clamping the range of expected values.

management.metrics.distribution.percentiles

Publish percentile values computed in your application

management.metrics.distribution.expiry, management.metrics.distribution.buffer-length

Give greater weight to recent samples by accumulating them in ring buffers which rotate after a configurable expiry, with a
configurable buffer length.

management.metrics.distribution.slo

Publish a cumulative histogram with buckets defined by your service-level objectives.

For more details on the concepts behind percentiles-histogram, percentiles, and slo, see the Histograms and percentiles section of the Micrometer documentation.

## Metrics Endpoint

Spring Boot provides a metrics endpoint that you can use diagnostically to examine the metrics collected by an application.
The endpoint is not available by default and must be exposed.
See exposing endpoints for more details.

Navigating to /actuator/metrics displays a list of available meter names.
You can drill down to view information about a particular meter by providing its name as a selector — for example, /actuator/metrics/jvm.memory.max.

The name you use here should match the name used in the code, not the name after it has been naming-convention normalized for a monitoring system to which it is shipped.
In other words, if jvm.memory.max appears as jvm_memory_max in Prometheus because of its snake case naming convention, you should still use jvm.memory.max as the selector when inspecting the meter in the metrics endpoint.

You can also add any number of tag=KEY:VALUE query parameters to the end of the URL to dimensionally drill down on a meter — for example, /actuator/metrics/jvm.memory.max?tag=area:nonheap.

The reported measurements are the sum of the statistics of all meters that match the meter name and any tags that have been applied.
In the preceding example, the returned Value statistic is the sum of the maximum memory footprints of the “Code Cache”, “Compressed Class Space”, and “Metaspace” areas of the heap.
If you wanted to see only the maximum size for the “Metaspace”, you could add an additional tag=id:Metaspace — that is, /actuator/metrics/jvm.memory.max?tag=area:nonheap&tag=id:Metaspace.

## Integration with Micrometer Observation

A DefaultMeterObservationHandler is automatically registered on the ObservationRegistry, which creates metrics for every completed observation.