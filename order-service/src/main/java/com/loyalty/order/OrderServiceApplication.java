package com.loyalty.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of order-service.
 *
 * {@code @SpringBootApplication} is three annotations in one:
 * - {@code @Configuration}: this class can define beans
 * - {@code @EnableAutoConfiguration}: Spring Boot configures things based on the jars it finds
 *   (it sees spring-webmvc, so it starts an embedded Tomcat)
 * - {@code @ComponentScan}: finds our controllers/services in this package and below
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
