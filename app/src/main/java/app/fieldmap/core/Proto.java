package app.fieldmap.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Tiny protobuf wire-format reader/writer, enough for the Meshtastic messages FieldMap uses. */
public final class Proto {
    private Proto() {}

    public static final int VARINT = 0, I64 = 1, LEN = 2, I32 = 5;

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private void raw(long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }

        private void tag(int field, int wire) { raw(((long) field << 3) | wire); }

        /** uint32 / uint64 / bool / enum. Skips zero (proto3 default). */
        public Writer varint(int field, long v) {
            if (v == 0) return this;
            tag(field, VARINT);
            raw(v);
            return this;
        }

        /** int32 that may be negative (encoded as 10-byte varint). */
        public Writer int32(int field, int v) {
            if (v == 0) return this;
            tag(field, VARINT);
            raw((long) v);
            return this;
        }

        public Writer bool(int field, boolean v) { return varint(field, v ? 1 : 0); }

        /** fixed32 / sfixed32. Skips zero unless force. */
        public Writer fixed32(int field, int v, boolean force) {
            if (v == 0 && !force) return this;
            tag(field, I32);
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 24) & 0xFF);
            return this;
        }

        public Writer fixed32(int field, int v) { return fixed32(field, v, false); }

        public Writer bytes(int field, byte[] b) {
            if (b == null) return this;
            tag(field, LEN);
            raw(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        public Writer string(int field, String s) {
            if (s == null || s.isEmpty()) return this;
            return bytes(field, s.getBytes(StandardCharsets.UTF_8));
        }

        public Writer message(int field, Writer m) { return bytes(field, m.toBytes()); }

        public byte[] toBytes() { return out.toByteArray(); }
    }

    /** Iterates over the fields of one message. */
    public static final class Reader {
        private final byte[] b;
        private int pos;
        public int field, wire;
        public long value;     // VARINT, I32, I64
        public int start, len; // LEN

        public Reader(byte[] b) { this.b = b == null ? new byte[0] : b; }

        public boolean next() {
            if (pos >= b.length) return false;
            long t = readRaw();
            field = (int) (t >>> 3);
            wire = (int) (t & 7);
            switch (wire) {
                case VARINT: value = readRaw(); break;
                case I64:
                    value = 0;
                    for (int i = 0; i < 8; i++) value |= (long) (b[pos++] & 0xFF) << (8 * i);
                    break;
                case LEN:
                    len = (int) readRaw();
                    start = pos;
                    pos += len;
                    if (pos > b.length) throw new IllegalArgumentException("truncated");
                    break;
                case I32:
                    value = (b[pos] & 0xFFL) | (b[pos + 1] & 0xFFL) << 8 | (b[pos + 2] & 0xFFL) << 16 | (b[pos + 3] & 0xFFL) << 24;
                    pos += 4;
                    break;
                default:
                    throw new IllegalArgumentException("wire type " + wire);
            }
            return true;
        }

        private long readRaw() {
            long r = 0;
            int shift = 0;
            while (true) {
                if (pos >= b.length) throw new IllegalArgumentException("truncated varint");
                int c = b[pos++] & 0xFF;
                r |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) return r;
                shift += 7;
                if (shift > 63) throw new IllegalArgumentException("bad varint");
            }
        }

        public int i32() { return (int) value; }

        public byte[] bytes() {
            byte[] r = new byte[len];
            System.arraycopy(b, start, r, 0, len);
            return r;
        }

        public String string() { return new String(b, start, len, StandardCharsets.UTF_8); }
    }
}
