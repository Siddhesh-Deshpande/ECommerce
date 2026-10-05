package com.example.events.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class OutboxConfiguration {
    @Bean
    public OutboxEventWriter outboxEventWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new OutboxEventWriter(jdbcTemplate, objectMapper);
    }

    @Bean
    @SuppressWarnings("unchecked")
    public OutboxPublisher outboxPublisher(JdbcTemplate jdbcTemplate,
                                           ObjectMapper objectMapper,
                                           KafkaTemplate<?, ?> kafkaTemplate,
                                           PlatformTransactionManager transactionManager) {
        return new OutboxPublisher(jdbcTemplate, objectMapper,
                (KafkaTemplate<Object, Object>) kafkaTemplate,
                new TransactionTemplate(transactionManager));
    }
}
