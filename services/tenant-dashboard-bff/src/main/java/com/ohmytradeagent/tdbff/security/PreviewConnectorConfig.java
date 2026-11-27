package com.ohmytradeagent.tdbff.security;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A second HTTP port that serves the public-demo projections and nothing else ({@link
 * ServiceTokenFilter} enforces what is served; this class only opens the door).
 *
 * <p>The demo pod is internet-facing, so it is assumed compromised. Its NetworkPolicy admits it to
 * this port alone, which keeps it away from the main port's unauthenticated {@code /actuator/**},
 * and the connector has its own two request threads and a handful of connections, so a flood from
 * that pod cannot take the request threads operators' actions on live tenants run on.
 */
@Configuration
class PreviewConnectorConfig {

  // One poller making sequential calls needs one thread; the second is headroom for an overlap.
  static final int MAX_THREADS = 2;
  static final int MAX_CONNECTIONS = 8;
  static final int ACCEPT_COUNT = 8;

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> previewConnector(
      @Value("${bff.preview.port:0}") int port) {
    return factory -> {
      if (port <= 0) {
        return;
      }
      Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
      connector.setPort(port);
      connector.setProperty("maxThreads", String.valueOf(MAX_THREADS));
      connector.setProperty("minSpareThreads", "1");
      connector.setProperty("maxConnections", String.valueOf(MAX_CONNECTIONS));
      connector.setProperty("acceptCount", String.valueOf(ACCEPT_COUNT));
      factory.addAdditionalTomcatConnectors(connector);
    };
  }
}
