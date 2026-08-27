package com.niv.payment.merchant.core.crypto;

import com.niv.payment.merchant.core.MerchantException;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LengthFramedTupleCodecTest {
    @Test
    void freezesTheVersionOneBinaryFormat() {
        byte[] encoded = LengthFramedTupleCodec.encode(1, "A", "BC");

        assertArrayEquals(
            HexFormat.of().parseHex("4d43485400000001000000020000000141000000024243"),
            encoded
        );
    }

    @Test
    void distinguishesFieldBoundariesNullAndEmpty() {
        String splitAfterFirstCharacter = HexFormat.of().formatHex(
            LengthFramedTupleCodec.encode(1, "A", "BC")
        );
        String splitAfterSecondCharacter = HexFormat.of().formatHex(
            LengthFramedTupleCodec.encode(1, "AB", "C")
        );
        String nullValue = HexFormat.of().formatHex(
            LengthFramedTupleCodec.encode(1, (String) null)
        );
        String emptyValue = HexFormat.of().formatHex(
            LengthFramedTupleCodec.encode(1, "")
        );

        assertNotEquals(splitAfterFirstCharacter, splitAfterSecondCharacter);
        assertNotEquals(nullValue, emptyValue);
    }

    @Test
    void failsClosedForAnUnknownCodecVersion() {
        assertThrows(MerchantException.ProtectedFieldUnavailable.class,
            () -> LengthFramedTupleCodec.encode(2, "value"));
    }
}
