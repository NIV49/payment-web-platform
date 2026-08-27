package com.niv.payment.adminapi.web;

import com.niv.payment.dictionary.core.DictionaryCommands;
import com.niv.payment.dictionary.core.DictionaryModels;
import com.niv.payment.dictionary.core.SystemDictionaryException;
import com.niv.payment.dictionary.core.SystemDictionaryService;
import com.niv.payment.permission.domain.AuthorizationSubject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
public final class SystemDictionaryController {
    private final SystemDictionaryService dictionaries;

    public SystemDictionaryController(SystemDictionaryService dictionaries) {
        this.dictionaries = Objects.requireNonNull(dictionaries, "dictionaries");
    }

    @GetMapping("/system/dictionaries")
    ApiResponse<PageResponse<DictionaryTypeResponse>> types(
        @RequestParam(required = false) String dictType,
        @RequestParam(required = false) String dictName,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize) {
        var result = dictionaries.findTypes(new DictionaryCommands.TypeQuery(
            dictType, dictName, page, pageSize));
        return ApiResponse.success(new PageResponse<>(result.items().stream()
            .map(SystemDictionaryController::typeResponse).toList(), result.total()));
    }

    @PostMapping("/system/dictionaries")
    ApiResponse<IdResponse> createType(HttpServletRequest request,
                                       @RequestBody TypeCreateRequest body) {
        long id = dictionaries.createType(actor(request), new DictionaryCommands.CreateType(
            body.dictType(), body.dictName(), body.sort(), body.remark()));
        return ApiResponse.success(new IdResponse(Long.toString(id)));
    }

    @PutMapping("/system/dictionaries/{dictId}")
    ApiResponse<Void> updateType(HttpServletRequest request, @PathVariable long dictId,
                                 @RequestBody TypeUpdateRequest body) {
        dictionaries.updateType(actor(request), dictId, new DictionaryCommands.UpdateType(
            body.dictType(), body.dictName(), body.sort(), body.remark(),
            requiredVersion(body.expectedVersion())));
        return ApiResponse.success(null);
    }

    @DeleteMapping("/system/dictionaries/{dictId}")
    ApiResponse<Void> deleteType(HttpServletRequest request, @PathVariable long dictId,
                                 @RequestParam Long expectedVersion) {
        dictionaries.deleteType(actor(request), dictId, requiredVersion(expectedVersion));
        return ApiResponse.success(null);
    }

    @GetMapping("/system/dictionary-types/options")
    ApiResponse<List<TypeOptionResponse>> typeOptions() {
        return ApiResponse.success(dictionaries.findTypeOptions().stream()
            .map(option -> new TypeOptionResponse(option.dictType(), option.dictName())).toList());
    }

    @GetMapping("/system/dictionary-data")
    ApiResponse<PageResponse<DictionaryDataResponse>> data(
        @RequestParam String dictType,
        @RequestParam(required = false) String label,
        @RequestParam(required = false) String value,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize) {
        var result = dictionaries.findData(new DictionaryCommands.DataQuery(
            dictType, label, value, page, pageSize));
        return ApiResponse.success(new PageResponse<>(result.items().stream()
            .map(SystemDictionaryController::dataResponse).toList(), result.total()));
    }

    @PostMapping("/dict/queryBatch")
    ApiResponse<Map<String, List<DisplayValueResponse>>> queryBatch(
        @RequestBody BatchRequest body) {
        Map<String, List<DisplayValueResponse>> response = new LinkedHashMap<>();
        dictionaries.queryBatch(body == null ? null : body.dictTypes()).forEach((type, values) ->
            response.put(type, values.stream().map(SystemDictionaryController::displayResponse).toList()));
        return ApiResponse.success(response);
    }

    @PostMapping("/system/dictionary-data")
    ApiResponse<IdResponse> createData(HttpServletRequest request,
                                       @RequestBody DataCreateRequest body) {
        long id = dictionaries.createData(actor(request), new DictionaryCommands.CreateData(
            body.dictType(), body.label(), body.value(), body.color(), body.sort(), body.remark()));
        return ApiResponse.success(new IdResponse(Long.toString(id)));
    }

    @PutMapping("/system/dictionary-data/{dictCode}")
    ApiResponse<Void> updateData(HttpServletRequest request, @PathVariable long dictCode,
                                 @RequestBody DataUpdateRequest body) {
        dictionaries.updateData(actor(request), dictCode, new DictionaryCommands.UpdateData(
            body.dictType(), body.label(), body.value(), body.color(), body.sort(), body.remark(),
            requiredVersion(body.expectedVersion())));
        return ApiResponse.success(null);
    }

    @DeleteMapping("/system/dictionary-data/{dictCode}")
    ApiResponse<Void> deleteData(HttpServletRequest request, @PathVariable long dictCode,
                                 @RequestParam Long expectedVersion) {
        dictionaries.deleteData(actor(request), dictCode, requiredVersion(expectedVersion));
        return ApiResponse.success(null);
    }

    private static DictionaryModels.Actor actor(HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        return new DictionaryModels.Actor(subject.tenantId(), subject.membershipId(), subject.userId(),
            subject.permissionVersion(), subject.sessionVersion(), "PLATFORM");
    }

    private static long requiredVersion(Long version) {
        if (version == null) throw new SystemDictionaryException.InvalidRequest("expectedVersion is required");
        return version;
    }

    private static DictionaryTypeResponse typeResponse(DictionaryModels.DictionaryType item) {
        return new DictionaryTypeResponse(Long.toString(item.id()), item.dictType(), item.dictName(),
            item.sort(), item.remark(), item.rowVersion(), item.createTime().toString());
    }

    private static DictionaryDataResponse dataResponse(DictionaryModels.DictionaryData item) {
        return new DictionaryDataResponse(Long.toString(item.id()), item.dictType(), item.label(),
            item.value(), item.color(), item.sort(), item.remark(), item.rowVersion(),
            item.createTime().toString());
    }

    private static DisplayValueResponse displayResponse(DictionaryModels.DisplayValue item) {
        return new DisplayValueResponse(item.value(), item.label(), item.color());
    }

    record PageResponse<T>(List<T> items, long total) { }
    record IdResponse(String id) { }
    record DictionaryTypeResponse(String dictId, String dictType, String dictName, int sort,
                                  String remark, long rowVersion, String createTime) { }
    record TypeOptionResponse(String dictType, String dictName) { }
    record DictionaryDataResponse(String dictCode, String dictType, String label, String value,
                                  String color, int sort, String remark, long rowVersion,
                                  String createTime) { }
    record DisplayValueResponse(String value, String label, String color) { }
    record BatchRequest(List<String> dictTypes) { }
    record TypeCreateRequest(String dictType, String dictName, int sort, String remark) { }
    record TypeUpdateRequest(String dictType, String dictName, int sort, String remark,
                             Long expectedVersion) { }
    record DataCreateRequest(String dictType, String label, String value, String color,
                             int sort, String remark) { }
    record DataUpdateRequest(String dictType, String label, String value, String color,
                             int sort, String remark, Long expectedVersion) { }
}
