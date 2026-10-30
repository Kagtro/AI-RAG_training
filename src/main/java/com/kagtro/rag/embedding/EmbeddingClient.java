package com.kagtro.rag.embedding;

import java.util.List;

public interface EmbeddingClient {

    List<float[]> embedAll(List<String> texts);

    int dimension();
}
