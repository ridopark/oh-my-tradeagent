package com.ohmytradeagent.exec;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExecApplication {

  public static void main(String[] args) {
    SpringApplication.run(ExecApplication.class, args);
  }
}
