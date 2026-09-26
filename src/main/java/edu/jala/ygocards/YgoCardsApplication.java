package edu.jala.ygocards;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point.
 *
 * <p>Every value this service needs at run time is read from the environment through
 * {@code application.properties}, never from a file the process writes or from a value
 * compiled into the code. See {@code edu.jala.ygocards.config} for the typed holders.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class YgoCardsApplication {

    public static void main(String[] args) {
        SpringApplication.run(YgoCardsApplication.class, args);
    }
}
