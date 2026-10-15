package com.ticketing.ticketing;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QrCodeRendererTest {
    @Test
    void generatedPngDecodesToTheExactCredentialAndOriginalTicket() throws Exception {
        var codec = new TicketCredentialCodec(new TicketCredentialKeyRing("K1", Map.of("K1", new byte[32])));
        String number = "3eefbb49-3640-4758-a7d8-5509c11fbbb1";
        String text = codec.encode(number);
        byte[] png = new QrCodeRenderer().render(text);
        assertArrayEquals(new byte[] { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10 }, Arrays.copyOf(png, 8));
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(320, image.getWidth());
        assertEquals(320, image.getHeight());
        var source = new RGBLuminanceSource(image.getWidth(), image.getHeight(),
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
        String decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
        assertEquals(text, decoded);
        assertEquals(number, codec.verify(decoded));
    }

    @Test
    void missingAndOversizedInputAreRejectedBeforeImageEncoding() {
        var renderer = new QrCodeRenderer();
        for (String value : Arrays.asList(null, "", " ", "A".repeat(257))) {
            var error = assertThrows(IllegalArgumentException.class, () -> renderer.render(value));
            assertEquals("Ticket QR input is missing or too long", error.getMessage());
        }
    }
}
