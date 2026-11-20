package edu.course.rehabnext;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
class WorkspaceAuth {
    static final String ADMIN = "ADMIN";
    static final String GUIDE = "GUIDE";
    static final String TRAINEE = "TRAINEE";
    static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{1,64}");

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final String BEARER_PREFIX = "Bearer ";
    private static final PasswordHash UNKNOWN_ACCOUNT = new PasswordHash("7238012d0775ad3037744def56f111d9",
            "2176c0fc5bef9e67df06c51996296b5c8f66a760ce5f56ea42b024a9870a46f5");
    private final JdbcClient database;

    WorkspaceAuth(JdbcClient database) {
        this.database = database;
    }

    record Actor(long id, String username, String displayName, String role) {}

    record Login(String token, Actor actor) {}

    record PasswordHash(String salt, String hash) {
        boolean matches(String password) {
            return MessageDigest.isEqual(derive(password, HEX.parseHex(salt)), HEX.parseHex(hash));
        }
    }

    private record Credentials(Actor actor, PasswordHash password) {}

    @Transactional
    Login login(String submittedUsername, String password) {
        String username = normalizeUsername(submittedUsername);
        if (!USERNAME.matcher(username).matches() || password == null || password.isEmpty() || password.length() > 128) {
            throw badCredentials();
        }
        var credentials = database.sql("""
                SELECT id, username, display_name, account_role, password_salt, password_hash
                FROM workspace_account WHERE username = ?
                """).param(username).query((row, index) -> new Credentials(readActor(row),
                new PasswordHash(row.getString("password_salt"), row.getString("password_hash")))).optional();
        if (!credentials.map(Credentials::password).orElse(UNKNOWN_ACCOUNT).matches(password)
                || credentials.isEmpty()) {
            throw badCredentials();
        }

        Actor actor = credentials.get().actor();
        // Account status changes take the same row lock before revoking sessions.
        boolean enabled = database.sql("SELECT enabled FROM workspace_account WHERE id = ? FOR UPDATE")
                .param(actor.id()).query(Boolean.class).single();
        if (!enabled) throw badCredentials();

        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        database.sql("DELETE FROM workspace_login_session WHERE account_id = ? AND expires_at <= CURRENT_TIMESTAMP")
                .param(actor.id()).update();
        database.sql("INSERT INTO workspace_login_session (token_digest, account_id, expires_at) VALUES (?, ?, ?)")
                .params(tokenDigest(token), actor.id(), OffsetDateTime.now(ZoneOffset.UTC).plusHours(8)).update();
        return new Login(token, actor);
    }

    Actor require(String authorization) {
        return database.sql("""
                SELECT account.id, account.username, account.display_name, account.account_role
                FROM workspace_login_session session JOIN workspace_account account ON account.id = session.account_id
                WHERE session.token_digest = ? AND session.expires_at > CURRENT_TIMESTAMP AND account.enabled
                """).param(bearerDigest(authorization)).query((row, index) -> readActor(row)).optional()
                .orElseThrow(WorkspaceAuth::expiredSession);
    }

    void logout(String authorization) {
        String digest = bearerDigest(authorization);
        int removed = database.sql("""
                DELETE FROM workspace_login_session WHERE token_digest = ? AND expires_at > CURRENT_TIMESTAMP
                """).param(digest).update();
        if (removed == 0) throw expiredSession();
    }

    boolean canAccessTrainee(Actor actor, long traineeId) {
        return database.sql("""
                SELECT guide_id FROM workspace_trainee WHERE account_id = ?
                """).param(traineeId)
                .query((row, index) -> canAccessTrainee(actor, traineeId, row.getObject("guide_id", Long.class)))
                .optional().orElse(false);
    }

    boolean canAccessTrainee(Actor actor, long traineeId, Long guideId) {
        return switch (actor.role()) {
            case ADMIN -> true;
            case GUIDE -> guideId != null && actor.id() == guideId;
            case TRAINEE -> actor.id() == traineeId;
            default -> false;
        };
    }

    static void requireRole(Actor actor, String role) {
        if (!role.equals(actor.role())) throw new ResponseStatusException(FORBIDDEN, "当前账号无权执行此操作。");
    }

    static String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    static PasswordHash newPassword(String password) {
        byte[] salt = randomBytes(16);
        return new PasswordHash(HEX.formatHex(salt), HEX.formatHex(derive(password, salt)));
    }

    static Actor readActor(ResultSet row) throws SQLException {
        return new Actor(row.getLong("id"), row.getString("username"), row.getString("display_name"),
                row.getString("account_role"));
    }

    private static byte[] derive(String password, byte[] salt) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, 600_000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("无法计算密码摘要。", error);
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String bearerDigest(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw expiredSession();
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        if (!TOKEN.matcher(token).matches()) throw expiredSession();
        return tokenDigest(token);
    }

    private static String tokenDigest(String token) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("无法计算会话摘要。", error);
        }
    }

    private static ResponseStatusException badCredentials() {
        return new ResponseStatusException(UNAUTHORIZED, "用户名或密码无效，或账号已停用。");
    }

    private static ResponseStatusException expiredSession() {
        return new ResponseStatusException(UNAUTHORIZED, "登录已过期，请重新登录。");
    }
}
