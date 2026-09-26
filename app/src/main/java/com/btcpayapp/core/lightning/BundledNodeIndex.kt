package com.btcpayapp.core.lightning

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

private const val ASSET = "lnnodes.bin"

/**
 * Opens the bundled directory, mapped rather than read.
 *
 * The asset is listed under `androidResources.noCompress` in the module's build
 * script, which is what makes `openFd` possible: an uncompressed entry inside
 * the APK can be handed to the kernel as an offset and a length, so the two
 * hundred kilobytes of table are paged in on demand and shared between
 * processes instead of being copied onto this one's heap.
 *
 * The fallback exists because that arrangement is a packaging detail, not a
 * guarantee. If the asset ever ends up compressed the file descriptor is
 * refused, and reading the bytes is still far better than having no names; if
 * the asset is missing or malformed the result is null and the app falls back
 * to curated names and shortened keys.
 */
fun openBundledNodeIndex(context: Context): NodeIndex? = runCatching {
    val assets = context.applicationContext.assets

    val mapped = runCatching {
        assets.openFd(ASSET).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
            }
        }
    }.getOrNull()

    val buffer = mapped ?: assets.open(ASSET).use { ByteBuffer.wrap(it.readBytes()) }
    NodeIndex.parse(buffer)
}.getOrNull()
