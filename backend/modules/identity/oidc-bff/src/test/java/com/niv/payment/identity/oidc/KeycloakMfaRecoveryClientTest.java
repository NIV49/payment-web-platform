package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.MfaRecoveryStep;
import com.niv.payment.identity.lifecycle.MfaRecoveryTask;
import com.niv.payment.permission.domain.AccountDomain;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakMfaRecoveryClientTest {
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private KeycloakMfaRecoveryClient client;
    private URI issuer;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        issuer = root.resolve("/realms/MERCHANT");
        client = new KeycloakMfaRecoveryClient(RestClient.create(), new KeycloakAdminSettings(
            issuer,
            root.resolve("/realms/MERCHANT/protocol/openid-connect/token"),
            root.resolve("/admin/realms/MERCHANT"), "MERCHANT", "lifecycle", "private"),
            Duration.ofHours(1));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void recoveryCodeRevocationSendsBoundedTotpReEnrollmentAction() {
        client.revokeRecoveryCodes(task());

        assertThat(requests).anySatisfy(request -> {
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.path()).endsWith("/credentials/recovery-credential");
        });
        assertThat(requests).anySatisfy(request -> {
            assertThat(request.method()).isEqualTo("PUT");
            assertThat(request.path()).isEqualTo(
                "/admin/realms/MERCHANT/users/subject-1/execute-actions-email?lifespan=3600");
            assertThat(request.body()).isEqualTo("[\"CONFIGURE_TOTP\"]");
        });
    }

    private void respond(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body));
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/protocol/openid-connect/token")) {
            send(exchange, 200, "{\"access_token\":\"access-token\"}");
            return;
        }
        if (path.endsWith("/credentials") && "GET".equals(exchange.getRequestMethod())) {
            send(exchange, 200,
                "[{\"id\":\"recovery-credential\",\"type\":\"recovery-authn-codes\"}]");
            return;
        }
        send(exchange, 204, "");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private MfaRecoveryTask task() {
        return new MfaRecoveryTask(1, 2, 3, 4, 5, AccountDomain.MERCHANT,
            issuer.toString(), "subject-1", List.of(5L), 1,
            MfaRecoveryStep.RECOVERY_CODES);
    }

    private record Request(String method, String path, String body) { }
}
