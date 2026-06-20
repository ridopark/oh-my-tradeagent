package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import java.io.Serializable;

/**
 * Input to {@link WatchlistTriggerSessionWorkflow}: the verbatim daily watchlist ({@link
 * WatchlistMirrorPayload}) the session fans out from, plus the resolved {@link StrategyConfig} that
 * gates/sizes the legs. The session re-parses the rows inside a (deterministic-safe) Activity
 * rather than carrying pre-mapped legs, so the parse + strike-validation logic stays out of the
 * workflow body. A plain serializable POJO — internal to the orchestrator, never crosses a service
 * boundary.
 */
public class WatchlistTriggerSessionWorkflowInput implements Serializable {

  private static final long serialVersionUID = 1L;

  private WatchlistMirrorPayload source;
  private StrategyConfig config;

  public WatchlistTriggerSessionWorkflowInput() {}

  public WatchlistTriggerSessionWorkflowInput(
      WatchlistMirrorPayload source, StrategyConfig config) {
    this.source = source;
    this.config = config;
  }

  public WatchlistMirrorPayload getSource() {
    return source;
  }

  public void setSource(WatchlistMirrorPayload source) {
    this.source = source;
  }

  public StrategyConfig getConfig() {
    return config;
  }

  public void setConfig(StrategyConfig config) {
    this.config = config;
  }
}
