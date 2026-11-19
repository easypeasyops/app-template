package app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The app as Kubernetes and Prometheus see it: real ports, real HTTP. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@Import(ApplicationTest.Routes.class)
@ExtendWith(OutputCaptureExtension.class)
class ApplicationTest {
  private final HttpClient http = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  int appPort;

  @Value("${local.management.port}")
  int metricsPort;

  /** Routes the template doesn't have, to show how errors and path variables are labelled. */
  @TestConfiguration
  @RestController
  static class Routes {
    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("boom");
    }

    @GetMapping("/items/{id}")
    String item(@PathVariable String id) {
      return id;
    }
  }

  HttpResponse<String> get(int port, String path, String... headers)
      throws IOException, InterruptedException {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    if (headers.length > 0) {
      builder.headers(headers);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void aRequestIdIsSentBackAndKeptFromTheCaller() throws Exception {
    var kept = get(appPort, "/", "X-Request-ID", "abc-123");
    assertThat(kept.headers().firstValue("X-Request-ID")).hasValue("abc-123");
    assertThat(get(appPort, "/").headers().firstValue("X-Request-ID").orElseThrow())
        .matches("[0-9a-f]{32}");
    assertThat(get(appPort, "/", "X-Request-ID", "bad id!").headers().firstValue("X-Request-ID"))
        .isNotEqualTo("bad id!")
        .isPresent();
  }

  @Test
  void eachRequestLogsOneJsonLineWithItsId(CapturedOutput output) throws Exception {
    get(appPort, "/items/7", "X-Request-ID", "trace-me");
    get(appPort, "/boom", "X-Request-ID", "trace-boom");

    String access =
        output
            .getOut()
            .lines()
            .filter(l -> l.contains("\"request_id\":\"trace-me\""))
            .findFirst()
            .orElseThrow();
    assertThat(access)
        .startsWith("{\"ts\":\"")
        .contains("\"level\":\"info\"", "\"logger\":\"app.access\"", "\"msg\":\"request\"")
        .contains("\"method\":\"GET\"", "\"route\":\"/items/{id}\"", "\"status\":200")
        .contains("\"duration_ms\":");
    // An error is one JSON line too, with its stack trace (Tomcat logs it after the request's
    // filters, so without its request_id: the access line has that, with status 500).
    assertThat(output.getOut().lines().filter(l -> l.contains("\"level\":\"error\"")))
        .anySatisfy(l -> assertThat(l).contains("\"exc\":\"java.lang.IllegalStateException: boom"));
    assertThat(output.getOut().lines().filter(l -> l.contains("\"request_id\":\"trace-boom\"")))
        .anySatisfy(l -> assertThat(l).contains("\"status\":500"));
  }

  @Test
  void helloAnswers() throws Exception {
    var response = get(appPort, "/");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"hello\":\"world\"}");
  }

  @Test
  void probesAnswer() throws Exception {
    assertThat(get(appPort, "/healthz").statusCode()).isEqualTo(200);
    assertThat(get(appPort, "/readyz").body()).isEqualTo("ok");
  }

  @Test
  void metricsAreOnTheirOwnPortOnly() throws Exception {
    assertThat(get(appPort, "/metrics").statusCode()).isEqualTo(404);
    assertThat(get(metricsPort, "/metrics").statusCode()).isEqualTo(200);
  }

  @Test
  void requestsAreCountedByRouteWithTheNamesThePlatformReads() throws Exception {
    get(appPort, "/readyz");
    get(appPort, "/no-such-path");
    get(appPort, "/boom");
    get(appPort, "/items/42");

    String metrics = get(metricsPort, "/metrics").body();
    assertThat(metrics)
        .contains("http_requests_total{method=\"GET\",route=\"/readyz\",status=\"200\"}")
        .contains("http_requests_total{method=\"GET\",route=\"unmatched\",status=\"404\"}")
        .contains("http_requests_total{method=\"GET\",route=\"/boom\",status=\"500\"}")
        .contains("http_requests_total{method=\"GET\",route=\"/items/{id}\",status=\"200\"}")
        // The SLO's latency threshold (0.5 s) is a bucket edge.
        .contains(
            "http_request_duration_seconds_bucket{method=\"GET\",route=\"/readyz\",le=\"0.5\"}")
        .doesNotContain("route=\"/items/42\"");
  }
}
