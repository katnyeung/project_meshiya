package com.meshiya;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MeshiyaApplication {
    public static void main(String[] args) {
        SpringApplication.run(MeshiyaApplication.class, args);
    }
}