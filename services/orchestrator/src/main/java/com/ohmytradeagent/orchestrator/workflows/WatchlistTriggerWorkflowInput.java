package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import com.ohmytradeagent.orchestrator.domain.EntryStateMachine;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Input to {@link WatchlistTriggerWorkflow}. Carries the leg ({@link WatchlistTriggerPayload}), the
 * resolved {@link StrategyConfig}, and the arm-time {@code size_multiplier} (the arm decider's
 * output, decided by the parent before the child is started). The remaining fields are the
 * continue-as-new carry-forward state (mirroring {@code KillSwitchWorkflowInput}); they are null on
 * the first run and re-populated by the child when it continues-as-new at the history watermark.
 *
 * <p>A plain serializable POJO (not a contract DTO) because it is purely internal to the
 * orchestrator and never crosses a service boundary.
 */
public class WatchlistTriggerWorkflowInput {

  private WatchlistTriggerPayload payload;
  private StrategyConfig config;
  private BigDecimal sizeMultiplier;

  // continue-as-new carry-forward
  private EntryStateMachine.State carriedState;
  private BigDecimal carriedPrev;
  private LocalDate etDate;
  private boolean fired;

  public WatchlistTriggerWorkflowInput() {}

  public WatchlistTriggerWorkflowInput(
      WatchlistTriggerPayload payload, StrategyConfig config, BigDecimal sizeMultiplier) {
    this.payload = payload;
    this.config = config;
    this.sizeMultiplier = sizeMultiplier;
  }

  public WatchlistTriggerPayload getPayload() {
    return payload;
  }

  public void setPayload(WatchlistTriggerPayload payload) {
    this.payload = payload;
  }

  public StrategyConfig getConfig() {
    return config;
  }

  public void setConfig(StrategyConfig config) {
    this.config = config;
  }

  public BigDecimal getSizeMultiplier() {
    return sizeMultiplier;
  }

  public void setSizeMultiplier(BigDecimal sizeMultiplier) {
    this.sizeMultiplier = sizeMultiplier;
  }

  public EntryStateMachine.State getCarriedState() {
    return carriedState;
  }

  public void setCarriedState(EntryStateMachine.State carriedState) {
    this.carriedState = carriedState;
  }

  public BigDecimal getCarriedPrev() {
    return carriedPrev;
  }

  public void setCarriedPrev(BigDecimal carriedPrev) {
    this.carriedPrev = carriedPrev;
  }

  public LocalDate getEtDate() {
    return etDate;
  }

  public void setEtDate(LocalDate etDate) {
    this.etDate = etDate;
  }

  public boolean isFired() {
    return fired;
  }

  public void setFired(boolean fired) {
    this.fired = fired;
  }
}
