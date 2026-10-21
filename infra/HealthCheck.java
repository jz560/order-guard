import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
class HealthCheck {
  public static void main(String[] args) throws Exception {
    var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
        HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200 || !response.body().contains("\"status\":\"UP\"")) {
      System.exit(1);
    }
  }
}
