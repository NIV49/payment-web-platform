package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentContent;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentKind;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentUploadRequest;
import com.niv.payment.merchant.core.MerchantOnboardingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.util.Set;

@RestController
@RequestMapping("/api/platform")
final class MerchantDocumentController {
    private static final long MAX_DOCUMENT_BYTES = 2L * 1024 * 1024;
    private final MerchantOnboardingService service;
    private final MerchantSubjectAdapter subjects;
    private final MerchantRequestTrace trace;

    MerchantDocumentController(MerchantOnboardingService service, MerchantSubjectAdapter subjects,
                               MerchantRequestTrace trace) {
        this.service = service;
        this.subjects = subjects;
        this.trace = trace;
    }

    @PostMapping(value = "/merchant-document-uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    MerchantApiResponse<MerchantOnboardingResponses.Upload> upload(
        @RequestParam("targetTenantId") String targetTenantId,
        @RequestParam("kind") String kind,
        @RequestParam("file") MultipartFile file,
        HttpServletRequest request
    ) {
        exactParts(request);
        long tenantId = positiveId(targetTenantId);
        DocumentKind documentKind = kind(kind);
        if (file.getSize() > MAX_DOCUMENT_BYTES) {
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(
                MAX_DOCUMENT_BYTES);
        }
        try {
            MerchantImageSanitizer.Sanitized image = MerchantImageSanitizer.sanitize(file.getBytes());
            var metadata = service.uploadDocument(subjects.actor(request), new DocumentUploadRequest(
                tenantId, documentKind, image.mediaType(), image.content(), image.width(), image.height()));
            return MerchantApiResponse.success(MerchantOnboardingResponses.upload(metadata), trace);
        } catch (IOException exception) {
            throw MerchantJson.invalid();
        }
    }

    @DeleteMapping("/merchant-document-uploads/{documentId}")
    MerchantApiResponse<MerchantOnboardingResponses.Deleted> delete(
        @PathVariable long documentId, HttpServletRequest request
    ) {
        service.deleteDocument(subjects.actor(request), documentId);
        return MerchantApiResponse.success(new MerchantOnboardingResponses.Deleted(
            Long.toString(documentId), "DELETED"), trace);
    }

    @GetMapping("/merchant-document-uploads/{documentId}/content")
    ResponseEntity<byte[]> stagedContent(
        @PathVariable long documentId, HttpServletRequest request
    ) {
        return content(service.stagedDocumentContent(subjects.actor(request), documentId));
    }

    @GetMapping("/merchants/{merchantId}/documents/{kind}/content")
    ResponseEntity<byte[]> merchantContent(
        @PathVariable long merchantId,
        @PathVariable String kind,
        @RequestParam(value = "amendmentId", required = false) String amendmentId,
        HttpServletRequest request
    ) {
        return content(service.merchantDocumentContent(subjects.actor(request), merchantId, kind(kind),
            amendmentId == null ? null : positiveId(amendmentId)));
    }

    private static ResponseEntity<byte[]> content(DocumentContent source) {
        byte[] bytes = source.content();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(source.mediaType()))
            .contentLength(bytes.length)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "sandbox; default-src 'none'")
            .body(bytes);
    }

    private static DocumentKind kind(String value) {
        try { return DocumentKind.valueOf(value); }
        catch (IllegalArgumentException exception) { throw MerchantJson.invalid(); }
    }

    private static long positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw MerchantJson.invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw MerchantJson.invalid(); }
    }

    private static void exactParts(HttpServletRequest request) {
        if (!(request instanceof MultipartHttpServletRequest multipart)
            || !request.getParameterMap().keySet().equals(Set.of("targetTenantId", "kind"))
            || request.getParameterValues("targetTenantId").length != 1
            || request.getParameterValues("kind").length != 1
            || !multipart.getMultiFileMap().keySet().equals(Set.of("file"))
            || multipart.getMultiFileMap().get("file").size() != 1) {
            throw MerchantJson.invalid();
        }
    }
}
