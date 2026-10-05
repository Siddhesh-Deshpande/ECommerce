package com.example.inventory_service.services;

import com.example.events.dtos.DeductItems;
import com.example.events.dtos.InventoryResponse;
import com.example.events.dtos.ReleaseItems;
import com.example.events.dtos.ReserveItems;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@KafkaListener(topics = "inventory-service")
public class InventoryService {
    private static final Logger log = LoggerFactory.getLogger("DB_OPERATIONS");
    private final JdbcTemplate jdbcTemplate;
    private final OutboxEventWriter outbox;
    private final Tracer tracer;

    public InventoryService(JdbcTemplate jdbcTemplate, OutboxEventWriter outbox, Tracer tracer) {
        this.jdbcTemplate = jdbcTemplate;
        this.outbox = outbox;
        this.tracer = tracer;
    }

    @KafkaHandler
    @Transactional
    public void prepare(ReserveItems command) {
        inSpan("inventory-prepare", command.getCorrelationId(), () -> {
            String cid = command.getCorrelationId();
            String state = lockOrCreateWorkflow(cid);
            if ("PREPARED".equals(state) || "COMMITTED".equals(state)) {
                vote(cid, true);
                return;
            }
            if ("REJECTED".equals(state) || "ABORTED".equals(state)) {
                vote(cid, false);
                return;
            }

            TreeMap<Integer, Integer> requested = normalize(command.getItemIds(), command.getQuantity());
            boolean canReserve = !requested.isEmpty();
            List<LockedItem> lockedItems = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : requested.entrySet()) {
                Integer stock = queryInt("SELECT quantity FROM items WHERE item_id = ? FOR UPDATE", entry.getKey());
                if (stock == null) {
                    canReserve = false;
                    break;
                }
                jdbcTemplate.update("INSERT INTO reserved_items (item_id, reserved_quantity) VALUES (?, 0) ON CONFLICT (item_id) DO NOTHING", entry.getKey());
                Integer reserved = queryInt("SELECT reserved_quantity FROM reserved_items WHERE item_id = ? FOR UPDATE", entry.getKey());
                lockedItems.add(new LockedItem(entry.getKey(), stock, reserved == null ? 0 : reserved));
                if (stock - (reserved == null ? 0 : reserved) < entry.getValue()) {
                    canReserve = false;
                }
            }

            if (!canReserve) {
                jdbcTemplate.update("UPDATE inventory_workflows SET state = 'REJECTED', updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?", cid);
                vote(cid, false);
                return;
            }

            for (LockedItem item : lockedItems) {
                int quantity = requested.get(item.itemId());
                jdbcTemplate.update("UPDATE reserved_items SET reserved_quantity = ? WHERE item_id = ?",
                        Math.addExact(item.reserved(), quantity), item.itemId());
                jdbcTemplate.update("INSERT INTO inventory_workflow_items (correlation_id, item_id, quantity) VALUES (?, ?, ?)",
                        cid, item.itemId(), quantity);
            }
            jdbcTemplate.update("UPDATE inventory_workflows SET state = 'PREPARED', updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?", cid);
            vote(cid, true);
        });
    }

    @KafkaHandler
    @Transactional
    public void commit(DeductItems command) {
        inSpan("inventory-commit", command.getCorrelationid(), () -> {
            String cid = command.getCorrelationid();
            String state = findWorkflowStateForUpdate(cid);
            if ("COMMITTED".equals(state)) {
                acknowledge(cid, true, "COMMIT");
                return;
            }
            if (!"PREPARED".equals(state)) {
                acknowledge(cid, false, "COMMIT");
                return;
            }

            for (Map<String, Object> reservation : reservationsForUpdate(cid)) {
                int itemId = ((Number) reservation.get("item_id")).intValue();
                int quantity = ((Number) reservation.get("quantity")).intValue();
                List<Integer> stockRows = jdbcTemplate.query("SELECT quantity FROM items WHERE item_id = ? FOR UPDATE",
                        (rs, rowNum) -> rs.getInt(1), itemId);
                queryInt("SELECT reserved_quantity FROM reserved_items WHERE item_id = ? FOR UPDATE", itemId);
                if (stockRows.isEmpty() || stockRows.getFirst() < quantity) {
                    throw new IllegalStateException("Prepared inventory is no longer available for " + cid + " item " + itemId);
                }
                jdbcTemplate.update("UPDATE items SET quantity = quantity - ? WHERE item_id = ?", quantity, itemId);
                jdbcTemplate.update("UPDATE reserved_items SET reserved_quantity = reserved_quantity - ? WHERE item_id = ?", quantity, itemId);
                log.info("correlationId: {}, eventType: {}, timestamp: {}, item_id: {}, quantity_deducted: {}",
                        cid, "DeductItems", Instant.now(), itemId, quantity);
            }
            jdbcTemplate.update("UPDATE inventory_workflows SET state = 'COMMITTED', updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?", cid);
            acknowledge(cid, true, "COMMIT");
        });
    }

    @KafkaHandler
    @Transactional
    public void abort(ReleaseItems command) {
        inSpan("inventory-abort", command.getCorrelationid(), () -> {
            String cid = command.getCorrelationid();
            String state = lockOrCreateWorkflow(cid);
            if ("COMMITTED".equals(state)) {
                acknowledge(cid, false, "ABORT");
                return;
            }
            if ("PREPARED".equals(state)) {
                for (Map<String, Object> reservation : reservationsForUpdate(cid)) {
                    int itemId = ((Number) reservation.get("item_id")).intValue();
                    int quantity = ((Number) reservation.get("quantity")).intValue();
                    queryInt("SELECT reserved_quantity FROM reserved_items WHERE item_id = ? FOR UPDATE", itemId);
                    jdbcTemplate.update("UPDATE reserved_items SET reserved_quantity = reserved_quantity - ? WHERE item_id = ?",
                            quantity, itemId);
                }
            }
            jdbcTemplate.update("UPDATE inventory_workflows SET state = 'ABORTED', updated_at = CURRENT_TIMESTAMP WHERE correlation_id = ?", cid);
            acknowledge(cid, true, "ABORT");
        });
    }

    private void vote(String cid, boolean prepared) {
        outbox.enqueue("coor-service", cid, cid + ":vote:inventory:prepare",
                new InventoryResponse(cid, prepared, "PREPARE"));
    }

    private void acknowledge(String cid, boolean success, String stage) {
        InventoryResponse response = new InventoryResponse(cid, success, stage);
        outbox.enqueue("coor-service", cid,
                cid + ":ack:inventory:" + stage.toLowerCase() + ":" + response.getTimestamp().toEpochMilli(), response);
    }

    private String lockOrCreateWorkflow(String cid) {
        jdbcTemplate.update("INSERT INTO inventory_workflows (correlation_id, state) VALUES (?, 'PREPARING') ON CONFLICT (correlation_id) DO NOTHING", cid);
        return findWorkflowStateForUpdate(cid);
    }

    private String findWorkflowStateForUpdate(String cid) {
        List<String> rows = jdbcTemplate.query("SELECT state FROM inventory_workflows WHERE correlation_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), cid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Map<String, Object>> reservationsForUpdate(String cid) {
        return jdbcTemplate.query("""
                SELECT item_id, quantity FROM inventory_workflow_items
                WHERE correlation_id = ? ORDER BY item_id FOR UPDATE
                """, (rs, rowNum) -> Map.of("item_id", rs.getInt("item_id"), "quantity", rs.getInt("quantity")), cid);
    }

    private Integer queryInt(String sql, Integer parameter) {
        List<Integer> rows = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getInt(1), parameter);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static TreeMap<Integer, Integer> normalize(Integer[] itemIds, Integer[] quantities) {
        TreeMap<Integer, Integer> requested = new TreeMap<>();
        if (itemIds == null || quantities == null || itemIds.length == 0 || itemIds.length != quantities.length) {
            return requested;
        }
        for (int i = 0; i < itemIds.length; i++) {
            if (itemIds[i] == null || itemIds[i] <= 0 || quantities[i] == null || quantities[i] <= 0) {
                return new TreeMap<>();
            }
            requested.merge(itemIds[i], quantities[i], (left, right) -> Math.addExact(left, right));
        }
        return requested;
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

    private record LockedItem(int itemId, int stock, int reserved) {}
}
