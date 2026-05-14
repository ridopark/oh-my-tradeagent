package com.ohmytradeagent.exectradierpaper;

import io.temporal.worker.WorkerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WorkerStarter {

  private final WorkerFactory workerFactory;

  public WorkerStarter(WorkerFactory workerFactory) {
    this.workerFactory = workerFactory;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    workerFactory.start();
  }
}
