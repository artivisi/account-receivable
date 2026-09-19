package com.artivisi.accountreceivable;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AccountReceivableApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountReceivableApplication.class, args);
    }
}
