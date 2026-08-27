package com.niv.payment.dictionary.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DictionaryCatalogRepository {
    DictionaryModels.Page<DictionaryModels.DictionaryType> findTypes(DictionaryCommands.TypeQuery query);
    List<DictionaryModels.TypeOption> findTypeOptions();
    DictionaryModels.Page<DictionaryModels.DictionaryData> findData(DictionaryCommands.DataQuery query);
    long currentRevision();
    Map<String, List<DictionaryModels.DisplayValue>> findDisplayValues(Set<String> types);
    long createType(DictionaryModels.Actor actor, DictionaryCommands.CreateType command);
    void updateType(DictionaryModels.Actor actor, long id, DictionaryCommands.UpdateType command);
    void deleteType(DictionaryModels.Actor actor, long id, long expectedVersion);
    long createData(DictionaryModels.Actor actor, DictionaryCommands.CreateData command);
    void updateData(DictionaryModels.Actor actor, long id, DictionaryCommands.UpdateData command);
    void deleteData(DictionaryModels.Actor actor, long id, long expectedVersion);
}
