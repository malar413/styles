package ca.larose.fieldcompanion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

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
    private static final float EARLY_ACCEPT = 0.35f;
    private static final float EARLY_MARGIN = 0.12f;

    private final Context context;

    static final class HouseCandidate {
        final String common;
        final String scientific;
        final float matchIndex;
        final int viewsUsed;

        HouseCandidate(String common, String scientific, float matchIndex, int viewsUsed) {
            this.common = common;
            this.scientific = scientific;
            this.matchIndex = matchIndex;
            this.viewsUsed = viewsUsed;
        }
    }

    HousePlantExpert(Context context) {
        this.context = context.getApplicationContext();
    }

    List<HouseCandidate> classify(Bitmap source, int topK) throws Exception {
        byte[] model = readAll("ai/houseplant_q4.onnx");
        List<String> labels = readLabels("ai/houseplant_labels.txt");

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));

        try (OrtSession session = env.createSession(model, options)) {
            String inputName = session.getInputNames().iterator().next();

            // 1) Vue complète, sans déformation : l'image est letterboxée dans un carré neutre.
            float[] first = infer(session, env, inputName, letterbox(source));
            if (first.length != labels.size()) throw new IllegalStateException("Étiquettes horticoles incohérentes");

            int[] firstTop = top2(first);
            float top = first[firstTop[0]];
            float second = first[firstTop[1]];

            List<float[]> views = new ArrayList<>();
            views.add(first);

            // 2) Si la vue complète est ambiguë, on ajoute des recadrages carrés qui
            //    conservent les proportions du sujet. On évite ainsi l'ancien 224x224
            //    qui étirait toute la photo.
            if (top < EARLY_ACCEPT || (top - second) < EARLY_MARGIN) {
                for (Bitmap crop : squareCrops(source)) {
                    views.add(infer(session, env, inputName, crop));
                }
            }

            // Indice multi-vues = 65 % meilleur cadrage + 35 % moyenne des cadrages.
            // Ce n'est PAS une probabilité calibrée; c'est uniquement un indice de classement.
            float[] aggregate = new float[labels.size()];
            for (int cls = 0; cls < labels.size(); cls++) {
                float max = 0f;
                float sum = 0f;
                for (float[] v : views) {
                    float p = v[cls];
                    if (p > max) max = p;
                    sum += p;
                }
                float mean = sum / views.size();
                aggregate[cls] = 0.65f * max + 0.35f * mean;
            }

            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < aggregate.length; i++) idx.add(i);
            idx.sort((a, b) -> Float.compare(aggregate[b], aggregate[a]));

            List<HouseCandidate> out = new ArrayList<>();
            for (int rank = 0; rank < Math.min(topK, idx.size()); rank++) {
                int i = idx.get(rank);
                Parsed p = parse(labels.get(i));
                out.add(new HouseCandidate(p.common, p.scientific, aggregate[i], views.size()));
            }
            return out;
        }
    }

    private float[] infer(OrtSession session, OrtEnvironment env, String inputName, Bitmap view) throws Exception {
        float[] inputData = prepareImage(view);
        try (OnnxTensor input = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(inputData),
                new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(Map.of(inputName, input))) {
            Object value = result.get(0).getValue();
            if (!(value instanceof float[][])) {
                throw new IllegalStateException("Sortie expert horticole inattendue");
            }
            return softmax(((float[][]) value)[0]);
        }
    }

    private int[] top2(float[] values) {
        int first = 0;
        int second = values.length > 1 ? 1 : 0;
        if (values[second] > values[first]) {
            int t = first; first = second; second = t;
        }
        for (int i = 2; i < values.length; i++) {
            if (values[i] > values[first]) {
                second = first;
                first = i;
            } else if (values[i] > values[second]) {
                second = i;
            }
        }
        return new int[]{first, second};
    }

    private Bitmap letterbox(Bitmap source) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.rgb(127, 127, 127));

        int sw = source.getWidth();
        int sh = source.getHeight();
        float scale = Math.min(SIZE / (float) sw, SIZE / (float) sh);
        int dw = Math.max(1, Math.round(sw * scale));
        int dh = Math.max(1, Math.round(sh * scale));
        int left = (SIZE - dw) / 2;
        int top = (SIZE - dh) / 2;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, new Rect(left, top, left + dw, top + dh), paint);
        return out;
    }

    private List<Bitmap> squareCrops(Bitmap source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int side = Math.min(w, h);
        List<Bitmap> crops = new ArrayList<>();

        if (w > h) {
            int maxX = w - side;
            crops.add(cropAndScale(source, 0, 0, side));
            crops.add(cropAndScale(source, maxX / 2, 0, side));
            crops.add(cropAndScale(source, maxX, 0, side));
        } else if (h > w) {
            int maxY = h - side;
            crops.add(cropAndScale(source, 0, 0, side));
            crops.add(cropAndScale(source, 0, maxY / 2, side));
            crops.add(cropAndScale(source, 0, maxY, side));
        } else {
            crops.add(Bitmap.createScaledBitmap(source, SIZE, SIZE, true));
            int zoom = Math.max(32, Math.round(side * 0.76f));
            crops.add(cropAndScale(source, (w - zoom) / 2, (h - zoom) / 2, zoom));
        }
        return crops;
    }

    private Bitmap cropAndScale(Bitmap source, int x, int y, int side) {
        int safeX = Math.max(0, Math.min(x, source.getWidth() - side));
        int safeY = Math.max(0, Math.min(y, source.getHeight() - side));
        Bitmap crop = Bitmap.createBitmap(source, safeX, safeY, side, side);
        return Bitmap.createScaledBitmap(crop, SIZE, SIZE, true);
    }

    private float[] prepareImage(Bitmap bitmap) {
        Bitmap scaled = bitmap.getWidth() == SIZE && bitmap.getHeight() == SIZE
                ? bitmap
                : Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true);

        int[] px = new int[SIZE * SIZE];
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        float[] out = new float[3 * SIZE * SIZE];
        int plane = SIZE * SIZE;

        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            // ViT processor: x/255 puis mean=.5/std=.5 -> 2*x-1.
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
            out[i] = (float) Math.exp(logits[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < out.length; i++) out[i] /= (float) sum;
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

        Parsed(String common, String scientific) {
            this.common = common;
            this.scientific = scientific;
        }
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
