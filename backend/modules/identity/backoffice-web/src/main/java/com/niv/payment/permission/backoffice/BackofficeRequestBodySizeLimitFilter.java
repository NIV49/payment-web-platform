package com.niv.payment.permission.backoffice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.util.MultiValueMap;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounds request bodies before JSON parsing, including chunked requests. */
final class BackofficeRequestBodySizeLimitFilter extends OncePerRequestFilter {
    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ObjectMapper json;
    private final int maximumBytes;
    private final boolean merchantDocumentUploadEnabled;

    BackofficeRequestBodySizeLimitFilter(ObjectMapper json, int maximumBytes) {
        this(json, maximumBytes, false);
    }

    BackofficeRequestBodySizeLimitFilter(ObjectMapper json, int maximumBytes,
                                         boolean merchantDocumentUploadEnabled) {
        this.json = json;
        if (maximumBytes < 1 || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Request body limit must be between 1 and Integer.MAX_VALUE - 1");
        }
        this.maximumBytes = maximumBytes;
        this.merchantDocumentUploadEnabled = merchantDocumentUploadEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/")
            || BODYLESS_METHODS.contains(request.getMethod())
            || isMerchantDocumentUpload(request);
    }

    private boolean isMerchantDocumentUpload(HttpServletRequest request) {
        String contentType = request.getContentType();
        return merchantDocumentUploadEnabled
            && "POST".equals(request.getMethod())
            && "/api/platform/merchant-document-uploads".equals(request.getRequestURI())
            && contentType != null
            && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (request.getContentLengthLong() > maximumBytes) {
            reject(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maximumBytes + 1);
        if (body.length > maximumBytes) {
            reject(response);
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(),
            BackofficeApiResponse.failure(41301, "PAYLOAD_TOO_LARGE", "Request body is too large"));
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final Map<String, String[]> formParameters;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
            this.formParameters = parseFormParameters(request, body);
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Asynchronous request-body reads are not supported");
                }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null
                ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }

        @Override
        public String getParameter(String name) {
            if (formParameters == null) {
                return super.getParameter(name);
            }
            String[] values = formParameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public String[] getParameterValues(String name) {
            if (formParameters == null) {
                return super.getParameterValues(name);
            }
            String[] values = formParameters.get(name);
            return values == null ? null : values.clone();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return formParameters == null
                ? super.getParameterNames() : Collections.enumeration(formParameters.keySet());
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            if (formParameters == null) {
                return super.getParameterMap();
            }
            Map<String, String[]> copy = new LinkedHashMap<>();
            formParameters.forEach((name, values) -> copy.put(name, values.clone()));
            return Collections.unmodifiableMap(copy);
        }

        private static Map<String, String[]> parseFormParameters(
            HttpServletRequest request, byte[] body) {
            String contentType = request.getContentType();
            if (contentType == null) {
                return null;
            }
            try {
                MediaType mediaType = MediaType.parseMediaType(contentType);
                if (!MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(mediaType)) {
                    return null;
                }
                Map<String, List<String>> values = new LinkedHashMap<>();
                append(values, parseForm(request.getQueryString()));
                append(values, parseForm(body));
                Map<String, String[]> parameters = new LinkedHashMap<>();
                values.forEach((name, entries) ->
                    parameters.put(name, entries.toArray(String[]::new)));
                return Collections.unmodifiableMap(parameters);
            } catch (IllegalArgumentException | IOException exception) {
                return Map.of("", new String[0]);
            }
        }

        private static MultiValueMap<String, String> parseForm(String encoded) throws IOException {
            return encoded == null ? null : parseForm(encoded.getBytes(StandardCharsets.UTF_8));
        }

        private static MultiValueMap<String, String> parseForm(byte[] encoded) throws IOException {
            if (encoded.length == 0) {
                return null;
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpInputMessage input = new HttpInputMessage() {
                @Override public ByteArrayInputStream getBody() {
                    return new ByteArrayInputStream(encoded);
                }
                @Override public HttpHeaders getHeaders() {
                    return headers;
                }
            };
            return new FormHttpMessageConverter().read(null, input);
        }

        private static void append(Map<String, List<String>> target,
                                   MultiValueMap<String, String> source) {
            if (source == null) {
                return;
            }
            source.forEach((name, values) ->
                target.computeIfAbsent(name, ignored -> new ArrayList<>()).addAll(values));
        }
    }
}
