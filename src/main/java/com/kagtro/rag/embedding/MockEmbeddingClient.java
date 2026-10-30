package com.kagtro.rag.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地开发用：没有 API Key 时生成确定性的伪向量。
 * 仅用于跑通链路，不用于生产检索。
 */
public class MockEmbeddingClient implements EmbeddingClient {

    private final int dimension;

    public MockEmbeddingClient(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(embedOne(text));
        }
        return result;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private float[] embedOne(String text) {
        float[] vec = new float[dimension];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] seed = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            long state = 0;
            for (int i = 0; i < 8; i++) {
                state = (state << 8) | (seed[i] & 0xff);
            }
            for (int i = 0; i < dimension; i++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                vec[i] = ((state >>> 11) & 0xffff) / 32768f - 1f;
            }
        } catch (Exception e) {
            for (int i = 0; i < dimension; i++) {
                vec[i] = (float) Math.sin(text == null ? i : text.hashCode() + i);
            }
        }
        normalize(vec);
        return vec;
    }

    private void normalize(float[] vec) {
        double sum = 0;
        for (float v : vec) {
            sum += v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) (vec[i] / norm);
            }
        }
    }
}
