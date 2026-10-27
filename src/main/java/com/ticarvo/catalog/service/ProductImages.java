package com.ticarvo.catalog.service;
import com.ticarvo.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Validates and re-encodes uploaded product images (ADR-007): JPEG/PNG detected from the bytes, dimensions read
 * from the header before decoding, and a fresh encode so embedded metadata (EXIF, GPS, comments) is dropped.
 */
final class ProductImages {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_SIDE = 4000;
    record Processed(String contentType, byte[] bytes, int width, int height) {}
    static { ImageIO.setUseCache(false); }
    private ProductImages() {}

    static Processed process(byte[] input) {
        if (input == null || input.length == 0 || input.length > MAX_BYTES) throw invalid();
        String format = isJpeg(input) ? "jpeg" : isPng(input) ? "png" : null;
        if (format == null) throw invalid();
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReadersByFormatName(format);
            if (stream == null || !readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                // Checked before decoding, so a tiny file cannot expand into a huge bitmap.
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) throw invalid();
                BufferedImage image = reader.read(0);
                var out = new ByteArrayOutputStream();
                if (format.equals("jpeg")) writeJpeg(image, out); else ImageIO.write(image, "png", out);
                return new Processed(format.equals("jpeg") ? "image/jpeg" : "image/png", out.toByteArray(), width, height);
            } finally {
                reader.dispose();
            }
        } catch (ApiException rejected) {
            throw rejected;
        } catch (IOException | RuntimeException unreadable) {
            throw invalid();
        }
    }
    private static void writeJpeg(BufferedImage image, ByteArrayOutputStream out) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.9f);
        try (var target = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(target);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
    private static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
    }
    private static boolean isPng(byte[] b) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        if (b.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (b[i] != signature[i]) return false;
        return true;
    }
    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_INVALID", "Upload a JPEG or PNG image up to 5 MB and 4000x4000 pixels.");
    }
}
