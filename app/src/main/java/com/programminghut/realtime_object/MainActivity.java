package com.programminghut.realtime_object;
import java.io.IOException;  // Add this import
import android.util.Log;  // Add this import
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.TextureView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    private PreviewView viewFinder;
    private TextureView overlayView;
    private Detector detector;
    private ExecutorService cameraExecutor;
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private Paint boxPaint;
    private Paint textPaint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d("ModelDebug", "Starting application...");

        try {
            String[] assets = getAssets().list("");
            Log.d("ModelDebug", "Available assets in app:");
            for (String asset : assets) {
                Log.d("ModelDebug", "Asset: " + asset);
            }
        } catch (IOException e) {
            Log.e("ModelDebug", "Error listing assets", e);
        }

        viewFinder = findViewById(R.id.viewFinder);
        overlayView = findViewById(R.id.overlayView);
        overlayView.setSurfaceTextureListener(this);

        setupPaints();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            detector = new Detector(this);
        } catch (Exception e) {
            Toast.makeText(this, "Error loading models: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private void setupPaints() {
        boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4);
        boxPaint.setColor(Color.RED);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setShadowLayer(1f, 0f, 0f, Color.BLACK);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    try {
                        if (detector != null) {
                            List<Detection> detections = detector.processImage(image);
                            drawDetections(detections);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        image.close();
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Toast.makeText(this, "Error starting camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void drawDetections(List<Detection> detections) {
        if (overlayView.isAvailable()) {
            Canvas canvas = overlayView.lockCanvas();
            if (canvas != null) {
                try {
                    // Clear previous drawings
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

                    for (Detection detection : detections) {
                        // Only draw high confidence detections
                        if (detection.confidence < 0.45f) continue;

                        // Set colors based on type
                        int boxColor;
                        if (detection.type == 0) { // Vehicle
                            boxColor = Color.GREEN;
                        } else { // Sign
                            boxColor = Color.BLUE;
                        }

                        // Prepare box paint
                        boxPaint.setColor(boxColor);
                        boxPaint.setStyle(Paint.Style.STROKE);
                        boxPaint.setStrokeWidth(4);

                        // Convert normalized coordinates to screen coordinates
                        float left = detection.box.left * canvas.getWidth();
                        float top = detection.box.top * canvas.getHeight();
                        float right = detection.box.right * canvas.getWidth();
                        float bottom = detection.box.bottom * canvas.getHeight();

                        // Draw box
                        canvas.drawRect(left, top, right, bottom, boxPaint);

                        // Prepare label
                        String label = String.format("%s %.0f%%",
                                detection.label,
                                detection.confidence * 100);

                        // Draw label background
                        textPaint.setTextSize(36);
                        float textWidth = textPaint.measureText(label);
                        float textHeight = 50;

                        Paint bgPaint = new Paint();
                        bgPaint.setColor(boxColor);
                        bgPaint.setAlpha(160);
                        canvas.drawRect(
                                left,
                                top - textHeight,
                                left + textWidth + 20,
                                top,
                                bgPaint
                        );

                        // Draw label text
                        textPaint.setColor(Color.WHITE);
                        canvas.drawText(
                                label,
                                left + 10,
                                top - 10,
                                textPaint
                        );
                    }
                } finally {
                    overlayView.unlockCanvasAndPost(canvas);
                }
            }
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {}

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}