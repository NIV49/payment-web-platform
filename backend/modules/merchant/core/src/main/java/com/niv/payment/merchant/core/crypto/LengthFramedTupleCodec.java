package com.niv.payment.merchant.core.crypto;

import com.niv.payment.merchant.core.MerchantException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class LengthFramedTupleCodec {
    public static final int VERSION_1 = 1;
    private static final byte[] MAGIC = {'M', 'C', 'H', 'T'};

    private LengthFramedTupleCodec() {
    }

    public static byte[] encode(int version, String... fields) {
        if (version != VERSION_1 || fields == null) {
            throw new MerchantException.ProtectedFieldUnavailable();
        }
        byte[][] encodedFields = new byte[fields.length][];
        int size = MAGIC.length + Integer.BYTES + Integer.BYTES;
        try {
            for (int index = 0; index < fields.length; index++) {
                encodedFields[index] = fields[index] == null
                    ? null
                    : fields[index].getBytes(StandardCharsets.UTF_8);
                size = Math.addExact(size, Integer.BYTES);
                if (encodedFields[index] != null) {
                    size = Math.addExact(size, encodedFields[index].length);
                }
            }
        } catch (ArithmeticException exception) {
            throw new MerchantException.ProtectedFieldUnavailable();
        }

        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put(MAGIC);
        buffer.putInt(version);
        buffer.putInt(encodedFields.length);
        for (byte[] field : encodedFields) {
            if (field == null) {
                buffer.putInt(-1);
            } else {
                buffer.putInt(field.length);
                buffer.put(field);
            }
        }
        return buffer.array();
    }
}
