package net.m87.spinelite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("net.m87.spinelite.config")
public class SpineLiteApplication {

  public static void main(String[] args) {
    SpringApplication.run(SpineLiteApplication.class, args);
  }
}
