package app.fieldmap.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** KISS TNC framing (data frames on port 0). */
public final class Kiss {
    public static final int FEND = 0xC0, FESC = 0xDB, TFEND = 0xDC, TFESC = 0xDD;

    /** Wraps an AX.25 frame for sending to the TNC. */
    public static byte[] encode(byte[] ax25) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(ax25.length + 8);
        out.write(FEND);
        out.write(0x00); // data frame, port 0
        for (byte b : ax25) {
            int v = b & 0xFF;
            if (v == FEND) { out.write(FESC); out.write(TFEND); }
            else if (v == FESC) { out.write(FESC); out.write(TFESC); }
            else out.write(v);
        }
        out.write(FEND);
        return out.toByteArray();
    }

    /** Streaming decoder: feed raw bytes from the radio, get whole AX.25 frames back. */
    public static final class Decoder {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private boolean inFrame = false, escape = false;

        public List<byte[]> feed(byte[] data, int len) {
            List<byte[]> frames = new ArrayList<>();
            for (int i = 0; i < len; i++) {
                int v = data[i] & 0xFF;
                if (v == FEND) {
                    if (inFrame && buf.size() > 1) {
                        byte[] f = buf.toByteArray();
                        // first byte is the command: low nibble 0 = data frame
                        if ((f[0] & 0x0F) == 0) {
                            byte[] ax = new byte[f.length - 1];
                            System.arraycopy(f, 1, ax, 0, ax.length);
                            frames.add(ax);
                        }
                    }
                    buf.reset();
                    inFrame = true;
                    escape = false;
                    continue;
                }
                if (!inFrame) continue;
                if (escape) {
                    if (v == TFEND) buf.write(FEND);
                    else if (v == TFESC) buf.write(FESC);
                    else buf.write(v);
                    escape = false;
                } else if (v == FESC) {
                    escape = true;
                } else {
                    buf.write(v);
                }
                if (buf.size() > 4096) { buf.reset(); inFrame = false; }
            }
            return frames;
        }
    }
}
