package ca.larose.fieldcompanion;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class LocalNaturalistAi {
    public static final class Candidate {
        public final String commonName;
        public final String scientificName;
        public final String group;
        public final float confidence;

        Candidate(String commonName, String scientificName, String group, float confidence) {
            this.commonName = commonName;
            this.scientificName = scientificName;
            this.group = group;
            this.confidence = confidence;
        }

        public String display() {
            String pct = String.format(java.util.Locale.CANADA_FRENCH, "%.0f %%", confidence * 100f);
            String common = (commonName == null || commonName.trim().isEmpty()) ? scientificName : commonName;
            return common + " — " + scientificName + "  [" + group + " • " + pct + "]";
        }
    }

    public static final class Result {
        public final List<Candidate> candidates;
        public final String reliability;
        public final long elapsedMs;

        Result(List<Candidate> candidates, String reliability, long elapsedMs) {
            this.candidates = candidates;
            this.reliability = reliability;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final class ModelSpec {
        final String modelAsset;
        final String labelsAsset;
        final String group;

        ModelSpec(String modelAsset, String labelsAsset, String group) {
            this.modelAsset = modelAsset;
            this.labelsAsset = labelsAsset;
            this.group = group;
        }
    }

    private final Context context;
    private final ModelSpec[] specs = new ModelSpec[] {
            new ModelSpec("ai/inat_bird.tflite", "ai/inat_bird_labels.txt", "Oiseau"),
            new ModelSpec("ai/inat_plant.tflite", "ai/inat_plant_labels.txt", "Plante"),
            new ModelSpec("ai/inat_insect.tflite", "ai/inat_insect_labels.txt", "Insecte")
    };

    public LocalNaturalistAi(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result classify(Bitmap source) throws Exception {
        long started = System.currentTimeMillis();
        List<Candidate> all = new ArrayList<>();

        for (ModelSpec spec : specs) {
            all.addAll(runModel(source, spec, 3));
        }

        Collections.sort(all, (a, b) -> Float.compare(b.confidence, a.confidence));
        if (all.size() > 6) all = new ArrayList<>(all.subList(0, 6));

        String reliability = "INSUFFISANTE";
        if (!all.isEmpty()) {
            float first = all.get(0).confidence;
            float second = all.size() > 1 ? all.get(1).confidence : 0f;
            if (first >= 0.60f && (first - second) >= 0.10f) reliability = "ÉLEVÉE";
            else if (first >= 0.35f) reliability = "MODÉRÉE";
        }

        return new Result(all, reliability, System.currentTimeMillis() - started);
    }

    private List<Candidate> runModel(Bitmap source, ModelSpec spec, int topK) throws Exception {
        ByteBuffer model = loadModel(spec.modelAsset);
        List<String> labels = loadLabels(spec.labelsAsset);

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));

        try (Interpreter interpreter = new Interpreter(model, options)) {
            Tensor inputTensor = interpreter.getInputTensor(0);
            Tensor outputTensor = interpreter.getOutputTensor(0);

            int[] inShape = inputTensor.shape();
            int height = inShape.length >= 3 ? inShape[1] : 224;
            int width = inShape.length >= 3 ? inShape[2] : 224;
            Bitmap bitmap = Bitmap.createScaledBitmap(source, width, height, true);

            ByteBuffer input = prepareInput(bitmap, inputTensor.dataType());
            int[] outShape = outputTensor.shape();
            int classes = outShape[outShape.length - 1];

            List<Float> scores = new ArrayList<>(classes);
            if (outputTensor.dataType() == DataType.UINT8) {
                ByteBuffer output = ByteBuffer.allocateDirect(classes).order(ByteOrder.nativeOrder());
                interpreter.run(input, output);
                output.rewind();
                Tensor.QuantizationParams qp = outputTensor.quantizationParams();
                float scale = qp.getScale();
                int zero = qp.getZeroPoint();
                for (int i = 0; i < classes; i++) {
                    int raw = output.get() & 0xFF;
                    scores.add(Math.max(0f, (raw - zero) * scale));
                }
            } else if (outputTensor.dataType() == DataType.FLOAT32) {
                ByteBuffer output = ByteBuffer.allocateDirect(classes * 4).order(ByteOrder.nativeOrder());
                interpreter.run(input, output);
                output.rewind();
                for (int i = 0; i < classes; i++) scores.add(output.getFloat());
            } else {
                throw new IllegalStateException("Type de sortie IA non pris en charge: " + outputTensor.dataType());
            }

            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < Math.min(classes, labels.size()); i++) indices.add(i);
            indices.sort((a, b) -> Float.compare(scores.get(b), scores.get(a)));

            List<Candidate> results = new ArrayList<>();
            for (int rank = 0; rank < Math.min(topK, indices.size()); rank++) {
                int idx = indices.get(rank);
                ParsedLabel parsed = parseLabel(labels.get(idx));
                results.add(new Candidate(parsed.common, parsed.scientific, spec.group, scores.get(idx)));
            }
            return results;
        }
    }

    private ByteBuffer prepareInput(Bitmap bitmap, DataType type) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        if (type == DataType.UINT8) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 3).order(ByteOrder.nativeOrder());
            for (int p : pixels) {
                buffer.put((byte) ((p >> 16) & 0xFF));
                buffer.put((byte) ((p >> 8) & 0xFF));
                buffer.put((byte) (p & 0xFF));
            }
            buffer.rewind();
            return buffer;
        }

        if (type == DataType.FLOAT32) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 3 * 4).order(ByteOrder.nativeOrder());
            for (int p : pixels) {
                buffer.putFloat(((p >> 16) & 0xFF) / 255f);
                buffer.putFloat(((p >> 8) & 0xFF) / 255f);
                buffer.putFloat((p & 0xFF) / 255f);
            }
            buffer.rewind();
            return buffer;
        }

        throw new IllegalStateException("Type d'entrée IA non pris en charge: " + type);
    }

    private ByteBuffer loadModel(String asset) throws Exception {
        try (InputStream in = context.getAssets().open(asset);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            byte[] bytes = out.toByteArray();
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            buffer.put(bytes);
            buffer.rewind();
            return buffer;
        }
    }

    private List<String> loadLabels(String asset) throws Exception {
        List<String> labels = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(asset)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) labels.add(line);
            }
        }
        return labels;
    }

    private static final class ParsedLabel {
        final String scientific;
        final String common;
        ParsedLabel(String scientific, String common) {
            this.scientific = scientific;
            this.common = common;
        }
    }

    private ParsedLabel parseLabel(String raw) {
        int open = raw.lastIndexOf(" (");
        if (open > 0 && raw.endsWith(")")) {
            return new ParsedLabel(raw.substring(0, open).trim(), raw.substring(open + 2, raw.length() - 1).trim());
        }
        return new ParsedLabel(raw.trim(), "");
    }
}
