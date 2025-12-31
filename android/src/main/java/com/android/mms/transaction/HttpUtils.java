/*
 * Copyright 2014 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.transaction;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Locale;

import android.Manifest;
import android.content.Context;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.android.mms.logs.LogTag;
import com.android.mms.MmsConfig;

public class HttpUtils {
    private static final String TAG = LogTag.TRANSACTION;

    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = DEBUG ? Config.LOGD : Config.LOGV;

    public static final int HTTP_POST_METHOD = 1;
    public static final int HTTP_GET_METHOD = 2;

    private static final int MMS_READ_BUFFER = 4096;

    // This is the value to use for the "Accept-Language" header.
    // Once it becomes possible for the user to change the locale
    // setting, this should no longer be static. We should call
    // getHttpAcceptLanguage instead.
    private static final String HDR_VALUE_ACCEPT_LANGUAGE;

    static {
        HDR_VALUE_ACCEPT_LANGUAGE = getCurrentAcceptLanguage(Locale.getDefault());
    }

    // Definition for necessary HTTP headers.
    private static final String HDR_KEY_ACCEPT = "Accept";
    private static final String HDR_KEY_ACCEPT_LANGUAGE = "Accept-Language";

    private static final String HDR_VALUE_ACCEPT = "*/*, application/vnd.wap.mms-message, application/vnd.wap.sic";

    private HttpUtils() {
        // To forbidden instantiate this class.
    }

    /**
     * A helper method to send or retrieve data through HTTP protocol.
     *
     * @param token  The token to identify the sending progress.
     * @param url    The URL used in a GET request. Null when the method is
     *               HTTP_POST_METHOD.
     * @param pdu    The data to be POST. Null when the method is HTTP_GET_METHOD.
     * @param method HTTP_POST_METHOD or HTTP_GET_METHOD.
     * @return A byte array which contains the response data.
     *         If an HTTP error code is returned, an IOException will be thrown.
     * @throws IOException if any error occurred on network interface or
     *                     an HTTP error code(&gt;=400) returned from the server.
     */
    @RequiresPermission(allOf = { Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_PHONE_STATE })
    public static byte[] httpConnection(Context context, long token,
            String url, byte[] pdu, int method, boolean isProxySet,
            String proxyHost, int proxyPort) throws IOException {
        if (url == null) {
            throw new IllegalArgumentException("URL must not be null.");
        }

        Log.v(TAG, "httpConnection: params list");
        Log.v(TAG, "\ttoken\t\t= " + token);
        Log.v(TAG, "\turl\t\t= " + url);
        Log.v(TAG, "\tmethod\t\t= "
                + ((method == HTTP_POST_METHOD) ? "POST"
                        : ((method == HTTP_GET_METHOD) ? "GET" : "UNKNOWN")));
        Log.v(TAG, "\tisProxySet\t= " + isProxySet);
        Log.v(TAG, "\tproxyHost\t= " + proxyHost);
        Log.v(TAG, "\tproxyPort\t= " + proxyPort);
        // TODO Print out binary data more readable.
        // Log.v(TAG, "\tpdu\t\t= " + Arrays.toString(pdu));

        HttpURLConnection connection = null;
        byte[] body = null;
        try {
            java.net.URL urlObj = new java.net.URL(url);
            if (isProxySet && proxyHost != null) {
                java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new java.net.InetSocketAddress(proxyHost, proxyPort));
                connection = (HttpURLConnection) urlObj.openConnection(proxy);
            } else {
                connection = (HttpURLConnection) urlObj.openConnection();
            }

            // Set timeouts
            int soTimeout = MmsConfig.getHttpSocketTimeout();
            connection.setConnectTimeout(soTimeout);
            connection.setReadTimeout(soTimeout);

            // Set method
            switch (method) {
                case HTTP_POST_METHOD:
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/vnd.wap.mms-message");
                    if (pdu != null) {
                        connection.setFixedLengthStreamingMode(pdu.length);
                        java.io.OutputStream os = connection.getOutputStream();
                        os.write(pdu);
                        os.flush();
                        os.close();
                    }
                    break;
                case HTTP_GET_METHOD:
                    connection.setRequestMethod("GET");
                    break;
                default:
                    Log.e(TAG, "Unknown HTTP method: " + method
                            + ". Must be one of POST[" + HTTP_POST_METHOD
                            + "] or GET[" + HTTP_GET_METHOD + "].");
                    return null;
            }

            // Set headers
            connection.setRequestProperty(HDR_KEY_ACCEPT, HDR_VALUE_ACCEPT);
            String xWapProfileTagName = MmsConfig.getUaProfTagName();
            String xWapProfileUrl = MmsConfig.getUaProfUrl();
            if (xWapProfileUrl != null) {
                Log.d(LogTag.TRANSACTION,
                        "[HttpUtils] httpConn: xWapProfUrl=" + xWapProfileUrl);
                connection.setRequestProperty(xWapProfileTagName, xWapProfileUrl);
            }

            String extraHttpParams = MmsConfig.getHttpParams();
            if (extraHttpParams != null) {
                String line1Number = ((TelephonyManager) context
                        .getSystemService(Context.TELEPHONY_SERVICE))
                        .getLine1Number();
                String line1Key = MmsConfig.getHttpParamsLine1Key();
                String paramList[] = extraHttpParams.split("\\|");

                for (String paramPair : paramList) {
                    String splitPair[] = paramPair.split(":", 2);

                    if (splitPair.length == 2) {
                        String name = splitPair[0].trim();
                        String value = splitPair[1].trim();

                        if (line1Key != null) {
                            value = value.replace(line1Key, line1Number);
                        }
                        if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(value)) {
                            connection.setRequestProperty(name, value);
                        }
                    }
                }
            }
            connection.setRequestProperty(HDR_KEY_ACCEPT_LANGUAGE, HDR_VALUE_ACCEPT_LANGUAGE);

            // Connect and get response
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error: " + connection.getResponseMessage());
            }

            int contentLength = connection.getContentLength();
            try (java.io.InputStream is = connection.getInputStream()) {
                if (contentLength > 0) {
                    body = new byte[contentLength];
                    int offset = 0;
                    int bytesRead;
                    while (offset < contentLength
                            && (bytesRead = is.read(body, offset, contentLength - offset)) != -1) {
                        offset += bytesRead;
                    }
                } else {
                    // Unknown length, read until EOF
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[MMS_READ_BUFFER];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    body = baos.toByteArray();
                }
            }
            return body;
        } catch (Exception e) {
            handleHttpConnectionException(e, url);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    private static void handleHttpConnectionException(Exception exception, String url)
            throws IOException {
        // Inner exception should be logged to make life easier.
        Log.e(TAG, "Url: " + url + "\n" + exception.getMessage());
        IOException e = new IOException(exception.getMessage());
        e.initCause(exception);
        throw e;
    }

    private static final String ACCEPT_LANG_FOR_US_LOCALE = "en-US";

    /**
     * Return the Accept-Language header. Use the current locale plus
     * US if we are in a different locale than US.
     * This code copied from the browser's WebSettings.java
     *
     * @return Current AcceptLanguage String.
     */
    public static String getCurrentAcceptLanguage(Locale locale) {
        StringBuilder buffer = new StringBuilder();
        addLocaleToHttpAcceptLanguage(buffer, locale);

        if (!Locale.US.equals(locale)) {
            if (buffer.length() > 0) {
                buffer.append(", ");
            }
            buffer.append(ACCEPT_LANG_FOR_US_LOCALE);
        }

        return buffer.toString();
    }

    /**
     * Convert obsolete language codes, including Hebrew/Indonesian/Yiddish,
     * to new standard.
     */
    private static String convertObsoleteLanguageCodeToNew(String langCode) {
        if (langCode == null) {
            return null;
        }
        if ("iw".equals(langCode)) {
            // Hebrew
            return "he";
        } else if ("in".equals(langCode)) {
            // Indonesian
            return "id";
        } else if ("ji".equals(langCode)) {
            // Yiddish
            return "yi";
        }
        return langCode;
    }

    private static void addLocaleToHttpAcceptLanguage(StringBuilder builder,
            Locale locale) {
        String language = convertObsoleteLanguageCodeToNew(locale.getLanguage());
        if (language != null) {
            builder.append(language);
            String country = locale.getCountry();
            if (country != null) {
                builder.append("-");
                builder.append(country);
            }
        }
    }
}
