// Pure derivations from a broker_target string (e.g. "alpaca-live"). The paper/live mode is the
// single most operationally important fact on the status page — "is this tenant trading real
// money?" — and it is encoded entirely in the broker_target suffix, so no extra BFF data is needed.

export type BrokerMode = "live" | "paper" | "unknown";

export function brokerMode(brokerTarget: string): BrokerMode {
  if (brokerTarget.endsWith("-live") || brokerTarget === "live") {
    return "live";
  }
  if (brokerTarget.endsWith("-paper") || brokerTarget === "paper") {
    return "paper";
  }
  return "unknown";
}

// Provider is the segment before the first '-' (e.g. "alpaca-live" → "alpaca"). The legacy bare
// "paper"/"live" values have no provider segment and return unchanged.
export function brokerProvider(brokerTarget: string): string {
  const dash = brokerTarget.indexOf("-");
  return dash < 0 ? brokerTarget : brokerTarget.slice(0, dash);
}
