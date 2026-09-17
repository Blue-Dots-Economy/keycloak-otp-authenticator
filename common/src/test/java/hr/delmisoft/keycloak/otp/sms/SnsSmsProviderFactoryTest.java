package hr.delmisoft.keycloak.otp.sms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class SnsSmsProviderFactoryTest {

    private SnsSmsProviderFactory factory;

    @BeforeEach
    void setUp() {
        factory = new SnsSmsProviderFactory();
    }

    @Test
    void getId_returnsSns() {
        assertThat(factory.getId(), equalTo(SnsSmsProviderFactory.PROVIDER_ID));
    }

    @Test
    void create_returnsNonNull_evenWithoutInit() {
        // create() should never NPE — provider degrades gracefully when SNS client is null
        SmsProvider provider = factory.create(null);
        assertThat(provider, notNullValue());
    }

    @Test
    void send_throws_whenSnsClientIsNull() {
        SmsProvider provider = factory.create(null);
        SmsException ex = assertThrows(
                SmsException.class,
                () -> provider.send("+15555550100", "code 123456")
        );
        assertThat(ex.getMessage(), containsString("not configured"));
    }

    @Test
    void send_throws_onEmptyPhoneNumber() {
        SnsClient client = mock(SnsClient.class);
        SnsSmsProviderFactory.SnsSmsProvider provider =
                new SnsSmsProviderFactory.SnsSmsProvider(client, null, "Transactional");

        assertThrows(SmsException.class, () -> provider.send("", "msg"));
        assertThrows(SmsException.class, () -> provider.send(null, "msg"));
    }

    @Test
    void send_throws_onEmptyMessage() {
        SnsClient client = mock(SnsClient.class);
        SnsSmsProviderFactory.SnsSmsProvider provider =
                new SnsSmsProviderFactory.SnsSmsProvider(client, null, "Transactional");

        assertThrows(SmsException.class, () -> provider.send("+15555550100", ""));
        assertThrows(SmsException.class, () -> provider.send("+15555550100", null));
    }

    @Test
    void send_succeeds_onPublishResponse() throws Exception {
        SnsClient client = mock(SnsClient.class);
        PublishResponse response = mock(PublishResponse.class);
        doReturn("msg-id-123").when(response).messageId();
        doReturn(response).when(client).publish(any(PublishRequest.class));

        SnsSmsProviderFactory.SnsSmsProvider provider =
                new SnsSmsProviderFactory.SnsSmsProvider(client, "BLUEDOTS", "Transactional");

        provider.send("+15555550100", "your code is 123456");
    }

    @Test
    void send_throwsSmsException_onSnsException() {
        SnsClient client = mock(SnsClient.class);
        AwsErrorDetails details = AwsErrorDetails.builder()
                .errorCode("AuthorizationError")
                .errorMessage("User is not authorized to perform sns:Publish")
                .build();
        SnsException awsEx = (SnsException) SnsException.builder()
                .awsErrorDetails(details)
                .message("Authorization failed")
                .build();
        doThrow(awsEx).when(client).publish(any(PublishRequest.class));

        SnsSmsProviderFactory.SnsSmsProvider provider =
                new SnsSmsProviderFactory.SnsSmsProvider(client, null, "Transactional");

        SmsException ex = assertThrows(
                SmsException.class,
                () -> provider.send("+15555550100", "code 123456")
        );
        assertThat(ex.getMessage(), containsString("AuthorizationError"));
    }
}
