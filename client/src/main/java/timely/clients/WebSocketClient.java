package timely.clients;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;

import javax.net.ssl.SSLContext;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import org.glassfish.tyrus.client.SslEngineConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import timely.api.AddSubscription;
import timely.api.BasicAuthLogin;
import timely.api.CloseSubscription;
import timely.api.CreateSubscription;
import timely.api.RemoveSubscription;
import timely.client.websocket.SubscriptionClientHandler;
import timely.client.websocket.TimelyEndpointConfig;
import timely.serialize.JsonSerializer;

import com.google.common.base.Preconditions;

public class WebSocketClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketClient.class);

    private final String timelyHostname;
    private final int timelyHttpsPort;
    private final int timelyWssPort;
    private final boolean doLogin;
    private final String timelyUsername;
    private final String timelyPassword;
    private final boolean hostVerificationEnabled;
    private final int bufferSize;
    private final SSLContext ssl;

    private ClientManager webSocketClient = null;
    private Session session = null;
    private final String subscriptionId;
    private volatile boolean closed = true;

    public WebSocketClient(SSLContext ssl, String timelyHostname, int timelyHttpsPort, int timelyWssPort,
            boolean doLogin, String timelyUsername, String timelyPassword, boolean hostVerificationEnabled,
            int bufferSize) {
        this.ssl = ssl;
        this.timelyHostname = timelyHostname;
        this.timelyHttpsPort = timelyHttpsPort;
        this.timelyWssPort = timelyWssPort;
        this.doLogin = doLogin;
        this.timelyUsername = timelyUsername;
        this.timelyPassword = timelyPassword;
        this.hostVerificationEnabled = hostVerificationEnabled;
        this.bufferSize = bufferSize;

        Preconditions.checkNotNull(timelyHostname, "%s must be supplied", "Timely host name");
        Preconditions.checkNotNull(timelyHttpsPort, "%s must be supplied", "Timely HTTPS port");
        Preconditions.checkNotNull(timelyWssPort, "%s must be supplied", "Timely WSS port");

        if (doLogin
                && ((StringUtils.isEmpty(timelyUsername) && !StringUtils.isEmpty(timelyPassword) || (!StringUtils
                        .isEmpty(timelyUsername) && StringUtils.isEmpty(timelyPassword))))) {
            throw new IllegalArgumentException("Both Timely username and password must be empty or non-empty");
        }

        subscriptionId = UUID.randomUUID().toString();
        LOG.trace("Created WebSocketClient with subscriptionId {}", this.subscriptionId);
    }

    public WebSocketClient(String timelyHostname, int timelyHttpsPort, int timelyWssPort, boolean doLogin,
            String timelyUsername, String timelyPassword, String keyStoreFile, String keyStoreType,
            String keyStorePass, String trustStoreFile, String trustStoreType, String trustStorePass,
            boolean hostVerificationEnabled, int bufferSize) {
        this(HttpClient.getSSLContext(trustStoreFile, trustStoreType, trustStorePass, keyStoreFile, keyStoreType,
                keyStorePass), timelyHostname, timelyHttpsPort, timelyWssPort, doLogin, timelyUsername, timelyPassword,
                hostVerificationEnabled, bufferSize);
    }

    public void open(SubscriptionClientHandler clientEndpoint) throws IOException, DeploymentException,
            URISyntaxException {

        Cookie sessionCookie = null;
        if (doLogin) {
            BasicCookieStore cookieJar = new BasicCookieStore();
            try (CloseableHttpClient client = HttpClient.get(ssl, cookieJar, hostVerificationEnabled)) {

                String target = "https://" + timelyHostname + ":" + timelyHttpsPort + "/login";

                HttpRequestBase request = null;
                if (StringUtils.isEmpty(timelyUsername)) {
                    // HTTP GET to /login to use certificate based login
                    request = new HttpGet(target);
                    LOG.trace("Performing client certificate login");
                } else {
                    // HTTP POST to /login to use username/password
                    BasicAuthLogin login = new BasicAuthLogin();
                    login.setUsername(timelyUsername);
                    login.setPassword(timelyPassword);
                    String payload = JsonSerializer.getObjectMapper().writeValueAsString(login);
                    HttpPost post = new HttpPost(target);
                    post.setEntity(new StringEntity(payload));
                    request = post;
                    LOG.trace("Performing BasicAuth login");
                }

                HttpResponse response = null;
                try {
                    response = client.execute(request);
                    if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                        throw new HttpResponseException(response.getStatusLine().getStatusCode(), response
                                .getStatusLine().getReasonPhrase());
                    }
                    for (Cookie c : cookieJar.getCookies()) {
                        if (c.getName().equals("TSESSIONID")) {
                            sessionCookie = c;
                            break;
                        }
                    }
                    if (null == sessionCookie) {
                        throw new IllegalStateException(
                                "Unable to find TSESSIONID cookie header in Timely login response");
                    }
                } finally {
                    HttpClientUtils.closeQuietly(response);
                }
            }
        }

        SslEngineConfigurator sslEngine = new SslEngineConfigurator(ssl);
        sslEngine.setClientMode(true);
        sslEngine.setHostVerificationEnabled(hostVerificationEnabled);

        webSocketClient = ClientManager.createClient();
        webSocketClient.getProperties().put(ClientProperties.SSL_ENGINE_CONFIGURATOR, sslEngine);
        webSocketClient.getProperties().put(ClientProperties.INCOMING_BUFFER_SIZE, bufferSize);
        String wssPath = "wss://" + timelyHostname + ":" + timelyWssPort + "/websocket";
        session = webSocketClient.connectToServer(clientEndpoint, new TimelyEndpointConfig(sessionCookie), new URI(
                wssPath));
        CreateSubscription create = new CreateSubscription();
        create.setSubscriptionId(subscriptionId);
        session.getBasicRemote().sendText(JsonSerializer.getObjectMapper().writeValueAsString(create));
        closed = false;
    }

    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            if (null != session) {
                CloseSubscription close = new CloseSubscription();
                close.setSubscriptionId(subscriptionId);
                try {
                    session.getBasicRemote().sendText(JsonSerializer.getObjectMapper().writeValueAsString(close));
                    session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Client called close."));
                } catch (Exception e) {
                    LOG.info("Unable to send close message to server: {}", e.getMessage());
                }
            }
            if (null != webSocketClient) {
                webSocketClient.shutdown();
            }
        } finally {
            session = null;
            webSocketClient = null;
            closed = true;
        }
    }

    public Future<Void> addSubscription(String metric, Map<String, String> tags, long startTime, long endTime,
            long delayTime) throws IOException {
        AddSubscription add = new AddSubscription();
        add.setSubscriptionId(subscriptionId);
        add.setMetric(metric);
        add.setTags(Optional.ofNullable(tags));
        add.setStartTime(Optional.ofNullable(startTime));
        add.setEndTime(Optional.ofNullable(endTime));
        add.setDelayTime(Optional.ofNullable(delayTime));
        return session.getAsyncRemote().sendText(JsonSerializer.getObjectMapper().writeValueAsString(add));
    }

    public Future<Void> removeSubscription(String metric) throws Exception {
        RemoveSubscription remove = new RemoveSubscription();
        remove.setSubscriptionId(subscriptionId);
        remove.setMetric(metric);
        return session.getAsyncRemote().sendText(JsonSerializer.getObjectMapper().writeValueAsString(remove));
    }

    public boolean isClosed() {
        return closed;
    }

}
