package com.niv.payment.dictionary.core;

import java.time.Instant;
import java.util.List;

public final class DictionaryModels {
    private DictionaryModels() { }

    public record Actor(long tenantId, long membershipId, long userId,
                        long permissionVersion, long sessionVersion, String accountDomain) { }

    public record Page<T>(List<T> items, long total) {
        public Page {
            items = List.copyOf(items);
            if (total < 0) throw new IllegalArgumentException("Total cannot be negative");
        }
    }

    public record DictionaryType(long id, String dictType, String dictName, int sort,
                                 String remark, long rowVersion, Instant createTime) { }

    public record TypeOption(String dictType, String dictName) { }

    public record DictionaryData(long id, String dictType, String label, String value,
                                 String color, int sort, String remark, long rowVersion,
                                 Instant createTime) { }

    public record DisplayValue(String value, String label, String color) { }
}
