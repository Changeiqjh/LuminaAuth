package com.luminaauth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class NetworkUtils {
    public static String get(String urlString) throws Exception {
        return get(urlString, null);
    }

    public static String get(String urlString, String referer) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/153.0.0.0 Mobile Safari/537.36");
        if (referer != null) {
            conn.setRequestProperty("Referer", referer);
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP error: " + code);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}
