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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class FloraExpert {
    private static final int SIZE = 224;
    private static final int EMBED = 1024;
    private final Context context;

    static final class FloraCandidate {
        final String scientific;
        final float similarity;
        final boolean genusLevel;

        FloraCandidate(String scientific, float similarity, boolean genusLevel) {
            this.scientific = scientific;
            this.similarity = similarity;
            this.genusLevel = genusLevel;
        }
    }

    FloraExpert(Context context) {
        this.context = context.getApplicationContext();
    }

    List<FloraCandidate> classify(Bitmap source, int topK) throws Exception {
        byte[] modelBytes = readAll("ai/flora_student_fp16.onnx");
        float[] table = readFloatTable("ai/flora_taxa_table_f32.bin");
        List<String> labels = readLabels("ai/flora_taxa_labels.txt");
        if (labels.isEmpty() || table.length != labels.size() * EMBED) {
            throw new IllegalStateException("Table botanique incohérente");
        }

        float[] image = prepareImage(source);
        float[] embedding;

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));

        try (OrtSession session = env.createSession(modelBytes, options);
             OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(image),
                     new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(Map.of("image", input))) {
            Object v = result.get(0).getValue();
            if (!(v instanceof float[][])) throw new IllegalStateException("Sortie BioCLIP inattendue");
            embedding = ((float[][]) v)[0];
        }

        List<FloraCandidate> scored = new ArrayList<>(labels.size());
        for (int row = 0; row < labels.size(); row++) {
            float dot = 0f;
            int off = row * EMBED;
            for (int j = 0; j < EMBED; j++) dot += table[off + j] * embedding[j];
            scored.add(new FloraCandidate(labels.get(row), dot, false));
        }
        scored.sort((a,b) -> Float.compare(b.similarity, a.similarity));

        int n = Math.min(topK, scored.size());
        List<FloraCandidate> result = new ArrayList<>();
        if (n > 1) {
            String g0 = genus(scored.get(0).scientific);
            int sameGenus = 0;
            float bestOtherGenus = -2f;
            for (int i = 0; i < Math.min(5, scored.size()); i++) {
                FloraCandidate c = scored.get(i);
                if (genus(c.scientific).equals(g0)) sameGenus++;
                else bestOtherGenus = Math.max(bestOtherGenus, c.similarity);
            }
            float speciesGap = scored.get(0).similarity - scored.get(1).similarity;
            if (sameGenus >= 2 && speciesGap < 0.045f &&
                    scored.get(0).similarity - bestOtherGenus > 0.03f) {
                result.add(new FloraCandidate(g0 + " sp.", scored.get(0).similarity, true));
            }
        }
        for (int i = 0; i < n; i++) result.add(scored.get(i));
        return result;
    }

    private static String genus(String scientific) {
        int p = scientific.indexOf(' ');
        return p > 0 ? scientific.substring(0, p) : scientific;
    }

    private float[] prepareImage(Bitmap source) {
        Bitmap bitmap = Bitmap.createScaledBitmap(source, SIZE, SIZE, true);
        int[] px = new int[SIZE * SIZE];
        bitmap.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        float[] out = new float[3 * SIZE * SIZE];
        int plane = SIZE * SIZE;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            out[i] = ((p >> 16) & 0xff) / 255f;
            out[plane + i] = ((p >> 8) & 0xff) / 255f;
            out[2 * plane + i] = (p & 0xff) / 255f;
        }
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

    private float[] readFloatTable(String asset) throws Exception {
        byte[] bytes = readAll(asset);
        if ((bytes.length & 3) != 0) throw new IllegalStateException("Table botanique invalide");
        FloatBuffer fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] values = new float[fb.remaining()];
        fb.get(values);
        return values;
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
}
