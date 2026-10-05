package com.example.coordinator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.example.events.outbox.OutboxConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableScheduling
@Import(OutboxConfiguration.class)
public class CoordinatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(CoordinatorApplication.class, args);
	}

}
