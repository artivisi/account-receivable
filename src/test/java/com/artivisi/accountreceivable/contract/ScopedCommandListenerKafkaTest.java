package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.config.ArContractProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scoped listener against a real broker. Every other contract test runs in OUTBOX_ONLY and never
 * starts a listener, which is how a dispatcher defect once reached production unseen; the part worth
 * proving here is the wiring — that the configured topics are subscribed and that the topic a
 * message arrived on is what reaches the handler, since that topic is the whole authorization.
 */
class ScopedCommandListenerKafkaTest {

    private static final String SCOPED = "housing-command";

    @Configuration
    @EnableKafka
    static class Broker {
        static final EmbeddedKafkaBroker BROKER = new EmbeddedKafkaKraftBroker(1, 1, SCOPED);

        static {
            BROKER.afterPropertiesSet();
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
            ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.getBrokersAsString(),
                    ConsumerConfig.GROUP_ID_CONFIG, "scoped-listener-test",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)));
            return factory;
        }

        @Bean
        ContractCommandHandler handler() {
            return mock(ContractCommandHandler.class);
        }

        @Bean
        ArContractProperties properties(org.springframework.core.env.Environment env) {
            return new ArContractProperties(ArContractProperties.Mode.KAFKA, "ar",
                    new ArContractProperties.Topics("invoice-command-v2", "debtor-command-v2", "invoice-event-v2", "payment-event-v2"),
                    36, 3, 30, 5000, env.getProperty("ar.contract.scoped-command-topics"));
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Broker.class, ScopedCommandTopics.class, ScopedCommandListener.class);

    @Test
    void subscribesTheScopedTopics_andHandsOverTheTopicItReadFrom() {
        runner.withPropertyValues("ar.contract.mode=KAFKA", "ar.contract.scoped-command-topics=" + SCOPED + "=91")
                .run(context -> {
                    assertThat(context).hasSingleBean(ScopedCommandListener.class);
                    context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers()
                            .forEach(c -> c.start());
                    KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                            "bootstrap.servers", Broker.BROKER.getBrokersAsString(),
                            "key.serializer", StringSerializer.class,
                            "value.serializer", StringSerializer.class)));
                    template.send(new ProducerRecord<>(SCOPED, "2600000001", "{\"type\":\"x\"}")).get();

                    ContractCommandHandler handler = context.getBean(ContractCommandHandler.class);
                    verify(handler, timeout(Duration.ofSeconds(30).toMillis())).handleScoped(SCOPED, "{\"type\":\"x\"}");
                    verify(handler, never()).handle(anyString(), anyString());
                    template.destroy();
                });
    }

    @Test
    void noScopedTopic_meansNoListener() {
        runner.withPropertyValues("ar.contract.mode=KAFKA", "ar.contract.scoped-command-topics=")
                .run(context -> assertThat(context).doesNotHaveBean(ScopedCommandListener.class));
    }

    @Test
    void contractNotOnKafka_meansNoListener() {
        runner.withPropertyValues("ar.contract.mode=OUTBOX_ONLY", "ar.contract.scoped-command-topics=" + SCOPED + "=91")
                .run(context -> assertThat(context).doesNotHaveBean(ScopedCommandListener.class));
    }
}
