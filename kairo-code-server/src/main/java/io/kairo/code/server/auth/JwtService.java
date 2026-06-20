package io.kairo.code.server.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "kairo-code";

    private final byte[] secret;
    private final int expiryHours;

    public JwtService(AuthProperties props) {
        this.expiryHours = props.getTokenExpiryHours();
        this.secret = resolveSecret(props.getJwtSecret());
    }

    public String generateToken(String username) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(username)
                    .issuer(ISSUER)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(expiryHours, ChronoUnit.HOURS)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            JWSSigner signer = new MACSigner(secret);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new RuntimeException("Failed to generate JWT", e);
        }
    }

    public Optional<String> validateToken(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSVerifier verifier = new MACVerifier(secret);
            if (!jwt.verify(verifier)) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(claims.getIssuer())) {
                return Optional.empty();
            }
            if (claims.getExpirationTime() != null
                    && claims.getExpirationTime().before(new Date())) {
                return Optional.empty();
            }
            return Optional.ofNullable(claims.getSubject());
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }

    private static byte[] resolveSecret(String configSecret) {
        if (configSecret != null && !configSecret.isBlank()) {
            return Base64.getDecoder().decode(configSecret);
        }
        Path secretFile = Path.of(System.getProperty("user.home"), ".kairo-code", "jwt-secret.key");
        if (Files.exists(secretFile)) {
            try {
                return Base64.getDecoder().decode(Files.readString(secretFile).trim());
            } catch (IOException e) {
                log.warn("Failed to read jwt-secret.key, generating new: {}", e.getMessage());
            }
        }
        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        try {
            Files.createDirectories(secretFile.getParent());
            Files.writeString(secretFile, Base64.getEncoder().encodeToString(generated));
            try {
                Files.setPosixFilePermissions(secretFile,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
            }
            log.info("Generated and persisted JWT secret to {}", secretFile);
        } catch (IOException e) {
            log.warn("Failed to persist jwt-secret.key: {}", e.getMessage());
        }
        return generated;
    }
}
