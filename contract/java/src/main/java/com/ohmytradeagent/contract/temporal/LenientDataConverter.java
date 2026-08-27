package com.ohmytradeagent.contract.temporal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;

/**
 * Issue #772: the shared Temporal {@link DataConverter} for every worker service, differing from
 * the SDK default in exactly one way — Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} is OFF for
 * payload deserialization.
 *
 * <p><b>Why:</b> contract DTOs are workflow inputs and recorded activity results. With the strict
 * default converter, a history that serialized a {@code StrategyConfig} containing a since-removed
 * schema field throws {@code UnrecognizedPropertyException} on replay, which wedges the in-flight
 * workflow (#649 hit exactly this; on per-tenant long-lived workflows it is a real-money incident).
 * Schema fields are therefore un-removable until every worker deserializes leniently. This
 * converter is that leniency, in one place.
 *
 * <p><b>Why here and not the generated POJOs:</b> every contract schema declares {@code
 * "additionalProperties": false}, which the generated POJOs mirror (there is no runtime JSON-Schema
 * validator; the /config write path is DTO-mediated at the gateway binding, so unknown keys are
 * dropped, not rejected) — flipping the schemas (or the jsonschema2pojo {@code
 * includeAdditionalProperties} flag, which the schema-level setting overrides anyway) would make a
 * typo'd config key silently legal at WRITE time. Transport-layer leniency keeps write-side
 * validation strict while letting old histories replay: unknown-at-read is forgiven,
 * unknown-at-write still fails loudly.
 *
 * <p><b>Deployment ordering (the point of the issue):</b> the pod doing the replay is the one that
 * needs the leniency. This must be live on EVERY worker service before any schema field is actually
 * removed (#338, #649 stay blocked until then).
 *
 * <p>The mapper starts from {@link JacksonJsonPayloadConverter#newDefaultObjectMapper()} so every
 * other serialization behavior (JavaTime handling, non-null inclusion) is byte-identical to the SDK
 * default — replay determinism depends on serialized payloads not changing shape.
 */
public final class LenientDataConverter {

  private static final DataConverter INSTANCE =
      DefaultDataConverter.newDefaultInstance()
          .withPayloadConverterOverrides(new JacksonJsonPayloadConverter(lenientMapper()));

  private LenientDataConverter() {}

  /** The process-wide lenient converter; thread-safe, one instance per JVM is intentional. */
  public static DataConverter instance() {
    return INSTANCE;
  }

  private static ObjectMapper lenientMapper() {
    return JacksonJsonPayloadConverter.newDefaultObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }
}
