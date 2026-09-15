package hr.delmisoft.keycloak.otp.identifier;

import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticationExecutionModel;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItemInArray;

class IdentifierFormAuthenticatorFactoryTest {

    private final IdentifierFormAuthenticatorFactory factory = new IdentifierFormAuthenticatorFactory();

    @Test
    void getId_isTheProviderIdTheRealmBindsTo() {
        // The realm's browser flow references this id by name; a rename fails
        // realm import rather than degrading to password login.
        assertThat(factory.getId(), org.hamcrest.Matchers.equalTo(IdentifierFormConst.PROVIDER_ID));
    }

    @Test
    void getRequirementChoices_doesNotOfferDisabled() {
        // The regression this pins. Deleting the local array in favour of the
        // inherited ConfigurableAuthenticatorFactory.REQUIREMENT_CHOICES silently
        // ADDS DISABLED, because the inherited constant has three values, not two.
        //
        // This authenticator resolves the user and stores the identifier type the
        // rest of the flow routes on, so it is the first step of every OTP login:
        // offering DISABLED puts "break login for this realm" one dropdown away
        // in the admin console, with nothing indicating that is what it does.
        assertThat(
                factory.getRequirementChoices(),
                not(hasItemInArray(AuthenticationExecutionModel.Requirement.DISABLED)));
    }

    @Test
    void getRequirementChoices_offersExactlyRequiredAndAlternative() {
        assertThat(
                factory.getRequirementChoices(),
                arrayContainingInAnyOrder(
                        AuthenticationExecutionModel.Requirement.REQUIRED,
                        AuthenticationExecutionModel.Requirement.ALTERNATIVE));
    }

    @Test
    void isConfigurable_isFalse() {
        assertThat(factory.isConfigurable(), org.hamcrest.Matchers.equalTo(false));
    }
}
