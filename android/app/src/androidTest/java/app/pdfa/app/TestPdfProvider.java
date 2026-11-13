package app.pdfa.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Provider of the test APK: {@code content://app.pdfa.test.pdfs/<name>} (or {@code .../document/<name>},
 * the shape of a document URI, which the app needs to tell two documents apart when it saves) serves
 * the asset {@code <name>} written by tools/gen_test_pdfs.py and reports its display name and exact
 * size. Names starting with {@code out} are writable save targets. Image fixtures ({@code .png} from
 * tools/gen_test_images.py; {@code .jpg} and {@code .webp} are written by the tests into this package's
 * cache directory) are served with their image MIME type.
 *
 * Java on purpose: the system starts this provider in the test package's OWN process, where only
 * the test APK is loaded. The Kotlin runtime lives in the app APK, so Kotlin code crashes here.
 */
public final class TestPdfProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    private synchronized File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[a-z0-9]+\\.(pdf|png|jpg|webp)")) throw new FileNotFoundException("bad name");
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("no context");
        File target = new File(ctx.getCacheDir(), name);
        if (!target.exists()) {
            File part = new File(ctx.getCacheDir(), name + ".part");
            try (InputStream in = ctx.getAssets().open(name); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            } catch (IOException e) {
                // Not a fixture. Names starting with "out" are save targets: like the system's
                // create-document picker, they exist as an empty file before anything is written.
                if (!name.startsWith("out")) throw new FileNotFoundException("no such fixture");
                try {
                    new FileOutputStream(part).close();
                } catch (IOException e2) {
                    throw new FileNotFoundException("target not writable");
                }
            }
            if (!part.renameTo(target)) throw new FileNotFoundException("fixture not writable");
        }
        return target;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = fileFor(uri);
        if ("r".equals(mode)) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        // Only save targets are writable; the fixtures stay as generated.
        if (!file.getName().startsWith("out")) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File file = fileFor(uri);
            cursor.addRow(new Object[] {file.getName(), file.length()});
        } catch (FileNotFoundException e) {
            // An empty cursor: the caller sees "no such document".
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null && name.endsWith(".png")) return "image/png";
        if (name != null && name.endsWith(".jpg")) return "image/jpeg";
        if (name != null && name.endsWith(".webp")) return "image/webp";
        return "application/pdf";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
