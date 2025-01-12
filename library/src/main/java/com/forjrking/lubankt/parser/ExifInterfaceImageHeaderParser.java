package com.forjrking.lubankt.parser;


import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import com.bumptech.glide.load.ImageHeaderParser;
import com.bumptech.glide.load.resource.bitmap.DefaultImageHeaderParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;


/**
 * Uses {@link ExifInterface} to parse orientation data.
 *
 * <p>ExifInterface supports the HEIF format on OMR1+. Glide's {@link DefaultImageHeaderParser}
 * doesn't currently support HEIF. In the future we should reconcile these two classes, but for now
 * this is a simple way to ensure that HEIF files are oriented correctly on platforms where they're
 * supported.
 */
public final class ExifInterfaceImageHeaderParser extends DefaultImgHeaderParser {

    @Override
    public int getOrientation(InputStream is) throws IOException {
        ImageType type = getType(is);
        if (type == ImageType.PNG_A || type == ImageType.PNG || type == ImageType.GIF) {
            //对于一些明显不支持的直接return
            return ImageHeaderParser.UNKNOWN_ORIENTATION;
        }
        ExifInterface exifInterface = new ExifInterface(is);
        int result = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        if (result == ExifInterface.ORIENTATION_UNDEFINED) {
            return ImageHeaderParser.UNKNOWN_ORIENTATION;
        }
        return result;
    }

    @Override
    public boolean copyExif(Object input, @NonNull File outputFile) throws IOException {
        return false;
    }
}
