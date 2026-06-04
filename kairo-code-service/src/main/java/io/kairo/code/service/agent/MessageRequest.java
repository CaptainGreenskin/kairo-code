package io.kairo.code.service.agent;

import io.kairo.api.message.Msg;
import javax.annotation.Nullable;

/**
 * Transport-agnostic representation of an inbound user message.
 *
 * <p>{@code imageData} is a base64-encoded string (not {@code byte[]}) to match
 * the WebSocket handler contract, which receives the image as a base64 JSON field.
 *
 * @param text           the user's text message (required, may be empty)
 * @param imageData      base64-encoded image data (nullable)
 * @param imageMediaType MIME type of the image, e.g. "image/png" (nullable)
 * @param prebuiltMsg    pre-built Msg with metadata (nullable; when set, text/image are ignored)
 */
public record MessageRequest(
        String text,
        String imageData,
        String imageMediaType,
        @Nullable Msg prebuiltMsg) {

    public MessageRequest(String text, String imageData, String imageMediaType) {
        this(text, imageData, imageMediaType, null);
    }

    public MessageRequest(Msg prebuiltMsg) {
        this(null, null, null, prebuiltMsg);
    }

    public static MessageRequest text(String msg) {
        return new MessageRequest(msg, null, null);
    }

    public boolean hasImage() {
        return imageData != null && !imageData.isEmpty();
    }

    public boolean hasPrebuiltMsg() {
        return prebuiltMsg != null;
    }
}
