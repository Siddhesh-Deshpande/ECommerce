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

import java.util.List;

/** Sample payloads used to fail fast if an application's JSON mapper cannot process an event DTO. */
public final class OutboxPayloadCompatibility {
    private OutboxPayloadCompatibility() {}

    public static List<Object> samples() {
        return List.of(
                new CancelOrder("native-image-smoke-test"),
                new ChargeMoney("native-image-smoke-test"),
                new CreateOrder("native-image-smoke-test", new Integer[]{1}, new Integer[]{1}, 1, 10),
                new DeductItems("native-image-smoke-test"),
                new FinalizeOrder("native-image-smoke-test", 1),
                new InventoryResponse("native-image-smoke-test", true),
                new OrderResponse("native-image-smoke-test", true, 1),
                new PaymentResponse("native-image-smoke-test", true),
                new ReleaseFunds("native-image-smoke-test"),
                new ReleaseItems("native-image-smoke-test"),
                new ReserveItems("native-image-smoke-test", new Integer[]{1}, new Integer[]{1}),
                new ReservePayment("native-image-smoke-test", 1, 10)
        );
    }
}
