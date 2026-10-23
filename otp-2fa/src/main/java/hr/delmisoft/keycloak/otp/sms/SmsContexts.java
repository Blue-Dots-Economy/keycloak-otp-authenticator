package hr.delmisoft.keycloak.otp.sms;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.models.ClientModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

public final class SmsContexts {

    private SmsContexts() {
    }

    public static SmsContext build(AuthenticationFlowContext context, String phoneVerifiedAttribute) {
        UserModel user = context.getUser();
        boolean phoneVerified = "true".equalsIgnoreCase(user.getFirstAttribute(phoneVerifiedAttribute));
        boolean verified = phoneVerified || user.isEmailVerified();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        ClientModel client = authSession == null ? null : authSession.getClient();
        Map<String, String> variables = new LinkedHashMap<>();
        String url = authSession == null ? null : urlOrigin(authSession.getRedirectUri());
        if (url == null) {
            url = clientAppUrl(client);
        }
        if (url != null) {
            variables.put("url", url);
        }
        return new SmsContext(verified ? SmsContext.SIGNIN : SmsContext.SIGNUP,
                client == null ? null : client.getClientId(), variables, phoneVerified);
    }

    static String urlOrigin(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI parsed = URI.create(uri.trim());
            String scheme = parsed.getScheme();
            if (parsed.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
                return null;
            }
            return scheme + "://" + parsed.getHost() + (parsed.getPort() == -1 ? "" : ":" + parsed.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String clientAppUrl(ClientModel client) {
        if (client == null) {
            return null;
        }
        String base = client.getBaseUrl();
        String url = base != null && base.startsWith("http") ? base : client.getRootUrl();
        if (url == null || url.isBlank() || !url.startsWith("http")) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
