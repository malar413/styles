package ca.larose.fieldcompanion;

import android.content.Context;
import android.graphics.Bitmap;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HousePlantExpert {
    private static final int SIZE = 224;
    private final Context context;

    static final class HouseCandidate {
        final String common;
        final String scientific;
        final float probability;

        HouseCandidate(String common, String scientific, float probability) {
            this.common = common;
            this.scientific = scientific;
            this.probability = probability;
        }
    }

    HousePlantExpert(Context context) {
        this.context = context.getApplicationContext();
    }

    List<HouseCandidate> classify(Bitmap source, int topK) throws Exception {
        byte[] model = readAll("ai/houseplant_q4.onnx");
        List<String> labels = readLabels("ai/houseplant_labels.txt");

        float[] inputData = prepareImage(source);
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));

        float[] logits;
        try (OrtSession session = env.createSession(model, options)) {
            String inputName = session.getInputNames().iterator().next();
            try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData),
                    new long[]{1, 3, SIZE, SIZE});
                 OrtSession.Result result = session.run(Map.of(inputName, input))) {
                Object value = result.get(0).getValue();
                if (!(value instanceof float[][])) throw new IllegalStateException("Sortie expert horticole inattendue");
                logits = ((float[][]) value)[0];
            }
        }

        if (logits.length != labels.size()) throw new IllegalStateException("Étiquettes horticoles incohérentes");
        float[] probs = softmax(logits);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < probs.length; i++) idx.add(i);
        idx.sort((a,b) -> Float.compare(probs[b], probs[a]));

        List<HouseCandidate> out = new ArrayList<>();
        for (int rank = 0; rank < Math.min(topK, idx.size()); rank++) {
            int i = idx.get(rank);
            Parsed p = parse(labels.get(i));
            out.add(new HouseCandidate(p.common, p.scientific, probs[i]));
        }
        return out;
    }

    private float[] prepareImage(Bitmap source) {
        Bitmap bitmap = Bitmap.createScaledBitmap(source, SIZE, SIZE, true);
        int[] px = new int[SIZE * SIZE];
        bitmap.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        float[] out = new float[3 * SIZE * SIZE];
        int plane = SIZE * SIZE;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            // ViT processor: x/255 then normalize mean=.5/std=.5 -> 2*x-1
            out[i] = 2f * (((p >> 16) & 0xff) / 255f) - 1f;
            out[plane + i] = 2f * (((p >> 8) & 0xff) / 255f) - 1f;
            out[2 * plane + i] = 2f * ((p & 0xff) / 255f) - 1f;
        }
        return out;
    }

    private float[] softmax(float[] logits) {
        float max = -Float.MAX_VALUE;
        for (float v : logits) if (v > max) max = v;
        double sum = 0;
        float[] out = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = (float)Math.exp(logits[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < out.length; i++) out[i] /= (float)sum;
        return out;
    }

    private byte[] readAll(String asset) throws Exception {
        try (InputStream in = context.getAssets().open(asset);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private List<String> readLabels(String asset) throws Exception {
        List<String> labels = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(context.getAssets().open(asset)))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) labels.add(line);
            }
        }
        return labels;
    }

    private static final class Parsed {
        final String common;
        final String scientific;
        Parsed(String common, String scientific) { this.common = common; this.scientific = scientific; }
    }

    private Parsed parse(String raw) {
        int a = raw.lastIndexOf('(');
        int b = raw.endsWith(")") ? raw.length() - 1 : -1;
        if (a > 0 && b > a) {
            String common = raw.substring(0, a).trim();
            String scientific = raw.substring(a + 1, b).trim()
                    .replace(" spp.", " sp.")
                    .replace(" spp", " sp.");
            return new Parsed(common, scientific);
        }
        return new Parsed(raw, raw);
    }
}
