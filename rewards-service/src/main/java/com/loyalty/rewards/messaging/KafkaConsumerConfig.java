package com.loyalty.rewards.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failure handling for the consumer: retry a few times, then park the message
 * in a dead-letter topic (DLT) and move on.
 *
 * Without this, one bad message blocks its partition forever ("poison pill"),
 * because Kafka will not move past a message that was never acknowledged.
 */
@Configuration
public class KafkaConsumerConfig {

    /** Both topics are declared here too, so this service can start before order-service. */
    @Bean
    public NewTopic ordersPlacedTopic(@Value("${app.kafka.topics.orders-placed}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    /** Same partition count as the main topic: a failed record keeps its partition number. */
    @Bean
    public NewTopic ordersPlacedDltTopic(@Value("${app.kafka.topics.orders-placed-dlt}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    /**
     * Producer used only for the dead-letter topic. Two kinds of value can end up there:
     * - byte[]  : a message that could not even be parsed - forward the raw bytes untouched
     * - objects : a message that was parsed but failed in our code - write it back as JSON
     * DelegatingByTypeSerializer picks the right serializer by looking at the value's type.
     */
    @Bean
    public ProducerFactory<String, Object> dltProducerFactory(KafkaProperties properties,
                                                              KafkaConnectionDetails connectionDetails,
                                                              ObjectProvider<SslBundles> sslBundles) {
        // LinkedHashMap keeps the order: byte[] must be checked before the catch-all Object.
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        JsonSerializer<Object> json = new JsonSerializer<>();
        json.setAddTypeInfo(false);
        byType.put(Object.class, json);

        Map<String, Object> config = properties.buildProducerProperties(sslBundles.getIfAvailable());
        // Take the broker address from KafkaConnectionDetails, as Spring Boot's own Kafka beans
        // do. Normally it is just spring.kafka.bootstrap-servers, but in integration tests it is
        // the Testcontainers broker. Reading the property directly would make this producer
        // talk to a different Kafka than the consumer.
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getBootstrapServers());

        return new DefaultKafkaProducerFactory<>(
                config,
                new StringSerializer(),
                new DelegatingByTypeSerializer(byType, true));
    }

    @Bean
    public KafkaTemplate<String, Object> dltKafkaTemplate(ProducerFactory<String, Object> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }

    /**
     * Spring Boot plugs this error handler into every @KafkaListener automatically.
     *
     * When the listener throws:
     * 1. wait interval-ms and try the same message again, up to max-retries times
     *    (a database that is briefly down usually recovers within a few seconds);
     * 2. still failing -> the "recoverer" publishes the message to the DLT, with headers
     *    describing the exception, and the consumer continues with the next message.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, Object> dltKafkaTemplate,
            @Value("${app.kafka.topics.orders-placed-dlt}") String dltTopic,
            @Value("${app.kafka.retry.interval-ms}") long intervalMs,
            @Value("${app.kafka.retry.max-retries}") long maxRetries) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dltKafkaTemplate,
                (failedRecord, exception) -> new TopicPartition(dltTopic, failedRecord.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(intervalMs, maxRetries));

        // Retrying only helps for temporary problems. A message that is simply wrong
        // (missing field, unreadable JSON) goes straight to the DLT without retries.
        // (Unreadable JSON - DeserializationException - is already non-retryable by default.)
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
