package com.niv.payment.permission.cache;

import com.niv.payment.permission.domain.GrantSnapshot;

public interface GrantSnapshotCodec {
    byte[] encode(GrantSnapshot snapshot);

    GrantSnapshot decode(byte[] value);
}
