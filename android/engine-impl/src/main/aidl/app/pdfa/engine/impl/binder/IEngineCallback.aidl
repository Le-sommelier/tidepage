package app.pdfa.engine.impl.binder;

import android.os.ParcelFileDescriptor;

/**
 * Server -> client. Every method is oneway. A frame is the bytes of Response.encode() or
 * EngineWire.progress(). If the frame is larger than BlobPipes.INLINE_LIMIT the inline array is
 * empty and the whole frame is in `frameBlob` (read to EOF). `tile` carries the pixels of a
 * RENDER_TILE reply: exactly TileInfo.size bytes, then EOF; null for every other reply.
 */
oneway interface IEngineCallback {
    void onResponse(in byte[] frame, in ParcelFileDescriptor frameBlob, in ParcelFileDescriptor tile);
    void onProgress(in byte[] frame);
}
