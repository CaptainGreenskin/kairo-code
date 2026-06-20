package io.kairo.code.server.auth;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,32}$");
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final UserStore userStore;
    private final JwtService jwtService;
    private final AuthProperties authProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(UserStore userStore, JwtService jwtService, AuthProperties authProperties) {
        this.userStore = userStore;
        this.jwtService = jwtService;
        this.authProperties = authProperties;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        String inviteCode = body.getOrDefault("inviteCode", "").trim();

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request",
                    "Username must be 3-32 characters, alphanumeric or underscore");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request",
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (authProperties.requiresInviteCode()) {
            if (inviteCode.isBlank() || !constantTimeEquals(authProperties.getInviteCode(), inviteCode)) {
                return error(HttpStatus.FORBIDDEN, "invalid_invite_code",
                        "Invite code is required or incorrect");
            }
        }
        if (userStore.findByUsername(username).isPresent()) {
            return error(HttpStatus.CONFLICT, "username_taken", "Username already exists");
        }

        String hash = passwordEncoder.encode(password);
        UserStore.UserRecord user = userStore.createUser(username, hash);
        String token = jwtService.generateToken(username);

        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", Map.of("username", user.username(), "createdAt", user.createdAt())));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");

        var userOpt = userStore.findByUsername(username);
        if (userOpt.isEmpty() || !passwordEncoder.matches(password, userOpt.get().passwordHash())) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_credentials",
                    "Username or password is incorrect");
        }

        UserStore.UserRecord user = userOpt.get();
        String token = jwtService.generateToken(user.username());

        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", Map.of("username", user.username(), "createdAt", user.createdAt())));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = extractBearer(authHeader);
        if (token == null) {
            return error(HttpStatus.UNAUTHORIZED, "unauthorized", "Missing token");
        }
        var usernameOpt = jwtService.validateToken(token);
        if (usernameOpt.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid or expired token");
        }
        var userOpt = userStore.findByUsername(usernameOpt.get());
        if (userOpt.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "unauthorized", "User not found");
        }
        UserStore.UserRecord user = userOpt.get();
        return ResponseEntity.ok(Map.of(
                "username", user.username(),
                "createdAt", user.createdAt()));
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "requiresInviteCode", authProperties.requiresInviteCode(),
                "hasUsers", userStore.hasUsers()));
    }

    private static String extractBearer(String header) {
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message));
    }
}
