package com.niv.payment.dictionary.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DictionaryBatchCache {
    /** Empty Optional means the cache could not provide trustworthy results. Missing map keys are cache misses. */
    Optional<Map<String, List<DictionaryModels.DisplayValue>>> find(long revision, List<String> types);
    void store(long revision, Map<String, List<DictionaryModels.DisplayValue>> values);
}
