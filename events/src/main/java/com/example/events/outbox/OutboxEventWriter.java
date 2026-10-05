package com.example.events.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes a Kafka event to the same database transaction as its state change. */
public class OutboxEventWriter {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void enqueue(String topic, String key, String deduplicationKey, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox events must be written inside the owning database transaction");
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO outbox_events (topic, message_key, deduplication_key, payload_type, payload_json)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (deduplication_key) DO NOTHING
                    """,
                    topic,
                    key,
                    deduplicationKey,
                    payload.getClass().getSimpleName(),
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize outbox payload " + payload.getClass().getSimpleName(), e);
        }
    }
}
