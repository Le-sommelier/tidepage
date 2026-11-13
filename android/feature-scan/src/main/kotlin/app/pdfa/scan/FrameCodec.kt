package app.pdfa.scan

import app.pdfa.engine.ImageFrame

/**
 * Moves pictures between full-resolution RGBA frames and compressed JPEG bytes. The controller
 * holds pages only as JPEG; a decoded frame exists only inside [withDecoded], so at most one is
 * alive at a time. Android implementation: [AndroidFrameCodec].
 */
interface FrameCodec {
    /** JPEG bytes of [frame], quality 1..100. Never keeps [frame]. */
    suspend fun encode(frame: ImageFrame, quality: Int): ByteArray

    /**
     * Decodes [jpeg] (downscaled to at most [maxPixels] if larger), runs [block], and releases the
     * frame when [block] returns or throws. [block] must not keep the frame.
     */
    suspend fun <T> withDecoded(jpeg: ByteArray, maxPixels: Long, block: suspend (ImageFrame) -> T): T

    /** A smaller copy of [frame]; the caller drops [frame] afterwards. */
    suspend fun downscale(frame: ImageFrame, to: PixelSize): ImageFrame
}
