/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a previously written session checkpoint from
 * {@code {workingDir}/.kairo-session/checkpoint.json}.
 *
 * <p>Returns {@code Optional.empty()} if the file doesn't exist or can't be parsed.
 */
public final class CheckpointLoader {

    private static final Logger log = LoggerFactory.getLogger(CheckpointLoader.class);

    private static final String CHECKPOINT_DIR = ".kairo-session";
    private static final String CHECKPOINT_FILE = "checkpoint.json";

    private CheckpointLoader() {}

    /**
     * Load the checkpoint from the given working directory.
     *
     * @param workingDir the project working directory
     * @param mapper Jackson ObjectMapper for deserialization
     * @return Optional containing the checkpoint data, or empty if not found/unparseable
     */
    public static Optional<CheckpointData> load(Path workingDir, ObjectMapper mapper) {
        if (workingDir == null) {
            return Optional.empty();
        }

        Path checkpointPath = workingDir.resolve(CHECKPOINT_DIR).resolve(CHECKPOINT_FILE);
        if (!Files.exists(checkpointPath)) {
            return Optional.empty();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = mapper.readValue(checkpointPath.toFile(), Map.class);

            String sessionId = (String) root.get("sessionId");
            Number iterationNum = (Number) root.get("iteration");
            int iteration = iterationNum != null ? iterationNum.intValue() : 0;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> msgList = (List<Map<String, Object>>) root.get("messages");
            if (msgList == null) {
                return Optional.empty();
            }

            List<Msg> messages = parseMessages(msgList);
            return Optional.of(new CheckpointData(sessionId, iteration, messages));
        } catch (IOException e) {
            log.debug("Failed to load checkpoint from {}: {}", checkpointPath, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Failed to parse checkpoint from {}: {}", checkpointPath, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Msg> parseMessages(List<Map<String, Object>> msgList) {
        List<Msg> result = new ArrayList<>();
        for (Map<String, Object> entry : msgList) {
            try {
                String roleStr = (String) entry.get("role");
                if (roleStr == null) continue;

                MsgRole role = MsgRole.valueOf(roleStr.toUpperCase());
                String content = (String) entry.get("content");
                if (content == null) content = "";

                Msg msg = Msg.of(role, content);
                result.add(msg);
            } catch (Exception e) {
                log.debug("Skipping malformed message entry: {}", e.getMessage());
            }
        }
        return result;
    }
}
