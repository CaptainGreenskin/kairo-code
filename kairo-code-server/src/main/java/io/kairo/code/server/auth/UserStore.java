package io.kairo.code.server.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final TypeReference<List<UserRecord>> LIST_TYPE = new TypeReference<>() {};

    private final Path usersFile;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private List<UserRecord> users;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserRecord(String username, String passwordHash, long createdAt) {}

    public UserStore() {
        this(Path.of(System.getProperty("user.home"), ".kairo-code", "users.json"));
    }

    UserStore(Path usersFile) {
        this.usersFile = usersFile;
        this.users = loadFromDisk();
    }

    public Optional<UserRecord> findByUsername(String username) {
        lock.readLock().lock();
        try {
            return users.stream()
                    .filter(u -> u.username().equalsIgnoreCase(username))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public UserRecord createUser(String username, String passwordHash) {
        lock.writeLock().lock();
        try {
            if (users.stream().anyMatch(u -> u.username().equalsIgnoreCase(username))) {
                throw new IllegalArgumentException("Username already exists: " + username);
            }
            UserRecord user = new UserRecord(username, passwordHash, System.currentTimeMillis());
            users.add(user);
            flush();
            return user;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean hasUsers() {
        lock.readLock().lock();
        try {
            return !users.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<UserRecord> loadFromDisk() {
        if (!Files.exists(usersFile)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(MAPPER.readValue(Files.readString(usersFile), LIST_TYPE));
        } catch (IOException e) {
            log.warn("Failed to read users.json, starting empty: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void flush() {
        try {
            Files.createDirectories(usersFile.getParent());
            Path tmp = usersFile.resolveSibling("users.json.tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(users));
            try {
                Files.setPosixFilePermissions(tmp,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
            }
            Files.move(tmp, usersFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to write users.json: {}", e.getMessage());
        }
    }
}
