package com.niv.payment.permission.backoffice;

import com.niv.payment.dictionary.core.DictionaryCommands;
import com.niv.payment.dictionary.core.DictionaryModels;
import com.niv.payment.dictionary.core.SystemDictionaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api")
final class BackofficeSystemDictionaryReadController {
    private final SystemDictionaryService dictionaries;

    BackofficeSystemDictionaryReadController(SystemDictionaryService dictionaries) {
        this.dictionaries = Objects.requireNonNull(dictionaries, "dictionaries");
    }

    @GetMapping("/system/dictionary-types/options")
    BackofficeApiResponse<List<TypeOptionResponse>> typeOptions() {
        return BackofficeApiResponse.success(dictionaries.findTypeOptions().stream()
            .map(option -> new TypeOptionResponse(option.dictType(), option.dictName())).toList());
    }

    @GetMapping("/system/dictionary-data")
    BackofficeApiResponse<PageResponse<DictionaryDataResponse>> data(
        @RequestParam String dictType,
        @RequestParam(required = false) String label,
        @RequestParam(required = false) String value,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize) {
        var result = dictionaries.findData(new DictionaryCommands.DataQuery(
            dictType, label, value, page, pageSize));
        return BackofficeApiResponse.success(new PageResponse<>(result.items().stream()
            .map(BackofficeSystemDictionaryReadController::dataResponse).toList(), result.total()));
    }

    @PostMapping("/dict/queryBatch")
    BackofficeApiResponse<Map<String, List<DisplayValueResponse>>> queryBatch(
        @RequestBody BatchRequest body) {
        Map<String, List<DisplayValueResponse>> response = new LinkedHashMap<>();
        dictionaries.queryBatch(body == null ? null : body.dictTypes()).forEach((type, values) ->
            response.put(type, values.stream()
                .map(item -> new DisplayValueResponse(item.value(), item.label(), item.color())).toList()));
        return BackofficeApiResponse.success(response);
    }

    private static DictionaryDataResponse dataResponse(DictionaryModels.DictionaryData item) {
        return new DictionaryDataResponse(Long.toString(item.id()), item.dictType(), item.label(),
            item.value(), item.color(), item.sort(), item.remark(), item.rowVersion(),
            item.createTime().toString());
    }

    record TypeOptionResponse(String dictType, String dictName) { }
    record PageResponse<T>(List<T> items, long total) { }
    record DictionaryDataResponse(String dictCode, String dictType, String label, String value,
                                  String color, int sort, String remark, long rowVersion,
                                  String createTime) { }
    record DisplayValueResponse(String value, String label, String color) { }
    record BatchRequest(List<String> dictTypes) { }
}
