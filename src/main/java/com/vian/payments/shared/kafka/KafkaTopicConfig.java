package com.vian.payments.shared.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;

@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentEventsTopic(PaymentKafkaProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(properties.replicas())
                .build();
    }

    @Bean
    public NewTopic paymentEventsDltTopic(PaymentKafkaProperties properties) {
        return TopicBuilder.name(properties.dltTopic())
                .partitions(properties.partitions())
                .replicas(properties.replicas())
                .build();
    }
}
