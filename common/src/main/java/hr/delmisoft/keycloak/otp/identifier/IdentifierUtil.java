package hr.delmisoft.keycloak.otp.identifier;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import hr.delmisoft.keycloak.otp.sms.SmsOtpConst;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves a free-form login identifier (email, phone, or username) to a {@link UserModel}.
 *
 * <p>Detection rules:
 * <ul>
 *   <li>Contains "@" → email (lookup via realm email index)</li>
 *   <li>Optional "+" then 7-15 digits (after stripping spaces / dashes / parens) → phone
 *       (normalised to E.164 with libphonenumber, then attribute lookup)</li>
 *   <li>Otherwise → username (lookup via username index)</li>
 * </ul>
 *
 * <p>Adapted from cooperlyt/keycloak-phone-provider's Utils.java (Apache 2.0). Slimmed:
 * removed dependency on the cooperlyt PhoneProvider SPI, fixed default region via
 * config / realm locale, no PhoneOtpCredentialModel storage.
 */
public final class IdentifierUtil {

    private static final Logger LOG = Logger.getLogger(IdentifierUtil.class);

    private static final Pattern EMAIL_HINT = Pattern.compile(".+@.+");
    private static final Pattern PHONE_CANDIDATE = Pattern.compile("\\+?[0-9 .()\\-]{7,20}");

    private IdentifierUtil() {}

    public enum IdentifierType {
        EMAIL,
        PHONE,
        USERNAME
    }

    /**
     * Detects the identifier type using cheap regex heuristics. Does not perform a DB lookup.
     *
     * @param raw user-supplied identifier; may be null or blank.
     * @return detected type. Blank input → USERNAME.
     */
    public static IdentifierType detect(String raw) {
        if (raw == null) return IdentifierType.USERNAME;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return IdentifierType.USERNAME;
        if (EMAIL_HINT.matcher(trimmed).matches()) return IdentifierType.EMAIL;
        if (PHONE_CANDIDATE.matcher(trimmed).matches() && containsDigit(trimmed)) {
            return IdentifierType.PHONE;
        }
        return IdentifierType.USERNAME;
    }

    /**
     * Resolves the identifier to a {@link UserModel}, trying each candidate format in turn.
     *
     * @param session active Keycloak session.
     * @param realm   realm in which to search.
     * @param raw     user-supplied identifier.
     * @return matching user, or empty if none found.
     */
    public static Optional<UserModel> findUser(KeycloakSession session, RealmModel realm, String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String value = raw.trim();
        IdentifierType type = detect(value);
        switch (type) {
            case EMAIL:
                return Optional.ofNullable(session.users().getUserByEmail(realm, value));
            case PHONE:
                return findUserByPhone(session, realm, value);
            case USERNAME:
            default:
                return Optional.ofNullable(session.users().getUserByUsername(realm, value));
        }
    }

    /**
     * Looks up a user by phone attribute, trying every libphonenumber format
     * (E.164, INTERNATIONAL, NATIONAL, RFC3966) plus the raw national number.
     * Falls back to literal match if libphonenumber cannot parse.
     */
    public static Optional<UserModel> findUserByPhone(KeycloakSession session, RealmModel realm, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) return Optional.empty();

        Set<String> candidates = new HashSet<>();
        candidates.add(phoneNumber);

        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        String region = defaultRegion(session);
        try {
            PhoneNumber parsed = util.parse(phoneNumber, region);
            if (parsed.hasNationalNumber()) {
                candidates.add(String.valueOf(parsed.getNationalNumber()));
            }
            for (PhoneNumberFormat format : PhoneNumberFormat.values()) {
                candidates.add(util.format(parsed, format));
            }
        } catch (NumberParseException e) {
            LOG.warnf(e, "Cannot parse phone '%s' (region=%s) — falling back to literal match",
                    phoneNumber, region);
        }

        String attr = SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE;
        return candidates.stream()
                .flatMap(candidate -> session.users().searchForUserByUserAttributeStream(realm, attr, candidate))
                .findFirst();
    }

    /**
     * Canonicalises a phone number to E.164 (e.g. "+919876543210"). Validates with libphonenumber
     * if valid-format checking is enabled.
     *
     * @throws PhoneNumberInvalidException if the number cannot be parsed or is invalid.
     */
    public static String canonicalize(KeycloakSession session, String phoneNumber)
            throws PhoneNumberInvalidException {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new PhoneNumberInvalidException(
                    PhoneNumberInvalidException.ErrorType.VALID_FAIL,
                    "Phone number is blank");
        }
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        String region = defaultRegion(session);
        String trimmed = phoneNumber.trim();
        try {
            PhoneNumber parsed = util.parse(trimmed, region);
            if (!util.isValidNumber(parsed)) {
                throw new PhoneNumberInvalidException(
                        PhoneNumberInvalidException.ErrorType.VALID_FAIL,
                        String.format("Phone '%s' is not a valid number", trimmed));
            }
            return util.format(parsed, PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            throw new PhoneNumberInvalidException(e);
        }
    }

    /**
     * Default region for libphonenumber parsing. Resolved in priority order:
     * <ol>
     *   <li>Realm-level attribute "phoneDefaultRegion" if set</li>
     *   <li>Realm default locale country part (e.g. "en-IN" → "IN")</li>
     *   <li>Constant fallback (currently "IN")</li>
     * </ol>
     */
    static String defaultRegion(KeycloakSession session) {
        if (session == null || session.getContext() == null) return DEFAULT_REGION_FALLBACK;
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) return DEFAULT_REGION_FALLBACK;

        String attr = realm.getAttribute(REALM_ATTR_DEFAULT_REGION);
        if (attr != null && !attr.isBlank()) return attr.trim().toUpperCase();

        String locale = realm.getDefaultLocale();
        if (locale != null) {
            int sep = Math.max(locale.indexOf('-'), locale.indexOf('_'));
            if (sep >= 0 && sep + 1 < locale.length()) {
                String country = locale.substring(sep + 1).trim().toUpperCase();
                if (!country.isEmpty()) return country;
            }
        }
        return DEFAULT_REGION_FALLBACK;
    }

    private static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    public static final String REALM_ATTR_DEFAULT_REGION = "phoneDefaultRegion";
    public static final String DEFAULT_REGION_FALLBACK = "IN";
}
