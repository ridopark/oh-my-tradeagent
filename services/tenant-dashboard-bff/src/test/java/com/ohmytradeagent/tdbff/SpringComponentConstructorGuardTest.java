package com.ohmytradeagent.tdbff;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
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
    // false = no default filters, so only the @Component filter below applies. AnnotationTypeFilter
    // follows meta-annotations by default, so @Service/@RestController/@Repository/@Configuration
    // are all included — they are all @Component underneath, and all fail refresh the same way.
    var provider = new ClassPathScanningCandidateComponentProvider(false);
    provider.addIncludeFilter(new AnnotationTypeFilter(Component.class));

    // EVERY PROPERTY READS "true", AND WITHOUT THIS THE GUARD IS WORSE THAN ABSENT. The scanner
    // evaluates @Conditional as it goes, so against an empty Environment every
    // @ConditionalOnProperty bean is silently dropped from the scan — the guard then passes with a
    // confident green while checking none of the beans it exists to check. Verified: removing
    // @Autowired from OptionsChatRetention left this test passing until this block was added.
    //
    // That category is not incidental, it is the whole target. Both real incidents were beans
    // behind flags that are false by default and true only on the cluster, which is precisely why
    // neither CI nor the context smoke tests caught them.
    StandardEnvironment allFlagsOn = new StandardEnvironment();
    allFlagsOn
        .getPropertySources()
        .addFirst(
            new PropertySource<Object>("every-condition-satisfied") {
              @Override
              public Object getProperty(String name) {
                return "true";
              }
            });
    provider.setEnvironment(allFlagsOn);

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

    assertThat(violations)
        .as(
            "Spring cannot choose between multiple constructors unless exactly one is @Autowired; "
                + "it falls back to a no-arg constructor that does not exist and aborts context "
                + "refresh. Annotate the injectable constructor.")
        .isEmpty();
  }
}
