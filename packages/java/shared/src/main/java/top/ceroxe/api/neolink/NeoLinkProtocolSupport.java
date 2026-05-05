package top.ceroxe.api.neolink;

import top.ceroxe.api.neolink.exception.NoMoreNetworkFlowException;
import top.ceroxe.api.neolink.exception.NoMorePortException;
import top.ceroxe.api.neolink.exception.NoSuchKeyException;
import top.ceroxe.api.neolink.exception.OutDatedKeyException;
import top.ceroxe.api.neolink.exception.PortOccupiedException;
import top.ceroxe.api.neolink.exception.UnRecognizedKeyException;
import top.ceroxe.api.neolink.exception.UnSupportHostVersionException;

import java.io.IOException;

final class NeoLinkProtocolSupport {
    private static final String NO_UPDATE_URL_RESPONSE = "false";
    private static final String EN_TUN_ADDR_PREFIX = "Use the address: ";
    private static final String EN_TUN_ADDR_SUFFIX = " to start up connections.";
    private static final String ZH_TUN_ADDR_PREFIX = "使用链接地址： ";
    private static final String ZH_TUN_ADDR_SUFFIX = " 来从公网连接。";

    private NeoLinkProtocolSupport() {
    }

    static Exception classifyRuntimeTerminalFailure(String response) {
        Exception startupFailure = classifyStartupHandshakeFailure(response);
        if (startupFailure != null) {
            return startupFailure;
        }
        if (equalsProtocolText(response, "exitNoFlow")) {
            return new NoMoreNetworkFlowException(response);
        }
        return null;
    }

    static String normalizeUpdateURL(String response) {
        if (response == null) {
            return null;
        }

        String normalized = response.trim();
        if (normalized.isEmpty() || NO_UPDATE_URL_RESPONSE.equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    static Exception classifyStartupHandshakeFailure(String response) {
        if (response == null || response.isBlank()) {
            return new IOException("NeoProxyServer returned an empty startup response.");
        }
        if (isUnsupportedVersionResponse(response)) {
            return new UnSupportHostVersionException(response);
        }
        if (isNoMoreNetworkFlowResponse(response)) {
            return new NoMoreNetworkFlowException(response);
        }
        if (isOutdatedKeyResponse(response)) {
            return new OutDatedKeyException(response);
        }
        if (isUnrecognizedKeyResponse(response)) {
            return new UnRecognizedKeyException(response);
        }
        if (isRemotePortOccupiedResponse(response)) {
            return new PortOccupiedException(response);
        }
        if (isNoMorePortResponse(response)) {
            return new NoMorePortException(response);
        }
        return null;
    }

    static boolean isSuccessfulHandshakeResponse(String response) {
        for (LanguageData languageData : LanguageData.all()) {
            if (equalsProtocolText(response, languageData.connectionBuildUpSuccessfully)) {
                return true;
            }
        }
        return false;
    }

    static boolean isNoMorePortResponse(String response) {
        for (LanguageData languageData : LanguageData.all()) {
            if (equalsProtocolText(response, languageData.portAlreadyInUse)) {
                return true;
            }
        }
        return false;
    }

    static String parseTunAddrMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String fromEnglishMessage = extractBetween(message, EN_TUN_ADDR_PREFIX, EN_TUN_ADDR_SUFFIX);
        if (fromEnglishMessage != null) {
            return fromEnglishMessage;
        }
        return extractBetween(message, ZH_TUN_ADDR_PREFIX, ZH_TUN_ADDR_SUFFIX);
    }

    private static boolean isUnsupportedVersionResponse(String response) {
        for (LanguageData languageData : LanguageData.all()) {
            if (startsWithProtocolText(response, languageData.unsupportedVersionPrefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNoMoreNetworkFlowResponse(String response) {
        if (equalsProtocolText(response, "exitNoFlow")) {
            return true;
        }
        for (LanguageData languageData : LanguageData.all()) {
            if (equalsProtocolText(response, languageData.noNetworkFlowLeft)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOutdatedKeyResponse(String response) {
        for (LanguageData languageData : LanguageData.all()) {
            if (isWrappedProtocolText(response, languageData.keyPrefix, languageData.keyOutdatedSuffix)
                    || isWrappedProtocolText(response, languageData.keyAltPrefix, languageData.keyOutdatedSuffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnrecognizedKeyResponse(String response) {
        for (LanguageData languageData : LanguageData.all()) {
            if (equalsProtocolText(response, languageData.accessDeniedForceExiting)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRemotePortOccupiedResponse(String response) {
        for (LanguageData languageData : LanguageData.all()) {
            if (equalsProtocolText(response, languageData.remotePortOccupied)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsProtocolText(String response, String expected) {
        return normalizeProtocolText(response).equals(normalizeProtocolText(expected));
    }

    private static boolean startsWithProtocolText(String response, String expectedPrefix) {
        return normalizeProtocolText(response).startsWith(normalizeProtocolText(expectedPrefix));
    }

    private static boolean isWrappedProtocolText(String response, String prefix, String suffix) {
        String normalizedResponse = normalizeProtocolText(response);
        return normalizedResponse.startsWith(normalizeProtocolText(prefix))
                && normalizedResponse.endsWith(normalizeProtocolText(suffix));
    }

    private static String normalizeProtocolText(String response) {
        return response == null ? "" : response.trim();
    }

    private static String extractBetween(String value, String prefix, String suffix) {
        int start = value.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        int addressStart = start + prefix.length();
        int end = value.indexOf(suffix, addressStart);
        if (end < 0) {
            return null;
        }
        String address = value.substring(addressStart, end).trim();
        return address.isEmpty() ? null : address;
    }
}
