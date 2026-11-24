package com.example.replenishment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class ReplenishmentApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReplenishmentApplication.class, args);
    }
}
