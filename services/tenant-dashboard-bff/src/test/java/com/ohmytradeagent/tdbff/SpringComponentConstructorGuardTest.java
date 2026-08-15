package com.ohmytradeagent.tdbff;

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
 * Every scanned component must leave Spring able to pick a constructor. Whole-package, so it also
 * covers beans no test happens to enable.
 *
 * <p>THIS BUG CLASS HAS SHIPPED TWICE. A {@code @Component} declaring more than one constructor
 * with none annotated {@code @Autowired} compiles, passes every unit test, and then fails at
 * context refresh: Spring stops choosing, falls back to a no-arg constructor, finds none, and
 * throws {@code NoSuchMethodException: <init>()}. {@code PortfolioHistoryClient} did it in PR #486
 * and CrashLoopBackOff'd the homelab; {@code OptionsChatRetention} repeated it in PR #683 and was
 * caught only in review, after merge.
 *
 * <p>WHY THE EXISTING GUARDS COULD NOT CATCH THE SECOND ONE. The response to #486 was a per-class
 * assertion naming {@code PortfolioHistoryClient}, hand-copied a second time in market-data for
 * {@code AlpacaMarketData}. A guard that names its target can only ever protect what someone
 * remembered to name — the third occurrence was invisible to both. The context smoke tests are
 * broader but stop at configuration: a bean behind a {@code @ConditionalOnProperty} that is false
 * there is never constructed and so is never checked, which is exactly the gap #683 fell through.
 * Scanning the package depends on neither memory nor flags.
 *
 * <p>Declared constructors are counted, including private and package-private ones, because that is
 * what Spring counts — {@code AutowiredAnnotationBeanPostProcessor} reads {@code
 * getDeclaredConstructors()}. Both real incidents were a public injectable constructor beside a
 * package-private one taking a {@code Clock} for tests, so a visibility-filtered check would have
 * missed both.
 */
class SpringComponentConstructorGuardTest {

  private static final String SCANNED_PACKAGE = "com.ohmytradeagent.tdbff";

  @Test
  void everyComponentWithMoreThanOneConstructorDeclaresExactlyOneAutowired() throws Exception {
    // CONDITION EVALUATION IS BYPASSED ENTIRELY, AND THAT IS THE WHOLE TRICK. The scanner normally
    // evaluates @Conditional as it walks, so against a default Environment every
    // @ConditionalOnProperty bean is silently dropped and the guard passes a confident green while
    // checking none of the beans it exists to check. That category is not incidental — it is the
    // target. Both real incidents were beans behind flags that are false by default and true only
    // on the cluster, which is exactly why neither CI nor the context smoke tests caught them.
    //
    // Overriding the candidate test is what makes this independent of every condition FORM. The
    // obvious alternative — an Environment answering "true" to every property — only satisfies
    // @ConditionalOnProperty(havingValue = "true"), and measurably backfires elsewhere: it drops
    // beans whose havingValue is something else (exec's "db"/"file"/"stub" sources, market-data's
    // AlpacaMarketData at havingValue = "alpaca" — the very class the sibling per-class guard
    // exists to protect), scoring 17/33 on exec where even an empty Environment scores 18/33. It
    // also cannot see past @ConditionalOnExpression, which the standalone provider skips silently
    // because it has no BeanFactory (7 such classes in api-gateway).
    //
    // Measured coverage this way: 48/48 here, and 33/33, 33/33, 12/12 in api-gateway, exec and
    // market-data — so this is copy-pasteable to the modules where the bug actually shipped.
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
      // Synthetic constructors (instrumentation, compiler-generated bridges) are not candidates
      // Spring would choose between, so counting them would raise false alarms.
      List<Constructor<?>> ctors =
          Arrays.stream(type.getDeclaredConstructors()).filter(c -> !c.isSynthetic()).toList();

      if (ctors.size() <= 1) {
        continue; // A single constructor is used implicitly; the annotation is optional.
      }
      long autowired = ctors.stream().filter(c -> c.isAnnotationPresent(Autowired.class)).count();
      if (autowired != 1) {
        violations.add(
            "%s declares %d constructors but %d are @Autowired"
                .formatted(type.getName(), ctors.size(), autowired));
      }
    }

    // Guard the guard: a scan that silently matched nothing would be permanently, uselessly green.
    assertThat(candidates)
        .as("component scan of %s found no components — the scan itself is broken", SCANNED_PACKAGE)
        .isNotEmpty();

    // The rule is deliberately TIGHTER than Spring's, so it can over-report but never under-report.
    // Spring's own behaviour: at most one @Autowired(required=true), any number of required=false,
    // and with none annotated it falls back to the no-arg constructor — fatal only when there is no
    // no-arg constructor. Both shapes this rejects but Spring tolerates (two required=false
    // constructors; a no-arg sitting beside an injectable one) are absent here and are poor style
    // anyway, so the message says what to do rather than asserting one mechanism.
    assertThat(violations)
        .as(
            "Spring will not pick between multiple constructors on its own. Annotate the injectable "
                + "one with @Autowired — otherwise it falls back to a no-arg constructor, and where "
                + "none exists the context fails to start.")
        .isEmpty();
  }
}
