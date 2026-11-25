package dev.ushio.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class FnClientLoginTest {
    private static final String V2 = "/v/api/v2/user/loginByPassword";
    private static final String V1 = "/v/api/v1/login";
    private static final String LIBRARIES = "/v/api/v1/mediadb/list";
    private static final String TOKEN = "test-session";

    @Test public void usesWebSigningForPasswordAndLibraries() throws Exception {
        try (Server server = new Server("web", "web", false)) {
            FnClient.Session session = FnClient.login(server.address, "demo", "smokepass");

            assertTrue(session.signed);
            assertFalse(session.legacy);
            assertEquals(List.of(V2 + " web", LIBRARIES + " web"), server.calls);
        }
    }

    @Test public void retriesAnEnglishSignatureErrorWithoutResubmittingCredentials() throws Exception {
        try (Server server = new Server("web", "token", false)) {
            FnClient.Session session = FnClient.login(server.address, "demo", "smokepass");

            assertFalse(session.signed);
            assertEquals(TOKEN, session.token);
            assertEquals(List.of(V2 + " web", LIBRARIES + " web", LIBRARIES + " token"), server.calls);
        }
    }

    @Test public void handlesSignatureErrorAtLoginOnTokenSigningServers() throws Exception {
        try (Server server = new Server("token", "token", false)) {
            FnClient.Session session = FnClient.login(server.address, "demo", "smokepass");

            assertFalse(session.signed);
            assertEquals(List.of(V2 + " web", V2 + " token", LIBRARIES + " token"), server.calls);
        }
    }

    @Test public void preservesLegacyLoginWhenV2IsUnavailable() throws Exception {
        try (Server server = new Server("legacy", "web", false)) {
            FnClient.Session session = FnClient.login(server.address, "demo", "smokepass");

            assertTrue(session.legacy);
            assertEquals(List.of(V2 + " web", V1 + " web", LIBRARIES + " web"), server.calls);
        }
    }

    @Test public void doesNotRetryAnIncorrectPassword() throws Exception {
        try (Server server = new Server("web", "web", true)) {
            FnClient.ApiError error = assertThrows(FnClient.ApiError.class,
                    () -> FnClient.login(server.address, "demo", "smokepass"));

            assertEquals(-15, error.code);
            assertEquals(List.of(V2 + " web"), server.calls);
        }
    }

    private static final class Server implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final String address;
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final String loginMode, readMode;
        final boolean rejectPassword;

        Server(String loginMode, String readMode, boolean rejectPassword) throws IOException {
            this.loginMode = loginMode;
            this.readMode = readMode;
            this.rejectPassword = rejectPassword;
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) { return respond(request); }
            });
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            address = server.url("/v").toString();
        }

        private MockResponse respond(RecordedRequest request) {
            String path = request.getRequestUrl().encodedPath();
            String body = request.getBody().readUtf8();
            String authorization = request.getHeader("Authorization");
            String mode = signingMode(request.getHeader("Authx"), path, body, authorization == null ? "" : authorization);
            calls.add(path + " " + mode);
            if (path.equals(V2) && loginMode.equals("legacy")) return reply(404, "not found", "null");
            if (path.equals(V2) || path.equals(V1)) {
                String expectedMode = path.equals(V1) ? "web" : loginMode;
                if (!mode.equals(expectedMode)) return reply(5000, "invalid sign", "null");
                JSONObject payload;
                try {
                    payload = new JSONObject(body);
                } catch (JSONException invalid) {
                    return reply(-1, "Invalid Params", "null");
                }
                String expectedPassword = path.equals(V1) ? "smokepass" : FnClient.hash("SHA-256", "smokepass");
                boolean valid = payload.optString("username").equals("demo")
                        && payload.optString("password").equals(expectedPassword)
                        && payload.optString("app_name").equals("trimemedia-web")
                        && (!path.equals(V1) || payload.optString("nonce").matches("\\d{6}"));
                if (!valid || rejectPassword) return reply(-15, "Password Incorrect", "null");
                return reply(0, "", "{\"token\":\"" + TOKEN + "\"}");
            }
            if (path.equals(LIBRARIES) && TOKEN.equals(authorization)) {
                return mode.equals(readMode) ? reply(0, "", "[]") : reply(5000, "invalid sign", "null");
            }
            return reply(401, "Auth Failed", "null");
        }

        private String signingMode(String auth, String path, String body, String token) {
            if (auth == null) return "missing";
            String[] fields = auth.split("&");
            if (fields.length != 3) return "invalid";
            String nonce = fields[0].substring("nonce=".length());
            String time = fields[1].substring("timestamp=".length());
            String actual = fields[2].substring("sign=".length());
            String web = FnClient.hash("MD5", String.join("_", "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh",
                    path, nonce, time, FnClient.hash("MD5", body), "16CCEB3D-AB42-077D-36A1-F355324E4237"));
            if (web.equals(actual)) return "web";
            if (FnClient.hash("MD5", nonce + time + token).equals(actual)) return "token";
            return "invalid";
        }

        private MockResponse reply(int code, String message, String data) {
            String json = "{\"code\":" + code + ",\"msg\":" + JSONObject.quote(message) + ",\"data\":" + data + "}";
            return new MockResponse().setResponseCode(code == 404 ? 404 : 200)
                    .setHeader("Content-Type", "application/json").setBody(json);
        }

        @Override public void close() throws IOException { server.close(); }
    }
}
