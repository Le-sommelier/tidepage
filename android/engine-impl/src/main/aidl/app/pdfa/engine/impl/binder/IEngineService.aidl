package app.pdfa.engine.impl.binder;

import android.os.ParcelFileDescriptor;
import app.pdfa.engine.impl.binder.IEngineCallback;

/**
 * Client -> server. Both methods are oneway. `frame` is Request.encode(); if it is larger than
 * BlobPipes.INLINE_LIMIT the inline array is empty and the whole frame is in `frameBlob` (read to
 * EOF, capped). The server must answer every call exactly once through `callback.onResponse`.
 */
oneway interface IEngineService {
    void call(in byte[] frame, in ParcelFileDescriptor frameBlob, IEngineCallback callback);
    void cancel(long requestId);
}
