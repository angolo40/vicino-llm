package com.sectl.litertlm.server

import android.app.Application

/**
 * Must be declared in AndroidManifest as android:name.
 *
 * Its job is to set JVM system properties BEFORE any Netty class is loaded.
 * Netty inspects these once via static initializers, and if they change later
 * Netty won't re-read them — hence setting them here, at the very first entry
 * point of the process.
 *
 * Why these specific properties:
 *   - preferIPv4Stack=true: Android's JDK socket layer mishandles
 *     NioServerSocketChannel when IPv6 is attempted first; you get
 *     `sun.nio.ch.Net.socket0 → ConnectException: Connection refused`.
 *   - io.netty.transport.noNative=true: tells Netty not to try loading the
 *     Linux epoll/kqueue natives which aren't shipped in the Android artifact
 *     anyway — skips a noisy classloader probe on startup.
 */
class GemmaServerApp : Application() {

    /** Process-wide downloader. Shared by UI + HTTP handlers. */
    lateinit var modelDownloader: ModelDownloader
        private set

    override fun onCreate() {
        super.onCreate()
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
        System.setProperty("io.netty.transport.noNative", "true")
        modelDownloader = ModelDownloader(applicationContext)
    }
}
