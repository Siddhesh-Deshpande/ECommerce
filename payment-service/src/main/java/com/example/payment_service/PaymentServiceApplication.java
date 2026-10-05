package com.example.payment_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.example.events.outbox.OutboxConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableScheduling
@Import(OutboxConfiguration.class)
public class PaymentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentServiceApplication.class, args);
	}

}
