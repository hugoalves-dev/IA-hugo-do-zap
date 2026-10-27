package dev.hugo.watcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WatcherApplication {
    public static void main(String[] args) {
        SpringApplication.run(WatcherApplication.class, args);
    }
}
