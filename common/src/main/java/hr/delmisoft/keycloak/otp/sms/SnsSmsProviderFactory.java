package hr.delmisoft.keycloak.otp.sms;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;

import java.util.HashMap;
import java.util.Map;

/**
 * Amazon SNS-backed {@link SmsProvider}. Activated by setting the runtime SPI
 * configuration to this factory's id ({@code "sns"}), e.g. via the Keycloak
 * environment variable {@code KC_SPI_SMS_PROVIDER=sns}.
 *
 * <p>Configuration is read from the Keycloak SPI config (preferred) and falls
 * back to standard AWS environment variables / instance metadata so the same
 * JAR works in three deployment modes:
 *
 * <ul>
 *   <li><b>Static creds via env</b> — {@code AWS_ACCESS_KEY_ID},
 *       {@code AWS_SECRET_ACCESS_KEY}, {@code AWS_REGION}. Useful for local
 *       and Docker Compose dev.</li>
 *   <li><b>SPI config flags</b> —
 *       {@code --spi-sms-sns-access-key-id=...},
 *       {@code --spi-sms-sns-secret-access-key=...},
 *       {@code --spi-sms-sns-region=...}. Useful for production env-var
 *       hygiene.</li>
 *   <li><b>Default credential chain</b> — if no static creds are supplied,
 *       falls through to {@link DefaultCredentialsProvider}, which picks up
 *       env, ECS task role, EC2 instance profile, or {@code ~/.aws/credentials}
 *       in that order. Recommended for production on EKS with IRSA.</li>
 * </ul>
 *
 * <p>Optional config:
 * <ul>
 *   <li>{@code KC_SPI_SMS_SNS_SENDER_ID} / {@code AWS_SNS_SENDER_ID} —
 *       alphanumeric sender id surfaced on the recipient's device (where
 *       supported by the destination country).</li>
 *   <li>{@code KC_SPI_SMS_SNS_SMS_TYPE} / {@code AWS_SNS_SMS_TYPE} —
 *       {@code Transactional} (default — higher delivery priority,
 *       appropriate for OTPs) or {@code Promotional}.</li>
 * </ul>
 *
 * <p>Like {@link TwilioSmsProviderFactory} this provider is swappable. Set
 * {@code KC_SPI_SMS_PROVIDER} to a different id (e.g. {@code log},
 * {@code twilio}) and restart Keycloak — no code change required.
 */
public class SnsSmsProviderFactory implements SmsProviderFactory {

    public static final String PROVIDER_ID = "sns";

    private static final Logger LOG = Logger.getLogger(SnsSmsProviderFactory.class);

    private SnsClient client;
    private String senderId;
    private String smsType;

    @Override
    public void init(Config.Scope config) {
        String accessKeyId     = readConfig(config, "access-key-id",     "AWS_ACCESS_KEY_ID");
        String secretAccessKey = readConfig(config, "secret-access-key", "AWS_SECRET_ACCESS_KEY");
        String regionName      = readConfig(config, "region",            "AWS_REGION");

        this.senderId = readConfig(config, "sender-id", "AWS_SNS_SENDER_ID");
        this.smsType  = readConfigOrDefault(config, "sms-type", "AWS_SNS_SMS_TYPE", "Transactional");

        Region region = (regionName != null && !regionName.isBlank())
                ? Region.of(regionName)
                : Region.AP_SOUTH_1; // safe Indian default; override via config

        AwsCredentialsProvider creds;
        if (accessKeyId != null && !accessKeyId.isBlank()
                && secretAccessKey != null && !secretAccessKey.isBlank()) {
            creds = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        } else {
            LOG.debug("SnsSmsProvider — no static credentials supplied; "
                    + "falling back to DefaultCredentialsProvider chain "
                    + "(env / ECS / EC2 / profile).");
            creds = DefaultCredentialsProvider.create();
        }

        try {
            this.client = SnsClient.builder()
                    .region(region)
                    .credentialsProvider(creds)
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "SnsSmsProvider failed to initialise SNS client for region %s. "
                    + "Verify AWS_REGION + credentials before activating provider 'sns'.",
                    region);
        }
    }

    @Override
    public SmsProvider create(KeycloakSession session) {
        return new SnsSmsProvider(client, senderId, smsType);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOG.debugf(e, "Error closing SNS client (ignored).");
            }
        }
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    private static String readConfig(Config.Scope config, String key, String envName) {
        if (config != null) {
            String fromConfig = config.get(key);
            if (fromConfig != null && !fromConfig.isBlank()) {
                return fromConfig;
            }
        }
        return System.getenv(envName);
    }

    private static String readConfigOrDefault(Config.Scope config, String key,
                                              String envName, String defaultValue) {
        String value = readConfig(config, key, envName);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /** Stateless wrapper around the SDK's {@link SnsClient#publish}. */
    static final class SnsSmsProvider implements SmsProvider {

        private final SnsClient client;
        private final String senderId;
        private final String smsType;

        SnsSmsProvider(SnsClient client, String senderId, String smsType) {
            this.client = client;
            this.senderId = senderId;
            this.smsType = smsType;
        }

        @Override
        public void send(String phoneNumber, String message)
                throws hr.delmisoft.keycloak.otp.sms.SmsException {
            if (client == null) {
                throw new hr.delmisoft.keycloak.otp.sms.SmsException("SNS provider not configured");
            }
            if (phoneNumber == null || phoneNumber.isBlank()) {
                throw new hr.delmisoft.keycloak.otp.sms.SmsException("Phone number is empty");
            }
            if (message == null || message.isBlank()) {
                throw new hr.delmisoft.keycloak.otp.sms.SmsException("Message is empty");
            }

            Map<String, MessageAttributeValue> attrs = new HashMap<>();
            attrs.put("AWS.SNS.SMS.SMSType", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(smsType)
                    .build());
            if (senderId != null && !senderId.isBlank()) {
                attrs.put("AWS.SNS.SMS.SenderID", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(senderId)
                        .build());
            }

            PublishRequest req = PublishRequest.builder()
                    .phoneNumber(phoneNumber)
                    .message(message)
                    .messageAttributes(attrs)
                    .build();

            try {
                PublishResponse resp = client.publish(req);
                LOG.debugf("SNS SMS dispatched to %s (messageId=%s)",
                        SmsLogSafe.maskPhone(phoneNumber), resp.messageId());
            } catch (SnsException e) {
                throw new hr.delmisoft.keycloak.otp.sms.SmsException(
                        "SNS publish failed: " + e.awsErrorDetails().errorCode()
                                + " " + e.awsErrorDetails().errorMessage(), e);
            } catch (RuntimeException e) {
                throw new hr.delmisoft.keycloak.otp.sms.SmsException("SNS publish error", e);
            }
        }

        @Override
        public void close() {
            // owner (factory) closes the SnsClient
        }
    }
}
