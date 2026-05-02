package io.kairo.code.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.kairo.code")
public class KairoCodeServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KairoCodeServerApplication.class, args);
    }
}
