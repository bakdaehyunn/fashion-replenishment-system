package com.example.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class WmsSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(WmsSimulatorApplication.class, args);
    }
}
