package com.vodice.book;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class VodiceBookApplication {

    public static void main(String[] args) {
        SpringApplication.run(VodiceBookApplication.class, args);
    }
}
