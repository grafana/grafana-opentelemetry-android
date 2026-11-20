package com.grafana.sessiondraftprobe;

final class NativeCrash {
    static {
        System.loadLibrary("session_probe_crash");
    }

    private NativeCrash() {}

    static native void abortNow();
}
