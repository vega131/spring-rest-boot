package com.github.bingoohuang.springrest.boot.interceptor;

import com.github.bingoohuang.springrest.boot.annotations.RestfulSign;
import com.github.bingoohuang.springrest.boot.filter.BufferedRequestWrapper;
import com.github.bingoohuang.utils.codec.Base64;
import com.github.bingoohuang.utils.net.Http;
import com.github.bingoohuang.utils.sys.Hostname;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import lombok.SneakyThrows;
import lombok.experimental.var;
import lombok.val;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;

public class SignInterceptor extends HandlerInterceptorAdapter {
    public static final String CLIENT_SECURITY = "d51fd93e-f6c9-4eae-ae7a-9b37af1a60cc";

    @Override
    public boolean preHandle(
            HttpServletRequest req, HttpServletResponse rsp, Object handler) {
        rsp.addHeader("Rest-Server", Hostname.HOSTNAME);

        if (!(handler instanceof HandlerMethod)) return false;

        val method = (HandlerMethod) handler;
        val beanType = method.getBeanType();
        val ignoreSign = ignoreSign(beanType, method);
        val log = LoggerFactory.getLogger("rest." + beanType.getName());

        if (ignoreSign && !log.isInfoEnabled()) return true;

        var hici = req.getHeader("hici");
        if (StringUtils.isEmpty(hici)) hici = UUID.randomUUID().toString();

        req.setAttribute("_log_hici", hici);
        req.setAttribute("_log_start", System.currentTimeMillis());

        val contentType = req.getContentType();
        val lowerContentType = StringUtils.lowerCase(contentType);
        String requestBody = null;
        if (containsAnyOrNull(lowerContentType, "json", "xml", "text")) {
            val requestWrapper = (BufferedRequestWrapper) req.getAttribute("_log_req");
            requestBody = requestWrapper.getRequestBody();
        }
        if (StringUtils.isEmpty(requestBody)) requestBody = "(empty)";

        val signStr = new StringBuilder();
        val logStr = new StringBuilder();
        val proxy = new AbbreviateAppendable(logStr, signStr);
        createOriginalStringForSign(proxy, req);
        log.info("spring rest server {} request {} body: {}", hici, logStr, requestBody);

        if (ignoreSign) return true;

        val hisv = req.getHeader("hisv");
        if (Strings.isNullOrEmpty(hisv)) {
            log.info("spring rest server {} signature missed", hici);
            Http.error(rsp, 416, "signature missed");
            return false;
        }

        val sign = hmacSHA256(signStr.toString(), CLIENT_SECURITY);
        val signOk = sign.equals(hisv);
        log.info("spring rest server {} sign result {}", hici, signOk);
        if (!signOk) {
            Http.error(rsp, 416, "invalid signature");
        }

        return signOk;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest req, HttpServletResponse rsp,
            Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod)) return;

        val method = (HandlerMethod) handler;
        val beanType = method.getBeanType();
        val log = LoggerFactory.getLogger("rest." + beanType.getName());
        if (!log.isInfoEnabled()) return;

        val headerSb = new StringBuilder();
        val headerNames = rsp.getHeaderNames();
        val joiner = Joiner.on(',');
        for (val headerName : headerNames) {
            headerSb.append(headerName).append('=');
            val headers = rsp.getHeaders(headerName);
            joiner.join(headers);
            headerSb.append(headers).append('&');
        }
        val contentType = rsp.getContentType();
        headerSb.append("Content-Type=").append(contentType);

        val baos = (ByteArrayOutputStream) req.getAttribute("_log_baos");
        val sw = (StringWriter) req.getAttribute("_log_sw");
        val body = parseBody(contentType, baos, sw);

        val hici = (String) req.getAttribute("_log_hici");
        val start = (Long) req.getAttribute("_log_start");
        val costMillis = System.currentTimeMillis() - start;

        log.info("spring rest server {} response cost {} millis, status code {}, headers: {}, body: {}",
                hici, costMillis, rsp.getStatus(), headerSb, body);
    }

    private String parseBody(String contentType, ByteArrayOutputStream baos, StringWriter sw) {
        String body = null;

        val lowerContentType = StringUtils.lowerCase(contentType);
        if (containsAnyOrNull(lowerContentType, "json", "xml", "text")) {
            val bytes = baos.toByteArray();
            if (bytes.length > 0) {
                body = new String(bytes, Charsets.UTF_8);
            } else {
                body = sw.toString();
            }
        }

        if (body == null || body.contains("<html>")) body = " ignored";
        return body;
    }

    private boolean containsAnyOrNull(String contentType, String... any) {
        if (contentType == null) return true;

        for (String item : any) {
            if (contentType.contains(item)) return true;
        }
        return false;
    }

    private void createOriginalStringForSign(Appendable proxy, HttpServletRequest request) {
        appendMethodAndUrl(request, proxy);
        appendHeaders(request, proxy);
        appendRequestParams(request, proxy);
    }

    private boolean ignoreSign(Class<?> beanType, HandlerMethod method) {
        val restfulSign1 = method.getMethod().getAnnotation(RestfulSign.class);
        if (restfulSign1 != null) return restfulSign1.ignore();

        val restfulSign2 = beanType.getAnnotation(RestfulSign.class);
        if (restfulSign2 != null) return restfulSign2.ignore();

        return true;
    }

    @SneakyThrows
    public static String md5(byte[] bytes) {
        val md = MessageDigest.getInstance("MD5");
        val digest = md.digest(bytes);
        return Base64.base64(digest, Base64.Format.Standard);
    }

    @SneakyThrows
    public static String hmacSHA256(String data, String key) {
        val secretKey = new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256");
        val mac = Mac.getInstance("HmacSHA256");
        mac.init(secretKey);
        val hmacData = mac.doFinal(data.getBytes("UTF-8"));
        return Base64.base64(hmacData, Base64.Format.Standard);
    }

    private void appendRequestParams(HttpServletRequest request, Appendable signStr) {
        Map<String, String[]> parameterMap = Maps.newTreeMap();
        parameterMap.putAll(request.getParameterMap());

        val json = getJson(request);
        if (!Strings.isNullOrEmpty(json)) {
            parameterMap.put("_json", new String[]{json});
        }
        fileUpload(request, parameterMap);

        val queryString = request.getQueryString();
        for (val entry : parameterMap.entrySet()) {
            val parameterName = entry.getKey();
            if (isQueryParameter(queryString, parameterName)) continue;


            signStr.append(parameterName).append('$');
            for (val value : entry.getValue()) {
                signStr.appendAbbreviate(value).append('$');
            }
        }
    }

    private void fileUpload(HttpServletRequest request, Map<String, String[]> parameterMap) {
        if (!(request instanceof MultipartHttpServletRequest)) return;

        val mreq = (MultipartHttpServletRequest) request;
        val multiFileMap = mreq.getMultiFileMap();

        for (val entry : multiFileMap.entrySet()) {
            val name = entry.getKey();

            val sb = new StringBuilder();
            val value = entry.getValue();
            for (val file : value) {
                sb.append(md5(getBytes(file))).append('$');
            }
            if (sb.length() > 0) sb.delete(sb.length() - 1, sb.length());

            parameterMap.put(name, new String[]{sb.toString()});
        }
    }

    private byte[] getBytes(MultipartFile value) {
        try {
            return value.getBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public String getJson(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return null;

        val contentType = request.getHeader("content-type");
        if (contentType == null) return null;
        if (contentType.indexOf("application/json") < 0) return null;

        try {
            val reader = request.getReader();
            return CharStreams.toString(reader);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isQueryParameter(String queryString, String parameterName) {
        if (Strings.isNullOrEmpty(queryString)) return false;

        int index = queryString.indexOf(parameterName);
        if (index < 0) return false;

        if (index > 0 && queryString.charAt(index - 1) != '&') return false;

        int offset = index + parameterName.length();
        if (offset >= queryString.length()) return true;

        return queryString.charAt(offset) == '=';
    }

    private static String[] filtered = new String[]{
            "hisv", "accept-encoding", "user-agent",
            "host", "connection",
            "content-length", "content-type"
    };

    private void appendHeaders(HttpServletRequest request, Appendable signStr) {
        val headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            val headerName = headerNames.nextElement();
            if (ArrayUtils.contains(filtered, headerName)) continue;

            val headers = request.getHeaders(headerName);
            signStr.append(headerName).append('$');

            joinEnumeration(signStr, headers);
        }
    }

    private void joinEnumeration(Appendable signStr, Enumeration<String> headers) {
        while (headers.hasMoreElements()) {
            signStr.append(headers.nextElement()).append('$');
        }
    }

    private void appendMethodAndUrl(HttpServletRequest req, Appendable signStr) {
        signStr.append(req.getMethod()).append('$');

        val fullUrl = new StringBuilder(req.getRequestURL());
        val queryString = req.getQueryString();
        if (!Strings.isNullOrEmpty(queryString)) {
            fullUrl.append('?').append(queryString);
        }

        signStr.append(fullUrl.toString()).append('$');
    }

}
