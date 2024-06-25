package dev.langchain4j.rag.corrective;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.UrlDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.transformer.HtmlTextExtractor;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModelName;
import dev.langchain4j.rag.content.retriever.WebSearchContentRetriever;
import dev.langchain4j.rag.corrective.internal.DefaultCorrectiveRag;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


class CorrectiveRagIT {
    // README before running this test:

    // Corrective-RAG (CRAG) is a strategy for RAG that incorporates self-reflection / self-grading on retrieved documents.
    // In the paper here (https://arxiv.org/pdf/2401.15884.pdf), a few steps are taken:
    // 1. If at least one document exceeds the threshold for relevance, then it proceeds to generation
    // 2. Before generation, it performs knowledge refinement
    // 3. This partitions the document into "knowledge strips"
    // 4. It grades each strip, and filters our irrelevant ones
    // 5. If all documents fall below the relevance threshold or if the grader is unsure, then the framework seeks an additional datasource
    // 6. It will use web search to supplement retrieval

    // CorrectiveRagIT is an integration test class that demonstrates how to use the CorrectiveRag API.
    // It uses the DefaultCorrectiveRag implementation and `langchain4j-workflow` library.
    // The CorrectiveRag API provides a way to answer questions using a combination of the following components:
    // 1. A list of documents to search for answers.
    // 2. A web search content retriever to search the web for additional answers.
    // 3. A chatLanguageModel to generate answers.
    // 4. Optional: An embeddingStoreContentRetriever to search for answers using embeddings. By default, it uses in-memory store and embeddingModel.
    //              It's highly recommended to use external embedding model and store for better performance or production environments.
    // 5. Optional: A stream flag to enable streaming the workflow node by node.
    // 6. Optional: A generateWorkflowImage flag to generate a workflow image using Graphviz default settings.
    // 7. Optional: A workflowImageOutputPath to save the workflow image to the given path.

    // `langchain4j-corrective-rag` is a library that provides an easy way to implement CRAG in 4 simple steps:

    // 1- Index document content
    List<Document> documents = loadDocuments(
            "https://lilianweng.github.io/posts/2023-06-23-agent/",
            "https://lilianweng.github.io/posts/2023-03-15-prompt-engineering/",
            "https://lilianweng.github.io/posts/2023-10-25-adv-attack-llm/"
    );

    // 2 - Define a chatLanguageModel
    ChatLanguageModel llm = MistralAiChatModel.builder()
            .apiKey(System.getenv("MISTRAL_AI_API_KEY"))
            .modelName(MistralAiChatModelName.MISTRAL_LARGE_LATEST)
            .temperature(0.0)
            .build();

    // 3 - Define a webContentRetriever
    WebSearchContentRetriever webRetriever = WebSearchContentRetriever.builder()
            .webSearchEngine(TavilyWebSearchEngine.builder().apiKey(System.getenv("TAVILY_API_KEY")).build())
            .maxResults(3)
            .build();

    // 4 - Create a CorrectiveRag instance
    CorrectiveRag correctiveRag = DefaultCorrectiveRag.builder()
            .documents(documents) // If you want to use embeddingStoreContentRetriever with documents already ingested, this is not needed
            .webSearchContentRetriever(webRetriever)
            .chatLanguageModel(llm)
            //.embeddingStoreContentRetriever(contentRetriever) // Optional, by default it uses InMemoryEmbeddingStore and BgeSmallEnV15QuantizedEmbeddingModel
            //.stream(true) // Optional, by default it is false, if true it will stream the workflow node by node
            //.generateWorkflowImage(true) // Optional, by default it is false, if true it will generate a workflow image using Graphviz default settings
            //.workflowImageOutputPath(Paths.get("corrective-rag-workflow.png")) // Optional, by default it is null. If it is set, it will save the workflow image to the given path and generateWorkflowImage is set to true
            .build();

    @Test
    void run_using_default_embeddingContentRetriever() {
        // given
        String question = "How does the AlphaCodium paper work?";

        // when
        String answer = correctiveRag.answer(question);

        // then
        assertThat(answer).containsIgnoringWhitespaces("code generation");
    }

    @Test
    void run_in_stream_mode_using_default_embeddingContentRetriever() {
        // given
        CorrectiveRag correctiveRagStream = DefaultCorrectiveRag.builder()
                .documents(documents)
                .webSearchContentRetriever(webRetriever)
                .chatLanguageModel(llm)
                .stream(true)
                .build();

        String question = "How does the AlphaCodium paper work?";

        // when
        String answer = correctiveRagStream.answer(question);

        // then
        assertThat(answer).containsIgnoringWhitespaces("code generation");
    }

    @Test
    void run_using_default_embeddingContentRetriever_and_generate_workflow_image_with_outputPath() {
        // given
        Path workflowImageOutputPath = Paths.get("images/corrective-wf-2.svg");
        CorrectiveRag correctiveRagWithWorkflowImage = DefaultCorrectiveRag.builder()
                .documents(documents)
                .webSearchContentRetriever(webRetriever)
                .chatLanguageModel(llm)
                .workflowImageOutputPath(workflowImageOutputPath)
                .build();

        String question = "How does the AlphaCodium paper work?";

        // when
        String answer = correctiveRagWithWorkflowImage.answer(question);

        // then
        assertThat(answer).containsIgnoringWhitespaces("code generation");
        assertThat(workflowImageOutputPath.toFile()).exists();

    }

    private static List<Document> loadDocuments(String... uris) {
        List<Document> documents = new ArrayList<>();
        for (String uri : uris) {
            Document document = UrlDocumentLoader.load(uri,new TextDocumentParser());
            HtmlTextExtractor transformer = new HtmlTextExtractor(null, null, false);
            document = transformer.transform(document);
            documents.add(document);
        }
        return documents;
    }
}
