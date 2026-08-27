package com.niv.payment.dictionary.core;

public final class DictionaryCommands {
    private DictionaryCommands() { }

    public record TypeQuery(String dictType, String dictName, int page, int pageSize) { }
    public record DataQuery(String dictType, String label, String value, int page, int pageSize) { }
    public record CreateType(String dictType, String dictName, int sort, String remark) { }
    public record UpdateType(String dictType, String dictName, int sort, String remark,
                             long expectedVersion) { }
    public record CreateData(String dictType, String label, String value, String color,
                             int sort, String remark) { }
    public record UpdateData(String dictType, String label, String value, String color,
                             int sort, String remark, long expectedVersion) { }
}
