# Keycloak Email & SMS OTP Authenticator

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A Keycloak SPI plugin that adds one-time password (OTP) authentication via **email** and **SMS**. Supports browser login flows and custom OAuth2 grant types for API-based authentication.

> [!NOTE]
> This was developed using Claude Code. It is also still work in progress.

## Requirements

- Java 17+
- Keycloak 26.x (built against 26.5.5)
- Maven 3.x

## Project Structure

This is a multi-module Maven project:

| Module | Artifact | Description |
|---|---|---|
| `common/` | `keycloak-otp-common` | Shared constants, SMS SPI interfaces and default log provider |
| `otp-2fa/` | `keycloak-otp-2fa` | Browser flow authenticators for 2FA (email + SMS OTP forms) |
| `otp-login/` | `keycloak-otp-login` | Custom OAuth2 grant types for OTP login via token endpoint |
| `themes/` | `keycloak-otp-themes` | FreeMarker templates, messages, and theme resources |
| `dist/` | `keycloak-otp` | Single deployable JAR (all modules merged) |

## Build

```bash
mvn clean package
```

Produces a single deployable JAR:
- `dist/target/keycloak-otp-1.0.0-SNAPSHOT.jar`

Or download a pre-built JAR from [GitHub Releases](https://github.com/arados/keycloak-otp/releases).

## Installation

Copy the single JAR into Keycloak's `providers/` directory:

```bash
cp dist/target/keycloak-otp-1.0.0-SNAPSHOT.jar /opt/keycloak/providers/
```

Then rebuild Keycloak (required after adding providers):

```bash
/opt/keycloak/bin/kc.sh build
```

Restart Keycloak after the build completes.

## Docker Development

A Docker Compose setup is included for local development:

```bash
docker compose up --build -d    # Build and start
docker compose logs keycloak    # View logs (SMS OTP codes appear here)
docker compose down -v          # Stop and wipe data
```

Services:
- **Keycloak**: http://localhost:8080 — admin console at `/admin/` (admin/admin)
- **Mailpit**: http://localhost:8025 — captures OTP emails

A pre-configured `otp-demo` realm is imported with:
- Browser OTP flows (SMS, email, and channel choice)
- The `otp` login/email theme enabled
- Passwordless SMS OTP browser flow bound as realm default
- Client `otp-demo-client`: password + OTP (MFA)
- Client `passwordless-demo-client`: username + OTP only (no password)
- Test user: `testuser` / `password` (email: testuser@example.com, phone: +1234567890)

## Authenticators Provided

| Provider ID | Display Name | Flow Type | Channel |
|---|---|---|---|
| `email-otp-form` | Email OTP Form | Browser | Email |
| `sms-otp-form` | SMS OTP Form | Browser | SMS |
| `otp-channel-choice-form` | OTP Channel Choice | Browser | Email or SMS |
| `urn:otp:email` | Email OTP Grant | Custom Grant Type | Email |
| `urn:otp:sms` | SMS OTP Grant | Custom Grant Type | SMS |

## Theme

The `themes/` module provides a Keycloak theme named `otp`. It includes:

- **Login type**: OTP input forms (`login-email-otp.ftl`, `login-sms-otp.ftl`, `login-otp-channel-select.ftl`) and i18n messages — extends the `keycloak.v2` theme (PatternFly v5, uses `field.ftl`/`buttons.ftl` macros)
- **Email type**: OTP code email templates (HTML + text) and i18n messages — extends the `base` theme

To use the theme, set `loginTheme` and/or `emailTheme` to `otp` in the realm settings.

## Configuration Options

All authenticators are configurable through the Keycloak admin console under the execution's config:

### Email OTP

| Config Key | Label | Default | Description |
|---|---|---|---|
| `emailOtp.codeLength` | Code Length | `6` | Number of digits in the OTP code |
| `emailOtp.ttl` | Code TTL (seconds) | `300` | Time-to-live for the OTP code |
| `emailOtp.maxRetries` | Max Retries | `3` | Max failed attempts before invalidation |
| `emailOtp.markVerified` | Mark Email Verified | `true` | Set the user's `emailVerified` flag after a successful OTP |

### SMS OTP

| Config Key | Label | Default | Description |
|---|---|---|---|
| `smsOtp.codeLength` | Code Length | `6` | Number of digits in the OTP code |
| `smsOtp.ttl` | Code TTL (seconds) | `300` | Time-to-live for the OTP code |
| `smsOtp.maxRetries` | Max Retries | `3` | Max failed attempts before invalidation |
| `smsOtp.phoneAttribute` | Phone Number Attribute | `phoneNumber` | User attribute storing the phone number |
| `smsOtp.phoneVerifiedAttribute` | Phone Verified Attribute | `phoneNumberVerified` | User attribute set to `"true"` after a successful OTP |
| `smsOtp.markVerified` | Mark Phone Verified | `true` | Whether to set the phone verified attribute after a successful OTP |

## Setup: Browser Flow

1. In the Keycloak admin console, go to **Authentication** > **Flows**.
2. Copy the **Browser** flow (or create a new one).
3. Add an execution and select **Email OTP Form** or **SMS OTP Form**.
4. Set the requirement to **Required** (or **Conditional**).
5. Optionally click the gear icon to configure code length, TTL, and max retries.
6. Bind the flow to the browser flow in **Authentication** > **Bindings**.
7. Set the realm's login theme to `otp` under **Realm Settings** > **Themes**.

After login with username/password, the user will be prompted to enter the OTP code sent to their email or phone.

## Custom Grant Types (Recommended)

The plugin provides custom OAuth2 grant types that replace `grant_type=password` with dedicated, descriptive grant types. The `password` parameter is **optional** — include it for MFA (password + OTP), omit it for passwordless (OTP only).

| Grant Type | Channel | Description |
|---|---|---|
| `urn:otp:email` | Email | Email OTP authentication |
| `urn:otp:sms` | SMS | SMS OTP authentication |

No flow configuration is needed — the grant types are self-contained.

### API Usage

**Phase 1 — Request the OTP:**

```bash
# Passwordless (OTP only)
curl -X POST "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -d "grant_type=urn:otp:sms" \
  -d "client_id=${CLIENT_ID}" \
  -d "username=${USERNAME}"

# MFA (password + OTP) — same grant type, just add password
curl -X POST "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -d "grant_type=urn:otp:email" \
  -d "client_id=${CLIENT_ID}" \
  -d "username=${USERNAME}" \
  -d "password=${PASSWORD}"
```

Response (HTTP 401):

```json
{
  "error": "sms_otp_required",
  "error_description": "An OTP code has been sent to your phone number.",
  "otp_session_id": "a1b2c3d4-..."
}
```

**Phase 2 — Submit the OTP:**

```bash
curl -X POST "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -d "grant_type=urn:otp:sms" \
  -d "client_id=${CLIENT_ID}" \
  -d "username=${USERNAME}" \
  -d "otp=${OTP_CODE}" \
  -d "otp_session_id=${OTP_SESSION_ID}"
```

On success, returns the standard token response with `access_token`, `refresh_token`, etc.

## Passwordless Authentication

The same authenticators support passwordless login — the user provides only a username, then verifies via OTP.

### Passwordless Browser Flow

Uses Keycloak's built-in `auth-username-form` (username only, no password) followed by the OTP step:

```
auth-cookie                    (ALTERNATIVE)
passwordless-forms             (ALTERNATIVE)
  ├── auth-username-form       (REQUIRED)   ← username only
  └── sms-otp-form             (REQUIRED)   ← OTP replaces password
```

To configure manually:
1. Create a new browser flow.
2. Add `auth-cookie` as ALTERNATIVE.
3. Add a sub-flow (ALTERNATIVE) with `Username Form` (REQUIRED) then `SMS OTP Form` or `Email OTP Form` (REQUIRED).
4. Bind the flow to the realm or client.

### Passwordless Direct Grant

With custom grant types, simply omit the `password` parameter — no separate flow needed. See [Custom Grant Types](#custom-grant-types-recommended).

### Pre-configured Flows in Demo Realm

The `otp-demo` realm includes browser flow variants:

| Flow Alias | Type | Mode |
|---|---|---|
| `browser-with-email-otp` | Browser | Password + Email OTP |
| `browser-with-sms-otp` | Browser | Password + SMS OTP |
| `passwordless-browser-email-otp` | Browser | Username + Email OTP |
| `passwordless-browser-sms-otp` | Browser | Username + SMS OTP |
| `browser-otp-choice` | Browser | Password + Email **or** SMS OTP (user chooses) |

For direct grant (API) usage, use the custom grant types `urn:otp:email` and `urn:otp:sms` — no flow configuration needed.

## Setup: OTP Channel Choice Flow

The **OTP Channel Choice** authenticator presents a single screen where the user selects between email and SMS OTP, then enters the code — all within one authenticator execution.

```
auth-cookie                        (ALTERNATIVE)
browser-otp-choice-forms           (ALTERNATIVE)
  ├── auth-username-password-form  (REQUIRED)
  └── otp-channel-choice-form      (REQUIRED)
```

### OTP Channel Choice Configuration

| Config Key | Label | Default | Description |
|---|---|---|---|
| `otpChoice.codeLength` | Code Length | `6` | Number of digits in the OTP code |
| `otpChoice.ttl` | Code TTL (seconds) | `300` | Time-to-live for the OTP code |
| `otpChoice.maxRetries` | Max Retries | `3` | Max failed attempts before invalidation |
| `otpChoice.phoneAttribute` | Phone Number Attribute | `phoneNumber` | User attribute storing the phone number |
| `otpChoice.phoneVerifiedAttribute` | Phone Verified Attribute | `phoneNumberVerified` | User attribute set to `"true"` after a successful SMS OTP |
| `otpChoice.markVerified` | Mark Channel Verified | `true` | Mark the used channel verified after a successful OTP |

## Verification Recording

A completed OTP is proof that the user controls the address or number the code went to, so the
result is written back to the user profile (all four authenticators plus both direct-grant types):

| Channel | What is written | Where it shows up |
|---|---|---|
| Email | Keycloak's built-in `emailVerified` flag | `email_verified` claim in ID / access tokens |
| SMS | User attribute `phoneNumberVerified` = `"true"` | add a **User Attribute** protocol mapper to expose it as `phone_number_verified` |

Details:

- The delivery target is captured when the code is sent (auth note for browser flows, single-use
  object note for direct grants). If the profile's email / phone changed between send and verify,
  the flag is **not** set — the proof no longer applies to what the profile holds.
- Already-verified users are not re-written, so a repeat login is not a DB write.
- Disable per authenticator with `emailOtp.markVerified` / `smsOtp.markVerified` /
  `otpChoice.markVerified`. The direct-grant types (`urn:otp:email`, `urn:otp:sms`) always record.
- The demo realm ships a `phone_number_verified` mapper on both clients as a reference.

## Email Configuration

Email OTP uses Keycloak's built-in email provider. Configure SMTP settings in the admin console under **Realm Settings** > **Email**. No additional configuration is needed for the email channel beyond standard Keycloak SMTP setup.

## SMS Provider SPI

SMS sending is pluggable via a custom SPI. Five providers ship with the plugin out of the box:

| Provider id | Class | Use case |
|---|---|---|
| `log`    | `LogSmsSenderFactory`   | Default. Writes the OTP to Keycloak's stdout. Dev / E2E only. |
| `http`   | `HttpSmsProviderFactory`   | **Preferred for Blue Dots.** Hands the OTP to notification-service, which owns the vendor. |
| `twilio` | `TwilioSmsProviderFactory` | Sends via Twilio Programmable Messaging. |
| `sns`    | `SnsSmsProviderFactory`    | Sends via Amazon SNS Publish (region-scoped). |
| `msg91`  | `Msg91SmsProviderFactory`  | Sends via MSG91 Flow API (DLT-compliant for India). |

Switching providers is a one-env-var change — the active provider is selected via Keycloak's standard SPI configuration mechanism (`KC_SPI_SMS_PROVIDER` env var or `--spi-sms-provider` flag). No code change or rebuild required.

### Using the Log Provider (default)

The log provider is active by default. OTP codes appear in the Keycloak server log:

```
INFO  [hr.delmisoft.keycloak.otp.sms.LogSmsSenderFactory] SMS to +1234567890: Your verification code is: 123456
```

### Using the Twilio Provider

Set the provider id and supply Twilio credentials:

```yaml
# docker-compose.yml
environment:
  KC_SPI_SMS_PROVIDER: twilio
  TWILIO_ACCOUNT_SID:  ${TWILIO_ACCOUNT_SID}
  TWILIO_AUTH_TOKEN:   ${TWILIO_AUTH_TOKEN}
  TWILIO_FROM_NUMBER:  ${TWILIO_FROM_NUMBER}
```

Equivalent CLI flags for non-Compose deployments:

```bash
/opt/keycloak/bin/kc.sh start \
  --spi-sms-provider=twilio \
  --spi-sms-twilio-account-sid="$TWILIO_ACCOUNT_SID" \
  --spi-sms-twilio-auth-token="$TWILIO_AUTH_TOKEN" \
  --spi-sms-twilio-from-number="$TWILIO_FROM_NUMBER"
```

The Twilio provider has zero extra runtime dependencies — it uses JDK 17's `java.net.http.HttpClient` to call the Twilio REST API directly.

### Using the Amazon SNS Provider

Set the provider id and supply AWS credentials (or rely on the default credential chain):

```yaml
# docker-compose.yml
environment:
  KC_SPI_SMS_PROVIDER: sns
  AWS_ACCESS_KEY_ID: ${AWS_ACCESS_KEY_ID}
  AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY}
  AWS_REGION: ap-south-1
  AWS_SNS_SENDER_ID: BLUEDOTS         # optional, alphanumeric, country-dependent
  AWS_SNS_SMS_TYPE:  Transactional    # default; "Promotional" also valid
```

Equivalent CLI flags:

```bash
/opt/keycloak/bin/kc.sh start \
  --spi-sms-provider=sns \
  --spi-sms-sns-access-key-id="$AWS_ACCESS_KEY_ID" \
  --spi-sms-sns-secret-access-key="$AWS_SECRET_ACCESS_KEY" \
  --spi-sms-sns-region=ap-south-1 \
  --spi-sms-sns-sender-id=BLUEDOTS \
  --spi-sms-sns-sms-type=Transactional
```

If `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` are not set, the AWS SDK falls back to its **default credential chain** — env, ECS task role, EC2 instance profile, then `~/.aws/credentials`. This is the preferred path for production on EKS with IRSA (IAM Roles for Service Accounts) so static keys never touch the runtime.

The SNS provider depends on `software.amazon.awssdk:sns` (~5 MB shaded into the fat JAR). If you don't intend to use SNS, leave `KC_SPI_SMS_PROVIDER` set to `log` or `twilio` — the SDK code is on classpath but never loaded.

### Using the MSG91 Provider

MSG91's **Flow API** is template-based and DLT-compliant — the preferred path for Indian carriers. Pre-register an OTP template in the MSG91 dashboard with an OTP variable placeholder (default name `var`), then wire the credentials:

```yaml
# docker-compose.yml
environment:
  KC_SPI_SMS_PROVIDER:  msg91
  MSG91_AUTH_KEY:       ${MSG91_AUTH_KEY}        # required
  MSG91_TEMPLATE_ID:    ${MSG91_TEMPLATE_ID}     # required — DLT-approved Flow template id
  MSG91_SENDER_ID:      ${MSG91_SENDER_ID}       # optional; falls back to sender id set on the template
  MSG91_OTP_VAR_NAME:   var                      # template variable receiving the code (default: var)
```

Equivalent CLI flags:

```bash
/opt/keycloak/bin/kc.sh start \
  --spi-sms-provider=msg91 \
  --spi-sms-msg91-auth-key="$MSG91_AUTH_KEY" \
  --spi-sms-msg91-template-id="$MSG91_TEMPLATE_ID" \
  --spi-sms-msg91-sender-id="$MSG91_SENDER_ID" \
  --spi-sms-msg91-otp-var-name=var
```

The provider extracts the numeric OTP from the outbound SMS body (`"Your verification code is: 123456"`) and posts it under `MSG91_OTP_VAR_NAME` to the configured Flow template — keep the template body using the same variable name. Like Twilio, MSG91 uses JDK 17's `HttpClient` directly with zero extra runtime dependencies.

### Using the HTTP Provider (notification-service)

Every other provider here teaches Keycloak the name of one vendor. Adding the next one
then costs a Java change, a jar rebuild, a Keycloak image build, an image tag pin and a
chart change — repeated per vendor, forever.

`http` pays that once. It posts the OTP to the Blue Dots **notification-service**, which
owns the vendor selection, so a new SMS vendor becomes a change in one service and nothing
here moves. It also removes a duplicated setting: with `msg91` the login-OTP template id is
configured both here and in notification-service, whereas here the template is only *named*
and the notification service owns its vendor-side id and its text.

```yaml
# docker-compose.yml
environment:
  KC_SPI_SMS_PROVIDER:     http
  SMS_HTTP_URL:            http://notification-service:3000/notify   # required
  SMS_HTTP_SECRET:         ${SMS_HTTP_SECRET}                        # required, HMAC shared secret
  SMS_HTTP_KEY_ID:         keycloak                                  # default: keycloak
  SMS_HTTP_TEMPLATE_ID:    login_otp                                 # default: login_otp
  SMS_HTTP_OTP_VAR_NAME:   message                                   # default: message
  SMS_HTTP_TIMEOUT_MS:     5000                                      # default: 5000
```

Equivalent CLI flags:

```bash
/opt/keycloak/bin/kc.sh start \
  --spi-sms-provider=http \
  --spi-sms-http-url="$SMS_HTTP_URL" \
  --spi-sms-http-secret="$SMS_HTTP_SECRET" \
  --spi-sms-http-key-id=keycloak \
  --spi-sms-http-template-id=login_otp \
  --spi-sms-http-otp-var-name=message
```

`SMS_HTTP_KEY_ID` must name an entry in notification-service's `internal-secrets.json`, and
`SMS_HTTP_SECRET` must be that entry's secret. Requests carry notification-service's HMAC
envelope — `X-NS-Key`, `X-NS-Timestamp`, `X-NS-Nonce` and
`X-NS-Signature: v1=<hmac_sha256>` over `METHOD\nPATH\nTIMESTAMP\nNONCE`. The timestamp is
checked against a ±30s window and the nonce is single-use for 60s, so server clock skew
shows up as `401 Request expired`.

**Only the code is sent, not the rendered text:**

```json
{"channel":"sms","to":"+919999999999","template_id":"login_otp",
 "priority":"realtime","variables":{"message":"123456"}}
```

That is deliberate. Under Indian DLT rules the delivered text must match the template
registered with the operator, so the authoritative copy is the one notification-service
holds — not the string rendered from this plugin's theme messages. The provider extracts the
numeric code from the outbound body the same way the MSG91 provider does.

Two behaviours worth knowing:

- **Login gains a hard dependency on notification-service** being reachable. The timeout
  defaults to 5s and a non-positive value is rejected, because this call sits inside an
  interactive login and "wait forever" is worse than any misconfiguration it could mask.
- **A `409` is treated as sent.** notification-service suppresses an identical payload inside
  its dedupe window. Every login mints a fresh code, so an identical payload means this exact
  OTP is already on its way; failing here would show the user an error for a code they are
  about to receive.

### Implementing a Custom SMS Provider

To integrate with another SMS gateway (Vonage, Plivo, Karix, etc.), implement two interfaces:

1. **`SmsProvider`** — the send logic:

```java
public class TwilioSmsProvider implements SmsProvider {
    @Override
    public void send(String phoneNumber, String message) throws SmsException {
        // Call Twilio API
    }

    @Override
    public void close() {}
}
```

2. **`SmsProviderFactory`** — the factory:

```java
public class TwilioSmsProviderFactory implements SmsProviderFactory {
    @Override
    public SmsProvider create(KeycloakSession session) {
        return new TwilioSmsProvider();
    }

    @Override
    public String getId() {
        return "twilio";
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}
}
```

3. Register the factory in `META-INF/services/hr.delmisoft.keycloak.otp.sms.SmsProviderFactory`:

```
hr.delmisoft.TwilioSmsProviderFactory
```

4. Select the provider in Keycloak's configuration:

```bash
/opt/keycloak/bin/kc.sh start --spi-sms-provider=twilio
```

### SMS User Setup

Users must have a phone number stored in the user attribute specified by the `smsOtp.phoneAttribute` config (default: `phoneNumber`). Set this attribute in the admin console under **Users** > select user > **Attributes**, or via the Keycloak Admin REST API.

## Running Tests

### Unit Tests

```bash
mvn test                    # Run all tests
mvn test -pl common         # Run only common tests
mvn test -pl otp-2fa        # Run only 2FA tests
mvn test -pl otp-login      # Run only login tests
```

Unit tests use JUnit 5 and Mockito to test authenticator logic against mocked Keycloak interfaces.

### E2E Tests (Playwright)

Browser-based integration tests that verify the full OTP login flow against a running Keycloak instance.

```bash
cd e2e
npm install
npx playwright install chromium
npm test                    # Run all E2E tests
npm run test:sms            # Run SMS OTP tests only
npm run test:headed         # Run with visible browser
```

Requires Docker services to be running (`docker compose up --build -d`). Tests cover:
- SMS OTP login flow (form rendering, valid/invalid codes, post-login account access)
- OTP login via custom client (`otp-demo-client`)
- OTP channel choice flow (email or SMS selection)

## Security

> **Warning**: The Docker Compose setup and `otp-demo` realm are for **development and testing only**. They use hardcoded credentials (`admin/admin`, `testuser/password`) and the log-based SMS provider. Do not use these in production.

- OTP codes are compared using constant-time comparison (`MessageDigest.isEqual`) to prevent timing attacks
- Codes are generated with `SecureRandom`
- OTP sessions are stored in Keycloak's `SingleUseObjectProvider` (custom grant types) or `AuthenticationSessionModel` auth notes (browser flow)
- Brute-force protection is built in via configurable max retries

## License

This project is licensed under the [Apache License 2.0](LICENSE).
