package com.example.sil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ServiceIntegrationLayerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceIntegrationLayerApplication.class, args);
    }
}
