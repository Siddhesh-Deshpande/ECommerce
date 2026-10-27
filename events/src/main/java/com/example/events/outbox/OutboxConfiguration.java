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
import com.example.events.dtos.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@RegisterReflectionForBinding({
        CancelOrder.class,
        ChargeMoney.class,
        CreateOrder.class,
        DeductItems.class,
        FinalizeOrder.class,
        InventoryResponse.class,
        OrderResponse.class,
        PaymentResponse.class,
        ReleaseFunds.class,
        ReleaseItems.class,
        ReserveItems.class,
        ReservePayment.class,
        Response.class
})
public class OutboxConfiguration {
    @Bean
    public ApplicationRunner verifyOutboxEventJsonCompatibility(ObjectMapper objectMapper) {
        return args -> {
            for (Object payload : OutboxPayloadCompatibility.samples()) {
                try {
                    String json = objectMapper.writeValueAsString(payload);
                    objectMapper.readValue(json, payload.getClass());
                } catch (Exception error) {
                    throw new IllegalStateException(
                            "Jackson cannot round-trip outbox payload " + payload.getClass().getName(), error);
                }
            }
        };
    }

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
