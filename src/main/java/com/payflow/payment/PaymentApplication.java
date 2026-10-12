package com.payflow.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class PaymentApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(PaymentApplication.class, args);
        if (java.util.Arrays.asList(context.getEnvironment().getActiveProfiles()).contains("bootstrap")) {
            context.close();
        }
    }
}
