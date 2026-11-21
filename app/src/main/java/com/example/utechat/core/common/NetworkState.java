package com.example.utechat.core.common;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

public final class NetworkState {
    private NetworkState() { }

    public static boolean connected(Context context) {
        var manager = context.getSystemService(ConnectivityManager.class);
        var network = manager == null ? null : manager.getActiveNetwork();
        var capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
