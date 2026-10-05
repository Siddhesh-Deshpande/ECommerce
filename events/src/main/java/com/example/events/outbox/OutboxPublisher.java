package com.example.events.outbox;

import com.example.events.dtos.CancelOrder;
import com.example.events.dtos.ChargeMoney;
import com.example.events.dtos.CreateOrder;
import com.example.events.dtos.DeductItems;
import com.example.events.dtos.FinalizeOrder;
import com.example.events.dtos.InventoryResponse;
import com.example.events.dtos.OrderResponse;
import com.example.events.dtos.PaymentResponse;
import com.example.events.dtos.ReleaseFunds;
import com.example.events.dtos.ReleaseItems;
import com.example.events.dtos.ReserveItems;
import com.example.events.dtos.ReservePayment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/** Publishes committed outbox rows. Duplicate publication is safe because consumers are idempotent. */
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 25;
    private static final Map<String, Class<?>> PAYLOAD_TYPES = Map.ofEntries(
            Map.entry(CancelOrder.class.getSimpleName(), CancelOrder.class),
            Map.entry(ChargeMoney.class.getSimpleName(), ChargeMoney.class),
            Map.entry(CreateOrder.class.getSimpleName(), CreateOrder.class),
            Map.entry(DeductItems.class.getSimpleName(), DeductItems.class),
            Map.entry(FinalizeOrder.class.getSimpleName(), FinalizeOrder.class),
            Map.entry(InventoryResponse.class.getSimpleName(), InventoryResponse.class),
            Map.entry(OrderResponse.class.getSimpleName(), OrderResponse.class),
            Map.entry(PaymentResponse.class.getSimpleName(), PaymentResponse.class),
            Map.entry(ReleaseFunds.class.getSimpleName(), ReleaseFunds.class),
            Map.entry(ReleaseItems.class.getSimpleName(), ReleaseItems.class),
            Map.entry(ReserveItems.class.getSimpleName(), ReserveItems.class),
            Map.entry(ReservePayment.class.getSimpleName(), ReservePayment.class));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    public OutboxPublisher(JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper,
                           KafkaTemplate<Object, Object> kafkaTemplate,
                           TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:250}")
    public void publishPending() {
        transactionTemplate.executeWithoutResult(status -> {
            List<OutboxRow> rows = jdbcTemplate.query("""
                    SELECT event_id, topic, message_key, payload_type, payload_json
                    FROM outbox_events current_event
                    WHERE published_at IS NULL
                      AND NOT EXISTS (
                        SELECT 1 FROM outbox_events earlier
                        WHERE earlier.topic = current_event.topic
                          AND earlier.message_key = current_event.message_key
                          AND earlier.event_id < current_event.event_id
                          AND earlier.published_at IS NULL
                      )
                    ORDER BY event_id
                    LIMIT ?
                    FOR UPDATE OF current_event SKIP LOCKED
                    """, (rs, rowNum) -> new OutboxRow(
                    rs.getLong("event_id"),
                    rs.getString("topic"),
                    rs.getString("message_key"),
                    rs.getString("payload_type"),
                    rs.getString("payload_json")), BATCH_SIZE);

            Map<OutboxRow, CompletableFuture<SendResult<Object, Object>>> sends = new HashMap<>();
            for (OutboxRow row : rows) {
                try {
                    Class<?> payloadType = PAYLOAD_TYPES.get(row.payloadType());
                    if (payloadType == null) {
                        throw new IllegalArgumentException("Unregistered outbox payload type: " + row.payloadType());
                    }
                    Object payload = objectMapper.readValue(row.payloadJson(), payloadType);
                    sends.put(row, kafkaTemplate.send(row.topic(), row.messageKey(), payload));
                } catch (Exception e) {
                    jdbcTemplate.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE event_id = ?",
                            abbreviate(e.toString(), 1000), row.eventId());
                    log.error("Outbox event {} for topic {} remains pending", row.eventId(), row.topic(), e);
                }
            }
            for (Map.Entry<OutboxRow, CompletableFuture<SendResult<Object, Object>>> send : sends.entrySet()) {
                OutboxRow row = send.getKey();
                try {
                    send.getValue().get(15, TimeUnit.SECONDS);
                    jdbcTemplate.update("UPDATE outbox_events SET published_at = CURRENT_TIMESTAMP, last_error = NULL WHERE event_id = ?", row.eventId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    recordFailure(row, e);
                } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
                    recordFailure(row, e);
                }
            }
        });
    }

    private void recordFailure(OutboxRow row, Exception error) {
        jdbcTemplate.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE event_id = ?",
                abbreviate(error.toString(), 1000), row.eventId());
        log.error("Outbox event {} for topic {} remains pending", row.eventId(), row.topic(), error);
    }

    private static String abbreviate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record OutboxRow(long eventId, String topic, String messageKey, String payloadType, String payloadJson) {}
}
