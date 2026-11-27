package com.ohmytradeagent.tdbff.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

class PreviewConnectorConfigTest {

  private static TomcatServletWebServerFactory customized(int port) {
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
    new PreviewConnectorConfig().previewConnector(port).customize(factory);
    return factory;
  }

  @Test
  void noPortConfigured_opensNoExtraConnector() {
    assertThat(customized(0).getAdditionalTomcatConnectors()).isEmpty();
  }

  @Test
  void aConfiguredPort_opensOneSmallConnector() {
    assertThat(customized(8085).getAdditionalTomcatConnectors()).hasSize(1);
    Connector c = customized(8085).getAdditionalTomcatConnectors().get(0);
    assertThat(c.getPort()).isEqualTo(8085);
    // Small on purpose: this pool is all a compromised demo pod can occupy.
    assertThat(c.getProperty("maxThreads")).hasToString("2");
    assertThat(c.getProperty("maxConnections")).hasToString("8");
  }

  /**
   * The filter tells the two doors apart by {@code getLocalPort()}. This runs a real server to
   * prove a request arriving on the preview connector reports the preview port, and one arriving on
   * the main connector does not.
   */
  @Test
  void aRequestReportsThePortOfTheConnectorItArrivedOn() throws Exception {
    int previewPort;
    try (ServerSocket free = new ServerSocket(0)) {
      previewPort = free.getLocalPort();
    }
    TomcatServletWebServerFactory factory = customized(previewPort);
    factory.setPort(0);
    WebServer server =
        factory.getWebServer(
            ctx ->
                ctx.addServlet(
                        "port",
                        new HttpServlet() {
                          @Override
                          protected void doGet(HttpServletRequest req, HttpServletResponse res)
                              throws IOException {
                            res.getWriter().print(req.getLocalPort());
                          }
                        })
                    .addMapping("/"));
    server.start();
    try {
      assertThat(get(previewPort)).isEqualTo(String.valueOf(previewPort));
      assertThat(get(server.getPort())).isEqualTo(String.valueOf(server.getPort()));
      assertThat(server.getPort()).isNotEqualTo(previewPort);
    } finally {
      server.stop();
    }
  }

  private static String get(int port) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
            HttpResponse.BodyHandlers.ofString())
        .body();
  }
}
