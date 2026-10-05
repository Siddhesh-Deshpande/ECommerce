package com.example.events.dtos;

import org.springframework.core.annotation.Order;

public class OrderResponse extends Response {
    private Integer id;
    public OrderResponse(String correlationId, boolean status, Integer id) {
        super(correlationId,OrderResponse.class.getSimpleName() ,status);
        this.id = id;
    }
    public OrderResponse(String correlationId, boolean status, Integer id, String stage) {
        super(correlationId, OrderResponse.class.getSimpleName(), status, stage);
        this.id = id;
    }
    public OrderResponse(){}

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }
}
