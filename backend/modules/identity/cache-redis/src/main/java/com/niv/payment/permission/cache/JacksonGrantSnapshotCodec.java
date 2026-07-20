package com.niv.payment.permission.cache;

import com.niv.payment.permission.domain.GrantSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/** JSON codec kept inside the Redis adapter; the authorization core stays framework-free. */
public final class JacksonGrantSnapshotCodec implements GrantSnapshotCodec {
    private final ObjectMapper json;

    public JacksonGrantSnapshotCodec(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public byte[] encode(GrantSnapshot snapshot) {
        try {
            return json.writeValueAsBytes(snapshot);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Permission grant snapshot could not be encoded", exception);
        }
    }

    @Override
    public GrantSnapshot decode(byte[] value) {
        try {
            return json.readValue(value, GrantSnapshot.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Permission grant snapshot could not be decoded", exception);
        }
    }
}
