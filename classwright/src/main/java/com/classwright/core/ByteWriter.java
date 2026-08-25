package com.classwright.core;

import java.util.Arrays;

/**
 * A growable big-endian byte buffer.
 *
 * <p>Class files are big-endian and full of length prefixes whose values are only known after the
 * thing they measure has been written, so the two operations this needs to be good at are appending
 * and patching a value that was already emitted. {@link java.io.DataOutputStream} does the first
 * and not the second; {@link java.nio.ByteBuffer} does both but is fixed-capacity and its API
 * invites off-by-one errors around {@code position} and {@code limit}. This is thirty lines and
 * does exactly what the class-file format needs.
 *
 * <p>Package-private: this is an implementation detail of the engine, not part of the API.
 */
final class ByteWriter {

    /** Constant-pool strings and method code are both capped at 65535 by the format. */
    static final int U2_MAX = 0xFFFF;

    private byte[] data;
    private int length;

    ByteWriter() {
        this(64);
    }

    ByteWriter(int initialCapacity) {
        this.data = new byte[Math.max(initialCapacity, 8)];
    }

    /** Appends one unsigned byte. */
    ByteWriter u1(int value) {
        ensure(1);
        data[length++] = (byte) value;
        return this;
    }

    /** Appends one unsigned big-endian 16-bit value. */
    ByteWriter u2(int value) {
        ensure(2);
        data[length++] = (byte) (value >>> 8);
        data[length++] = (byte) value;
        return this;
    }

    /** Appends one big-endian 32-bit value. */
    ByteWriter u4(int value) {
        ensure(4);
        data[length++] = (byte) (value >>> 24);
        data[length++] = (byte) (value >>> 16);
        data[length++] = (byte) (value >>> 8);
        data[length++] = (byte) value;
        return this;
    }

    /** Appends one big-endian 64-bit value. */
    ByteWriter u8(long value) {
        u4((int) (value >>> 32));
        u4((int) value);
        return this;
    }

    /** Appends raw bytes. */
    ByteWriter bytes(byte[] source) {
        return bytes(source, 0, source.length);
    }

    ByteWriter bytes(byte[] source, int offset, int count) {
        ensure(count);
        System.arraycopy(source, offset, data, length, count);
        length += count;
        return this;
    }

    /** Appends the contents of another writer. */
    ByteWriter bytes(ByteWriter source) {
        return bytes(source.data, 0, source.length);
    }

    /**
     * Overwrites a previously written 16-bit value.
     *
     * <p>Used for branch offsets, which point forwards to code that has not been emitted yet: the
     * jump instruction reserves two bytes, and they are filled in once the target label is bound.
     *
     * @param position byte offset of the first of the two bytes
     * @param value    the value to write there
     */
    void patchU2(int position, int value) {
        data[position] = (byte) (value >>> 8);
        data[position + 1] = (byte) value;
    }

    /** Overwrites a previously written 32-bit value. Switch tables use 32-bit jump offsets. */
    void patchU4(int position, int value) {
        data[position] = (byte) (value >>> 24);
        data[position + 1] = (byte) (value >>> 16);
        data[position + 2] = (byte) (value >>> 8);
        data[position + 3] = (byte) value;
    }

    /**
     * Appends a string in <em>modified</em> UTF-8, the encoding the class-file format uses for
     * {@code CONSTANT_Utf8}.
     *
     * <p>Two things distinguish it from real UTF-8, and both matter:
     *
     * <ul>
     *   <li>{@code U+0000} is encoded as two bytes rather than one, so that encoded strings never
     *       contain a zero byte.</li>
     *   <li>Characters outside the basic multilingual plane are encoded as their two UTF-16
     *       surrogates, three bytes each, rather than as a single four-byte sequence.</li>
     * </ul>
     *
     * <p>Iterating over {@code char} values rather than code points gives the surrogate behaviour
     * for free, since each surrogate is itself in the three-byte range.
     *
     * @param value the string to encode
     * @throws CodeGenerationException if the encoded form exceeds the 65535-byte format limit
     */
    ByteWriter modifiedUtf8(String value) {
        int start = length;
        // Worst case up front — three bytes per char plus the prefix — so the encode loop below
        // is branch-free on capacity. Names and descriptors run through here for every pool
        // entry, and a per-character capacity check was pure overhead; the transient
        // over-reservation is at most 2 * length bytes on a buffer that grows geometrically.
        ensure(3 * value.length() + 2);
        int lengthPosition = length;
        length += 2;                        // reserve the u2 byte-count prefix

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                data[length++] = (byte) c;
            } else if (c <= 0x07FF) {       // also covers U+0000, deliberately as two bytes
                data[length++] = (byte) (0xC0 | ((c >> 6) & 0x1F));
                data[length++] = (byte) (0x80 | (c & 0x3F));
            } else {
                data[length++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
                data[length++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                data[length++] = (byte) (0x80 | (c & 0x3F));
            }
        }

        int encodedLength = length - lengthPosition - 2;
        if (encodedLength > U2_MAX) {
            length = start;
            throw new CodeGenerationException(
                    "string is " + encodedLength + " bytes in modified UTF-8, but the class-file "
                            + "format caps a constant-pool string at " + U2_MAX);
        }
        patchU2(lengthPosition, encodedLength);
        return this;
    }

    int length() {
        return length;
    }

    /**
     * Discards everything written after {@code mark}.
     *
     * <p>For callers whose multi-part writes must be atomic: {@code modifiedUtf8} rolls back its
     * own bytes when a string is oversized, but a caller that wrote a tag byte first needs to
     * roll back to <em>its</em> mark, or a catch-and-continue serialises a corrupt stream.
     */
    void truncate(int mark) {
        length = mark;
    }

    byte[] toByteArray() {
        return Arrays.copyOf(data, length);
    }

    private void ensure(int additional) {
        int required = length + additional;
        if (required > data.length) {
            data = Arrays.copyOf(data, Math.max(required, data.length * 2));
        }
    }
}
