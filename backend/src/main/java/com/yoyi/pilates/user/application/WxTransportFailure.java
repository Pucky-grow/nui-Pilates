package com.yoyi.pilates.user.application;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.IdentityHashMap;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Classifies transport failures without retaining URLs, response bodies or messages. */
final class WxTransportFailure {
    private WxTransportFailure() {}

    record Summary(String category, String rootType) {}

    static Summary describe(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable root = failure;
        String category = "IO";
        for (int i = 0; root != null && i < 24 && seen.add(root); i++) {
            if (root instanceof UnknownHostException) category = "DNS";
            else if (root instanceof CertPathBuilderException || root instanceof CertPathValidatorException
                    || root instanceof CertificateException) category = "TLS_CERTIFICATE";
            else if (root instanceof SSLPeerUnverifiedException) category = "TLS_PEER";
            else if (root instanceof SSLHandshakeException) category = "TLS_HANDSHAKE";
            else if (root instanceof SSLException) category = "TLS";
            else if (root instanceof SocketTimeoutException) category = "TIMEOUT";
            else if (root instanceof NoRouteToHostException) category = "NO_ROUTE";
            else if (root instanceof ConnectException) category = "CONNECT";
            if (root.getCause() == null || seen.contains(root.getCause())) break;
            root = root.getCause();
        }
        return new Summary(category, root == null ? "Unknown" : root.getClass().getSimpleName());
    }
}
