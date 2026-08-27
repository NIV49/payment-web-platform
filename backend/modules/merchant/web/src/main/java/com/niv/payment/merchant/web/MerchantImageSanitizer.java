package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.MerchantException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

final class MerchantImageSanitizer {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private MerchantImageSanitizer() { }

    static Sanitized sanitize(byte[] source) {
        if (source == null || source.length < 8 || source.length > MAX_BYTES) throw invalid();
        String expected = magic(source);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) throw invalid();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || width > 4096 || height < 1 || height > 4096
                    || (long) width * height > 12_000_000L) throw invalid();
                String actual = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (("png".equals(expected) && !actual.contains("png"))
                    || ("jpeg".equals(expected) && !(actual.contains("jpeg") || actual.contains("jpg")))) {
                    throw invalid();
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw invalid();
                }
                BufferedImage normalized = decoded;
                if ("jpeg".equals(expected)) {
                    normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = normalized.createGraphics();
                    try {
                        graphics.setColor(Color.WHITE);
                        graphics.fillRect(0, 0, width, height);
                        graphics.drawImage(decoded, 0, 0, null);
                    } finally { graphics.dispose(); }
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(source.length, MAX_BYTES));
                if (!ImageIO.write(normalized, expected, output)) throw invalid();
                byte[] sanitized = output.toByteArray();
                if (sanitized.length < 1 || sanitized.length > MAX_BYTES) throw invalid();
                return new Sanitized("png".equals(expected) ? "image/png" : "image/jpeg",
                    width, height, sanitized);
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof MerchantException.InvalidRequest invalid) throw invalid;
            throw invalid();
        }
    }

    private static String magic(byte[] source) {
        if ((source[0] & 0xff) == 0x89 && source[1] == 'P' && source[2] == 'N'
            && source[3] == 'G' && source[4] == 0x0d && source[5] == 0x0a
            && source[6] == 0x1a && source[7] == 0x0a) return "png";
        if ((source[0] & 0xff) == 0xff && (source[1] & 0xff) == 0xd8
            && (source[2] & 0xff) == 0xff) return "jpeg";
        throw invalid();
    }

    private static MerchantException.InvalidRequest invalid() {
        return new MerchantException.InvalidRequest("Invalid Merchant image");
    }

    record Sanitized(String mediaType, int width, int height, byte[] content) {
        Sanitized { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
}
