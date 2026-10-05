package com.example.events.dtos;

import java.time.Instant;

public class InventoryResponse extends  Response{

    public InventoryResponse(String correlationId, boolean status) {
        super(correlationId, InventoryResponse.class.getSimpleName(),status);
    }
    public InventoryResponse(String correlationId, boolean status, String stage) {
        super(correlationId, InventoryResponse.class.getSimpleName(), status, stage);
    }
    public InventoryResponse(){}
}
