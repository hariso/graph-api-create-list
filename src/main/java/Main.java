import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.models.extensions.IGraphServiceClient;
import com.microsoft.graph.models.extensions.ListInfo;
import com.microsoft.graph.models.extensions.Site;
import com.microsoft.graph.requests.extensions.GraphServiceClient;
import com.microsoft.graph.requests.extensions.IListCollectionPage;

import org.apache.commons.lang3.time.DateUtils;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Key;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import io.jsonwebtoken.Jwts;

import static java.lang.String.format;

public class Main {
    static ObjectReader reader = new ObjectMapper().reader();
    static String appId = "test";
    static String appX5t = "test";
    static String appPrivateKeyPath = "test_rsa_private_pkcs8";
    static String tenant = "test.onmicrosoft.com";
    static String spTenant = "test.sharepoint.com";
    static String scope = "https://graph.microsoft.com/.default";

    public static void main(String[] args) throws Exception {
        var accessToken = fetchGraphApiToken();
        var graph = buildGraphClient(accessToken);

        // get some lists
        final IListCollectionPage page = graph.sites(spTenant).lists().buildRequest().get();
        page.getCurrentPage().forEach(l -> System.out.println(l.displayName));

        // create list
        final Site rootSite = graph.sites(spTenant).buildRequest().get();
        var newList = new com.microsoft.graph.models.extensions.List();
        newList.displayName = "Created by the Graph API" + System.currentTimeMillis();
        newList.list = new ListInfo();
        newList.list.template = "documentLibrary";

        var created = graph.sites(rootSite.id).lists().buildRequest().post(newList);
        System.out.println(created.id);
    }

    static String fetchGraphApiToken() throws Exception {
        return getAuthTokenUsingClientAssertion();
    }

    static String getAuthTokenUsingClientAssertion() throws Exception {
        CloseableHttpClient client = HttpClientBuilder.create().build();
        final String authUrl = format("https://login.microsoftonline.com/%s/oauth2/v2.0/token", tenant);

        HttpPost request = new HttpPost(authUrl);

        request.addHeader("Content-Type", "application/x-www-form-urlencoded");
        request.addHeader("cache-control", "no-cache");

        List<NameValuePair> nvps = List.of(
                new BasicNameValuePair("client_id", appId),
                new BasicNameValuePair("scope", scope),
                new BasicNameValuePair("grant_type", "client_credentials"),
                new BasicNameValuePair("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"),
                new BasicNameValuePair("client_assertion", generateAzureAdJwt())
        );
        request.setEntity(new UrlEncodedFormEntity(nvps, StandardCharsets.UTF_8));

        try (CloseableHttpResponse response = client.execute(request)) {
            final JsonNode jsonNode = toJson(response);
            return jsonNode.get("access_token").textValue();
        }
    }

    static String generateAzureAdJwt() throws Exception {
        return Jwts.builder()
                .setHeaderParam("alg", "RS256")
                .setHeaderParam("typ", "JWT")
                .setHeaderParam("x5t", appX5t)
                .setAudience(format("https://login.microsoftonline.com/%s/oauth2/token", tenant))
                .setExpiration(getAzureAdJwtExpiration())
                .setIssuer(appId)
                .setId(UUID.randomUUID().toString())
                .setNotBefore(getAzureAdJwtNbf())
                .setSubject(appId)
                .signWith(getEncodingKey(appPrivateKeyPath))
                .compact();
    }

    static Date getAzureAdJwtNbf() {
        return DateUtils.addSeconds(new Date(), -1);
    }

    static Key getEncodingKey(String derFile) throws Exception {
        byte[] privKeyByteArray = Files.readAllBytes(Paths.get(derFile));

        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privKeyByteArray);

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");

        return keyFactory.generatePrivate(keySpec);
    }

    static Date getAzureAdJwtExpiration() {
        return DateUtils.addDays(new Date(), 1);
    }

    static JsonNode toJson(HttpResponse response) throws IOException {
        return reader.readTree(response.getEntity().getContent());
    }

    static IGraphServiceClient buildGraphClient(String accessToken) {
        IAuthenticationProvider authenticationProvider = request -> {
            try {
                request.addHeader("Authorization", "Bearer " + accessToken);
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
        return GraphServiceClient
                .builder()
                .authenticationProvider(authenticationProvider)
                .buildClient();
    }
}
