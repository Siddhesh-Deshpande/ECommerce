package com.example.events.dtos;

public class PaymentResponse extends Response {
    public PaymentResponse(String correlationId, boolean status) {
        super(correlationId,PaymentResponse.class.getSimpleName(), status);
    }
    public PaymentResponse(String correlationId, boolean status, String stage) {
        super(correlationId, PaymentResponse.class.getSimpleName(), status, stage);
    }
    public PaymentResponse(){}
}
