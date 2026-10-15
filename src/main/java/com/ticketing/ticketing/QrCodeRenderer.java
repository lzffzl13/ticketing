package com.ticketing.ticketing;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/** PNG transport for credential text; it does not grant or consume admission rights. */
public class QrCodeRenderer {
    private static final int SIZE = 320;

    public byte[] render(String credential) {
        if (credential == null || credential.isBlank() || credential.length() > 256) {
            throw new IllegalArgumentException("Ticket QR input is missing or too long");
        }
        try {
            var matrix = new QRCodeWriter().encode(credential, BarcodeFormat.QR_CODE, SIZE, SIZE,
                    Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 4,
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) { throw new IOException("PNG encoder unavailable"); }
            return output.toByteArray();
        } catch (WriterException | IOException exception) {
            throw new IllegalStateException("Ticket QR image could not be generated");
        }
    }
}
