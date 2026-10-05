package com.example.payment_service.service;

import com.example.events.dtos.ChargeMoney;
import com.example.events.dtos.PaymentResponse;
import com.example.events.dtos.ReleaseFunds;
import com.example.events.dtos.ReservePayment;
import com.example.events.outbox.OutboxEventWriter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@KafkaListener(topics = "payment-service")
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger("DB_OPERATIONS");
    private final JdbcTemplate jdbcTemplate;
    private final OutboxEventWriter outbox;
    private final Tracer tracer;

    public PaymentService(JdbcTemplate jdbcTemplate, OutboxEventWriter outbox, Tracer tracer) {
        this.jdbcTemplate = jdbcTemplate;
        this.outbox = outbox;
        this.tracer = tracer;
    }

    @KafkaHandler
    @Transactional
    public void prepare(ReservePayment command) {
        inSpan("payment-prepare", command.getCorrelationId(), () -> {
            String cid = command.getCorrelationId();
            if (command.getClientid() == null || command.getAmount() == null || command.getAmount() < 0) {
                vote(cid, false);
                return;
            }
            String state = lockOrCreateWorkflow(cid, command.getClientid(), command.getAmount());
            if ("PREPARED".equals(state) || "COMMITTED".equals(state)) {
                vote(cid, true);
                return;
            }
            if ("REJECTED".equals(state) || "ABORTED".equals(state)) {
                vote(cid, false);
                return;
            }

            Integer balance = queryInt("SELECT balance FROM users WHERE user_id = ? FOR UPDATE", command.getClientid());
            if (balance == null) {
                jdbcTemplate.update("UPDATE payment_workflows SET state = 'REJECTED', updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?", cid);
                vote(cid, false);
                return;
            }

            jdbcTemplate.update("INSERT INTO reserve_payments (user_id, amount) VALUES (?, 0) ON CONFLICT (user_id) DO NOTHING", command.getClientid());
            Integer reserved = queryInt("SELECT amount FROM reserve_payments WHERE user_id = ? FOR UPDATE", command.getClientid());
            int currentReserved = reserved == null ? 0 : reserved;
            if ((long) balance - currentReserved < command.getAmount()) {
                jdbcTemplate.update("UPDATE payment_workflows SET state = 'REJECTED', updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?", cid);
                vote(cid, false);
                return;
            }

            jdbcTemplate.update("UPDATE reserve_payments SET amount = ? WHERE user_id = ?",
                    Math.addExact(currentReserved, command.getAmount()), command.getClientid());
            jdbcTemplate.update("UPDATE payment_workflows SET state = 'PREPARED', updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?", cid);
            vote(cid, true);
        });
    }

    @KafkaHandler
    @Transactional
    public void commit(ChargeMoney command) {
        inSpan("payment-commit", command.getCorrelationid(), () -> {
            String cid = command.getCorrelationid();
            PaymentWorkflow workflow = findWorkflowForUpdate(cid);
            if (workflow != null && "COMMITTED".equals(workflow.state())) {
                acknowledge(cid, true, "COMMIT");
                return;
            }
            if (workflow == null || !"PREPARED".equals(workflow.state())) {
                acknowledge(cid, false, "COMMIT");
                return;
            }

            Integer balance = queryInt("SELECT balance FROM users WHERE user_id = ? FOR UPDATE", workflow.clientId());
            Integer reserved = queryInt("SELECT amount FROM reserve_payments WHERE user_id = ? FOR UPDATE", workflow.clientId());
            if (balance == null || reserved == null || balance < workflow.amount() || reserved < workflow.amount()) {
                throw new IllegalStateException("Prepared payment cannot be committed for " + cid);
            }
            jdbcTemplate.update("UPDATE users SET balance = balance - ? WHERE user_id = ?", workflow.amount(), workflow.clientId());
            jdbcTemplate.update("UPDATE reserve_payments SET amount = amount - ? WHERE user_id = ?", workflow.amount(), workflow.clientId());
            jdbcTemplate.update("UPDATE payment_workflows SET state = 'COMMITTED', updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?", cid);
            log.info("correlationId: {}, eventType: {}, timestamp: {}, user_id: {}, amount_deducted: {}",
                    cid, "ChargeMoney", Instant.now(), workflow.clientId(), workflow.amount());
            acknowledge(cid, true, "COMMIT");
        });
    }

    @KafkaHandler
    @Transactional
    public void abort(ReleaseFunds command) {
        inSpan("payment-abort", command.getCorrelationid(), () -> {
            String cid = command.getCorrelationid();
            PaymentWorkflow workflow = findWorkflowForUpdate(cid);
            if (workflow == null) {
                jdbcTemplate.update("""
                        INSERT INTO payment_workflows (correlation_id, state)
                        VALUES (?, 'ABORTED') ON CONFLICT (correlation_id) DO NOTHING
                        """, cid);
                workflow = findWorkflowForUpdate(cid);
            }
            if (workflow != null && "COMMITTED".equals(workflow.state())) {
                acknowledge(cid, false, "ABORT");
                return;
            }
            if (workflow != null && "PREPARED".equals(workflow.state())) {
                queryInt("SELECT amount FROM reserve_payments WHERE user_id = ? FOR UPDATE", workflow.clientId());
                jdbcTemplate.update("UPDATE reserve_payments SET amount = amount - ? WHERE user_id = ?",
                        workflow.amount(), workflow.clientId());
            }
            jdbcTemplate.update("UPDATE payment_workflows SET state = 'ABORTED', updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?", cid);
            acknowledge(cid, true, "ABORT");
        });
    }

    private String lockOrCreateWorkflow(String cid, int clientId, int amount) {
        jdbcTemplate.update("""
                INSERT INTO payment_workflows (correlation_id, client_id, amount, state)
                VALUES (?, ?, ?, 'PREPARING') ON CONFLICT (correlation_id) DO NOTHING
                """, cid, clientId, amount);
        PaymentWorkflow workflow = findWorkflowForUpdate(cid);
        if (workflow == null) return null;
        if ((workflow.clientId() != null && workflow.clientId() != clientId)
                || (workflow.amount() != null && workflow.amount() != amount)) {
            throw new IllegalStateException("Correlation ID reused with different payment details: " + cid);
        }
        return workflow.state();
    }

    private PaymentWorkflow findWorkflowForUpdate(String cid) {
        List<PaymentWorkflow> rows = jdbcTemplate.query("""
                SELECT state, client_id, amount FROM payment_workflows WHERE correlation_id = ? FOR UPDATE
                """, (rs, rowNum) -> new PaymentWorkflow(rs.getString("state"),
                (Integer) rs.getObject("client_id"), (Integer) rs.getObject("amount")), cid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Integer queryInt(String sql, Integer parameter) {
        List<Integer> rows = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getInt(1), parameter);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void vote(String cid, boolean prepared) {
        outbox.enqueue("coor-service", cid, cid + ":vote:payment:prepare",
                new PaymentResponse(cid, prepared, "PREPARE"));
    }

    private void acknowledge(String cid, boolean success, String stage) {
        PaymentResponse response = new PaymentResponse(cid, success, stage);
        outbox.enqueue("coor-service", cid,
                cid + ":ack:payment:" + stage.toLowerCase() + ":" + response.getTimestamp().toEpochMilli(), response);
    }

    private void inSpan(String name, String correlationId, Runnable operation) {
        Span span = tracer.nextSpan().name(name);
        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
            span.tag("correlationId", correlationId);
            operation.run();
        } finally {
            span.end();
        }
    }

    private record PaymentWorkflow(String state, Integer clientId, Integer amount) {}
}
