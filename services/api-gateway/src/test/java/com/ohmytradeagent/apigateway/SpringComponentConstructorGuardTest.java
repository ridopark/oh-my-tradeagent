package com.ohmytradeagent.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.stereotype.Component;

/**
 * Every scanned component must leave Spring able to pick a constructor.
 *
 * <p>A {@code @Component} declaring more than one constructor with none annotated
 * {@code @Autowired} compiles, passes every unit test, and then fails at context refresh: Spring
 * stops choosing, falls back to a no-arg constructor, finds none, and throws {@code
 * NoSuchMethodException: <init>()}. It has shipped twice — {@code PortfolioHistoryClient} (PR #486)
 * CrashLoopBackOff'd the homelab, and {@code OptionsChatRetention} (PR #683) repeated it.
 *
 * <p>Both earlier guards were per-class assertions naming their one target, which is why the second
 * occurrence was invisible to them. This scans the module instead, so it covers classes nobody
 * remembered to name and beans no test happens to enable.
 *
 * <p>See {@code com.ohmytradeagent.tdbff.SpringComponentConstructorGuardTest} for the full account;
 * this is the same check over {@code com.ohmytradeagent.apigateway}.
 */
class SpringComponentConstructorGuardTest {

  private static final String SCANNED_PACKAGE = "com.ohmytradeagent.apigateway";

  @Test
  void everyComponentWithMoreThanOneConstructorDeclaresExactlyOneAutowired() throws Exception {
    // CONDITION EVALUATION IS BYPASSED, AND THAT IS LOAD-BEARING. The scanner normally evaluates
    // @Conditional as it walks, so beans behind a false-by-default flag are dropped and the guard
    // passes green while checking none of the beans it exists to check — which is precisely the
    // category both incidents came from. Overriding the candidate test makes this independent of
    // the condition FORM; an Environment answering "true" to everything is NOT equivalent (it
    // drops @ConditionalOnProperty with any other havingValue, and cannot see past
    // @ConditionalOnExpression).
    var provider =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(MetadataReader reader) {
            var metadata = reader.getAnnotationMetadata();
            return metadata.hasAnnotation(Component.class.getName())
                || metadata.hasMetaAnnotation(Component.class.getName());
          }
        };

    List<String> violations = new ArrayList<>();
    var candidates = provider.findCandidateComponents(SCANNED_PACKAGE);

    for (var definition : candidates) {
      Class<?> type = Class.forName(definition.getBeanClassName());
      List<Constructor<?>> ctors =
          Arrays.stream(type.getDeclaredConstructors()).filter(c -> !c.isSynthetic()).toList();
      if (ctors.size() <= 1) {
        continue;
      }
      long autowired = ctors.stream().filter(c -> c.isAnnotationPresent(Autowired.class)).count();
      if (autowired != 1) {
        violations.add(
            "%s declares %d constructors but %d are @Autowired"
                .formatted(type.getName(), ctors.size(), autowired));
      }
    }

    // Guard the guard: a scan that matched nothing would be permanently, uselessly green.
    assertThat(candidates)
        .as("component scan of %s found no components — the scan itself is broken", SCANNED_PACKAGE)
        .isNotEmpty();

    assertThat(violations)
        .as(
            "Spring will not pick between multiple constructors on its own. Annotate the injectable "
                + "one with @Autowired — otherwise it falls back to a no-arg constructor, and where "
                + "none exists the context fails to start.")
        .isEmpty();
  }
}
