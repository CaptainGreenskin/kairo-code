package io.kairo.code.service.agent;

/**
 * Transport-agnostic representation of an inbound user message.
 *
 * <p>{@code imageData} is a base64-encoded string (not {@code byte[]}) to match
 * the WebSocket handler contract, which receives the image as a base64 JSON field.
 *
 * @param text           the user's text message (required, may be empty)
 * @param imageData      base64-encoded image data (nullable)
 * @param imageMediaType MIME type of the image, e.g. "image/png" (nullable)
 */
public record MessageRequest(String text, String imageData, String imageMediaType) {

    /**
     * Convenience factory for text-only messages.
     */
    public static MessageRequest text(String msg) {
        return new MessageRequest(msg, null, null);
    }

    /**
     * @return {@code true} when this request carries an image attachment.
     */
    public boolean hasImage() {
        return imageData != null && !imageData.isEmpty();
    }
}
