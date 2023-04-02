package com.programminghut.realtime_object;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import androidx.camera.core.ImageProxy;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ImageUtils {
    public static Bitmap imageProxyToBitmap(ImageProxy image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            return null;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        YuvImage yuvImage = imageProxyToYuvImage(image);
        if (yuvImage != null) {
            yuvImage.compressToJpeg(
                    new Rect(0, 0, image.getWidth(), image.getHeight()),
                    100,
                    out
            );

            byte[] imageBytes = out.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            if (bitmap != null && image.getImageInfo().getRotationDegrees() != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(image.getImageInfo().getRotationDegrees());
                bitmap = Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        matrix,
                        true
                );
            }
            return bitmap;
        }
        return null;
    }

    private static YuvImage imageProxyToYuvImage(ImageProxy image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            return null;
        }

        // Get image dimensions
        int width = image.getWidth();
        int height = image.getHeight();

        // Get image planes
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        // Get plane strides
        int yStride = planes[0].getRowStride();
        int uStride = planes[1].getRowStride();
        int vStride = planes[2].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();

        // Initialize data array
        byte[] nv21 = new byte[width * height * 3 / 2];

        // Copy Y plane
        int yBufferPosition = 0;
        int nv21Position = 0;

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (yBufferPosition < yBuffer.capacity()) {
                    nv21[nv21Position] = yBuffer.get(yBufferPosition);
                }
                nv21Position++;
                yBufferPosition += yPixelStride;
            }
            if (row < height - 1) {
                yBufferPosition += yStride - width * yPixelStride;
            }
        }

        // Copy U and V planes
        int uvBufferPosition = 0;
        int uvHeight = height / 2;
        int uvWidth = width / 2;

        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                // V plane
                if (uvBufferPosition < vBuffer.capacity()) {
                    nv21[nv21Position++] = vBuffer.get(uvBufferPosition);
                }
                uvBufferPosition += vPixelStride;

                // U plane
                if (uvBufferPosition < uBuffer.capacity()) {
                    nv21[nv21Position++] = uBuffer.get(uvBufferPosition - vPixelStride);
                }
                uvBufferPosition += uPixelStride;
            }
            if (row < uvHeight - 1) {
                uvBufferPosition += uStride - uvWidth * uPixelStride;
            }
        }

        return new YuvImage(nv21, ImageFormat.NV21, width, height, null);
    }
}