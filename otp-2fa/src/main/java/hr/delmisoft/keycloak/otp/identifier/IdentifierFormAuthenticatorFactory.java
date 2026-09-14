package hr.delmisoft.keycloak.otp.identifier;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

public class IdentifierFormAuthenticatorFactory implements AuthenticatorFactory {

    private static final IdentifierFormAuthenticator INSTANCE = new IdentifierFormAuthenticator();

    @Override
    public String getId() {
        return IdentifierFormConst.PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "OTP Identifier Form (email / phone / username)";
    }

    @Override
    public String getReferenceCategory() {
        return "otp";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Accepts an email, phone number, or username, resolves the user, and stores the detected identifier type for downstream OTP routing.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return INSTANCE;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
