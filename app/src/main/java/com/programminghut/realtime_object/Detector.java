package com.programminghut.realtime_object;
import android.util.Log;  // Add this import
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;
import androidx.camera.core.ImageProxy;
import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;  // Add this

public class Detector {
    private static final String TAG = "Detector";
    private final Module vehicleModel;
    private final Module signModel;
    private static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.35f;  // Lowered threshold for testing

    // Normalization parameters
    private static final float[] MEAN = new float[]{0.485f, 0.456f, 0.406f};
    private static final float[] STD = new float[]{0.229f, 0.224f, 0.225f};

    // Class names
    private static final String[] VEHICLE_CLASSES = {"car", "motorcycle", "bus", "truck"};
    private static final String[] SIGN_CLASSES = {"crossing", "near_crossing", "crossing_ahead"};

    public Detector(Context context) throws IOException {
        Log.d(TAG, "Initializing detector...");

        String vehiclePath = assetFilePath(context, "vehicle_model.ptl");
        String signPath = assetFilePath(context, "sign_model.ptl");

        Log.d(TAG, "Loading vehicle model from: " + vehiclePath);
        Log.d(TAG, "Loading sign model from: " + signPath);

        try {
            vehicleModel = Module.load(vehiclePath);
            signModel = Module.load(signPath);
            Log.d(TAG, "Models loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error loading models", e);
            throw e;
        }
    }

    private String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (FileOutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        }
    }

    public List<Detection> processImage(ImageProxy image) {
        List<Detection> detections = new ArrayList<>();

        try {
            // Convert ImageProxy to Bitmap
            Bitmap bitmap = ImageUtils.imageProxyToBitmap(image);
            if (bitmap == null) {
                Log.e(TAG, "Failed to convert ImageProxy to Bitmap");
                return detections;
            }

            // Resize bitmap to model input size
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    INPUT_SIZE,
                    INPUT_SIZE,
                    true
            );

            // Convert bitmap to tensor with normalization
            Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                    resizedBitmap,
                    MEAN,
                    STD
            );

            // Process with both models
            try {
                detections.addAll(runModel(vehicleModel, inputTensor, true));
            } catch (Exception e) {
                Log.e(TAG, "Error running vehicle model", e);
            }

            try {
                detections.addAll(runModel(signModel, inputTensor, false));
            } catch (Exception e) {
                Log.e(TAG, "Error running sign model", e);
            }

            // Clean up
            bitmap.recycle();
            resizedBitmap.recycle();

        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
        }

        return detections;
    }

    private List<Detection> runModel(Module model, Tensor inputTensor, boolean isVehicle) {
        List<Detection> detections = new ArrayList<>();
        String modelType = isVehicle ? "Vehicle" : "Sign";

        try {
            // Run inference
            IValue output = model.forward(IValue.from(inputTensor));

            // Handle output
            Tensor outputTensor;
            if (output.isTensor()) {
                outputTensor = output.toTensor();
            } else if (output.isTuple()) {
                outputTensor = output.toTuple()[0].toTensor();
            } else {
                return detections;
            }

            float[] outputs = outputTensor.getDataAsFloatArray();
            int dimensions = isVehicle ? 85 : (5 + SIGN_CLASSES.length);
            int numDetections = outputs.length / dimensions;

            // Collect valid detections
            List<Detection> validDetections = new ArrayList<>();
            float CONF_THRESHOLD = 0.45f;  // Increased confidence threshold

            for (int i = 0; i < numDetections; i++) {
                int offset = i * dimensions;

                if (offset + 4 < outputs.length) {
                    float confidence = outputs[offset + 4];
                    confidence = Math.min(confidence, 1.0f); // Cap confidence at 100%

                    if (confidence > CONF_THRESHOLD) {
                        float x = outputs[offset];
                        float y = outputs[offset + 1];
                        float w = outputs[offset + 2];
                        float h = outputs[offset + 3];

                        // Get class id
                        int classId = 0;
                        float maxProb = 0;
                        for (int j = 5; j < Math.min(dimensions, outputs.length - offset); j++) {
                            if (outputs[offset + j] > maxProb) {
                                maxProb = outputs[offset + j];
                                classId = j - 5;
                            }
                        }

                        // Convert to relative coordinates (0-1)
                        RectF box = new RectF(
                                (x - w/2) / INPUT_SIZE,
                                (y - h/2) / INPUT_SIZE,
                                (x + w/2) / INPUT_SIZE,
                                (y + h/2) / INPUT_SIZE
                        );

                        String label = getClassName(classId, isVehicle);
                        validDetections.add(new Detection(box, label, confidence, isVehicle ? 0 : 1));
                    }
                }
            }

            // Apply Non-Maximum Suppression
            detections = nonMaxSuppression(validDetections, 0.5f); // IOU threshold of 0.5

        } catch (Exception e) {
            Log.e(TAG, "Error running " + modelType + " model", e);
        }

        return detections;
    }

    private List<Detection> nonMaxSuppression(List<Detection> detections, float iouThreshold) {
        List<Detection> result = new ArrayList<>();

        // Sort by confidence
        Collections.sort(detections, (a, b) -> Float.compare(b.confidence, a.confidence));

        boolean[] removed = new boolean[detections.size()];

        for (int i = 0; i < detections.size(); i++) {
            if (removed[i]) continue;

            result.add(detections.get(i));

            for (int j = i + 1; j < detections.size(); j++) {
                if (removed[j]) continue;

                float iou = calculateIoU(detections.get(i).box, detections.get(j).box);
                if (iou > iouThreshold) {
                    removed[j] = true;
                }
            }
        }

        return result;
    }

    private float calculateIoU(RectF box1, RectF box2) {
        float intersectionLeft = Math.max(box1.left, box2.left);
        float intersectionTop = Math.max(box1.top, box2.top);
        float intersectionRight = Math.min(box1.right, box2.right);
        float intersectionBottom = Math.min(box1.bottom, box2.bottom);

        if (intersectionRight < intersectionLeft || intersectionBottom < intersectionTop) {
            return 0;
        }

        float intersectionArea = (intersectionRight - intersectionLeft) *
                (intersectionBottom - intersectionTop);

        float box1Area = (box1.right - box1.left) * (box1.bottom - box1.top);
        float box2Area = (box2.right - box2.left) * (box2.bottom - box2.top);

        float unionArea = box1Area + box2Area - intersectionArea;

        return intersectionArea / unionArea;
    }

    private int getClassId(float[] outputs, int offset, int dimensions) {
        int classId = 0;
        float maxScore = 0;

        // Start after box coordinates and confidence score
        for (int j = 5; j < dimensions; j++) {
            if (outputs[offset + j] > maxScore) {
                maxScore = outputs[offset + j];
                classId = j - 5;
            }
        }

        return classId;
    }

    private String getClassName(int id, boolean isVehicle) {
        String[] classes = isVehicle ? VEHICLE_CLASSES : SIGN_CLASSES;
        return id < classes.length ? classes[id] : (isVehicle ? "vehicle" : "sign");
    }
}
