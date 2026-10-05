package com.example.coordinator.services;

import com.example.coordinator.Controller.RestController.OrderSubmissionRequest;
import com.example.coordinator.Controller.RestController.WorkflowStatusResponse;
import com.example.coordinator.entity.Item;
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
import com.example.events.outbox.OutboxEventWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CoordinatorWorkflowService {
    private static final String PREPARING = "PREPARING";
    private static final String COMMITTING = "COMMITTING";
    private static final String ABORTING = "ABORTING";
    private static final String COMPLETED = "COMPLETED";
    private static final String ABORTED = "ABORTED";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxEventWriter outbox;

    public CoordinatorWorkflowService(JdbcTemplate jdbcTemplate,
                                      ObjectMapper objectMapper,
                                      OutboxEventWriter outbox) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.outbox = outbox;
    }

    @Transactional
    public String submit(OrderSubmissionRequest request) {
        validate(request);
        String correlationId = UUID.randomUUID().toString();
        String requestJson = writeJson(request);
        jdbcTemplate.update("""
                INSERT INTO coordinator_workflows (correlation_id, client_id, request_json, state)
                VALUES (?, ?, ?, 'PREPARING')
                """, correlationId, request.clientid(), requestJson);

        Integer[] itemIds = request.items().stream().map(Item::getId).toArray(Integer[]::new);
        Integer[] quantities = request.items().stream().map(Item::getQuantity).toArray(Integer[]::new);
        int amount = request.items().stream()
                .map(item -> Math.multiplyExact(item.getPrice(), item.getQuantity()))
                .reduce(0, (left, right) -> Math.addExact(left, right));

        outbox.enqueue("order-service", correlationId, correlationId + ":prepare:order",
                new CreateOrder(correlationId, itemIds, quantities, request.clientid(), amount));
        outbox.enqueue("inventory-service", correlationId, correlationId + ":prepare:inventory",
                new ReserveItems(correlationId, itemIds, quantities));
        outbox.enqueue("payment-service", correlationId, correlationId + ":prepare:payment",
                new ReservePayment(correlationId, request.clientid(), amount));
        return correlationId;
    }

    @Transactional
    public void recordParticipantResult(String correlationId, String participant, boolean success,
                                        Integer orderId, String stage) {
        Map<String, Object> workflow = lockWorkflow(correlationId);
        String state = (String) workflow.get("state");
        if (workflow.isEmpty()) {
            return;
        }

        String normalizedStage = stage == null || stage.isBlank() ? "PREPARE" : stage.toUpperCase();
        if ("PREPARE".equals(normalizedStage) && PREPARING.equals(state)) {
            recordPrepareVote(correlationId, participant, success, orderId, workflow);
        } else if ("COMMIT".equals(normalizedStage) && COMMITTING.equals(state)) {
            recordDecisionAck(correlationId, participant, true, success);
        } else if ("ABORT".equals(normalizedStage) && ABORTING.equals(state)) {
            recordDecisionAck(correlationId, participant, false, success);
        }
    }

    private void recordPrepareVote(String correlationId, String participant, boolean success,
                                   Integer orderId, Map<String, Object> workflow) {
        String voteColumn = switch (participant) {
            case "order" -> "order_vote";
            case "inventory" -> "inventory_vote";
            case "payment" -> "payment_vote";
            default -> throw new IllegalArgumentException("Unknown workflow participant: " + participant);
        };
        String update = "UPDATE coordinator_workflows SET " + voteColumn + " = COALESCE(" + voteColumn
                + ", ?), order_id = COALESCE(?, order_id), updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?";
        jdbcTemplate.update(update, success, orderId, correlationId);

        Map<String, Object> recorded = jdbcTemplate.queryForMap(
                "SELECT order_vote, inventory_vote, payment_vote, order_id FROM coordinator_workflows WHERE correlation_id = ?",
                correlationId);
        Boolean orderVote = (Boolean) recorded.get("order_vote");
        Boolean inventoryVote = (Boolean) recorded.get("inventory_vote");
        Boolean paymentVote = (Boolean) recorded.get("payment_vote");

        if (Boolean.FALSE.equals(orderVote) || Boolean.FALSE.equals(inventoryVote) || Boolean.FALSE.equals(paymentVote)) {
            beginAbort(correlationId);
        } else if (Boolean.TRUE.equals(orderVote)
                && Boolean.TRUE.equals(inventoryVote)
                && Boolean.TRUE.equals(paymentVote)) {
            beginCommit(correlationId, (Integer) recorded.get("order_id"));
        }
    }

    private void beginCommit(String correlationId, Integer orderId) {
        if (orderId == null) {
            throw new IllegalStateException("All participants voted yes but the order service did not return an order ID");
        }
        int changed = jdbcTemplate.update("""
                UPDATE coordinator_workflows SET state = 'COMMITTING', decision_attempt = 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE correlation_id = ? AND state = 'PREPARING'
                """, correlationId);
        if (changed == 0) return;

        outbox.enqueue("order-service", correlationId, correlationId + ":decision:commit:order",
                new FinalizeOrder(correlationId, orderId));
        outbox.enqueue("inventory-service", correlationId, correlationId + ":decision:commit:inventory",
                new DeductItems(correlationId));
        outbox.enqueue("payment-service", correlationId, correlationId + ":decision:commit:payment",
                new ChargeMoney(correlationId));
    }

    @Transactional
    public int abortExpiredPreparations(long timeoutMillis) {
        List<String> expired = jdbcTemplate.query("""
                SELECT correlation_id FROM coordinator_workflows
                WHERE state = 'PREPARING'
                  AND created_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond')
                ORDER BY created_at
                LIMIT 100
                FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> rs.getString(1), timeoutMillis);
        for (String correlationId : expired) {
            beginAbort(correlationId);
        }
        return expired.size();
    }

    @Transactional
    public int resendStaleDecisions(long retryAfterMillis) {
        List<Map<String, Object>> stale = jdbcTemplate.queryForList("""
                SELECT * FROM coordinator_workflows
                WHERE state IN ('COMMITTING', 'ABORTING')
                  AND updated_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond')
                ORDER BY updated_at
                LIMIT 100
                FOR UPDATE SKIP LOCKED
                """, retryAfterMillis);
        for (Map<String, Object> workflow : stale) {
            String correlationId = (String) workflow.get("correlation_id");
            String state = (String) workflow.get("state");
            int attempt = ((Number) workflow.get("decision_attempt")).intValue() + 1;
            jdbcTemplate.update("UPDATE coordinator_workflows SET decision_attempt = ?, updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?",
                    attempt, correlationId);
            if (COMMITTING.equals(state)) {
                enqueueMissingCommitCommands(correlationId, workflow, attempt);
            } else {
                enqueueMissingAbortCommands(correlationId, workflow, attempt);
            }
        }
        return stale.size();
    }

    private void beginAbort(String correlationId) {
        int changed = jdbcTemplate.update("""
                UPDATE coordinator_workflows SET state = 'ABORTING', decision_attempt = 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE correlation_id = ? AND state = 'PREPARING'
                """, correlationId);
        if (changed == 0) return;

        outbox.enqueue("order-service", correlationId, correlationId + ":decision:abort:order",
                new CancelOrder(correlationId));
        outbox.enqueue("inventory-service", correlationId, correlationId + ":decision:abort:inventory",
                new ReleaseItems(correlationId));
        outbox.enqueue("payment-service", correlationId, correlationId + ":decision:abort:payment",
                new ReleaseFunds(correlationId));
    }

    private void recordDecisionAck(String correlationId, String participant, boolean commit, boolean success) {
        if (!success) {
            // Keep the workflow in its decision state. The recovery scheduler will
            // resend the idempotent command until this participant can apply it.
            return;
        }
        String column = switch (participant) {
            case "order" -> commit ? "order_commit_ack" : "order_abort_ack";
            case "inventory" -> commit ? "inventory_commit_ack" : "inventory_abort_ack";
            case "payment" -> commit ? "payment_commit_ack" : "payment_abort_ack";
            default -> throw new IllegalArgumentException("Unknown workflow participant: " + participant);
        };
        jdbcTemplate.update("UPDATE coordinator_workflows SET " + column
                + " = TRUE, updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?", correlationId);
        Map<String, Object> acks = jdbcTemplate.queryForMap("SELECT * FROM coordinator_workflows WHERE correlation_id = ?", correlationId);
        boolean allComplete = commit
                ? Boolean.TRUE.equals(acks.get("order_commit_ack"))
                    && Boolean.TRUE.equals(acks.get("inventory_commit_ack"))
                    && Boolean.TRUE.equals(acks.get("payment_commit_ack"))
                : Boolean.TRUE.equals(acks.get("order_abort_ack"))
                    && Boolean.TRUE.equals(acks.get("inventory_abort_ack"))
                    && Boolean.TRUE.equals(acks.get("payment_abort_ack"));
        if (allComplete) {
            jdbcTemplate.update("UPDATE coordinator_workflows SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?",
                    commit ? COMPLETED : ABORTED, correlationId);
        }
    }

    private void enqueueMissingCommitCommands(String cid, Map<String, Object> workflow, int attempt) {
        if (!Boolean.TRUE.equals(workflow.get("order_commit_ack"))) {
            Integer orderId = (Integer) workflow.get("order_id");
            if (orderId != null) {
                outbox.enqueue("order-service", cid, cid + ":decision:commit:order:retry:" + attempt,
                        new FinalizeOrder(cid, orderId));
            }
        }
        if (!Boolean.TRUE.equals(workflow.get("inventory_commit_ack"))) {
            outbox.enqueue("inventory-service", cid, cid + ":decision:commit:inventory:retry:" + attempt,
                    new DeductItems(cid));
        }
        if (!Boolean.TRUE.equals(workflow.get("payment_commit_ack"))) {
            outbox.enqueue("payment-service", cid, cid + ":decision:commit:payment:retry:" + attempt,
                    new ChargeMoney(cid));
        }
    }

    private void enqueueMissingAbortCommands(String cid, Map<String, Object> workflow, int attempt) {
        if (!Boolean.TRUE.equals(workflow.get("order_abort_ack"))) {
            outbox.enqueue("order-service", cid, cid + ":decision:abort:order:retry:" + attempt,
                    new CancelOrder(cid));
        }
        if (!Boolean.TRUE.equals(workflow.get("inventory_abort_ack"))) {
            outbox.enqueue("inventory-service", cid, cid + ":decision:abort:inventory:retry:" + attempt,
                    new ReleaseItems(cid));
        }
        if (!Boolean.TRUE.equals(workflow.get("payment_abort_ack"))) {
            outbox.enqueue("payment-service", cid, cid + ":decision:abort:payment:retry:" + attempt,
                    new ReleaseFunds(cid));
        }
    }

    private Map<String, Object> lockWorkflow(String correlationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT * FROM coordinator_workflows WHERE correlation_id = ? FOR UPDATE
                """, correlationId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowStatusResponse> findStatus(String correlationId) {
        List<WorkflowStatusResponse> rows = jdbcTemplate.query("""
                SELECT correlation_id, state, order_id FROM coordinator_workflows WHERE correlation_id = ?
                """, (rs, rowNum) -> new WorkflowStatusResponse(
                rs.getString("correlation_id"), rs.getString("state"), (Integer) rs.getObject("order_id")), correlationId);
        return rows.stream().findFirst();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize submitted order", e);
        }
    }

    private static void validate(OrderSubmissionRequest request) {
        if (request == null || request.clientid() == null || request.clientid() <= 0
                || request.items() == null || request.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientid and at least one item are required");
        }
        try {
            for (Item item : request.items()) {
                if (item == null || item.getId() == null || item.getId() <= 0
                        || item.getQuantity() == null || item.getQuantity() <= 0
                        || item.getPrice() == null || item.getPrice() < 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Each item needs a positive id and quantity and a non-negative price");
                }
                Math.multiplyExact(item.getPrice(), item.getQuantity());
            }
            request.items().stream()
                    .map(item -> Math.multiplyExact(item.getPrice(), item.getQuantity()))
                    .reduce(0, (left, right) -> Math.addExact(left, right));
        } catch (ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order amount exceeds supported range", e);
        }
    }
}
