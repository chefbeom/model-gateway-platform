package com.aiconnect.llmgateway.runtime;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Locale;

public class RuntimeUnavailableException extends RuntimeException {
    private final boolean safeToRetry;

    public RuntimeUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.safeToRetry = failedBeforeConnection(cause);
    }

    public boolean isSafeToRetry() {
        return safeToRetry;
    }

    private static boolean failedBeforeConnection(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof HttpConnectTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException
                    || current instanceof PortUnreachableException
                    || isConnectTimeout(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isConnectTimeout(Throwable failure) {
        if (!(failure instanceof SocketTimeoutException) || failure.getMessage() == null) return false;
        String message = failure.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("connect timed out") || message.contains("connect timeout");
    }
}
