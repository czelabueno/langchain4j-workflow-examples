package dev.langchain4j.rag.corrective.workflow;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.structured.StructuredPromptProcessor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.content.retriever.WebSearchContentRetriever;
import dev.langchain4j.rag.corrective.prompt.GenerateAnswer;
import dev.langchain4j.rag.corrective.prompt.GradeDocument;
import dev.langchain4j.rag.corrective.prompt.RewriteQuery;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.toList;

public class CorrectiveNodeFunctions {

    private static final Logger log = LoggerFactory.getLogger(CorrectiveNodeFunctions.class);

    private final EmbeddingStoreContentRetriever embeddingStoreContentRetriever;
    private final WebSearchContentRetriever webSearchContentRetriever;
    private final ChatLanguageModel chatLanguageModel;

    private CorrectiveNodeFunctions(Builder builder) {
        this.embeddingStoreContentRetriever = builder.embeddingStoreContentRetriever;
        this.webSearchContentRetriever = builder.webSearchContentRetriever;
        this.chatLanguageModel = builder.chatLanguageModel;
    }

    public static class Builder {
        private EmbeddingStoreContentRetriever embeddingStoreContentRetriever;
        private WebSearchContentRetriever webSearchContentRetriever;
        private ChatLanguageModel chatLanguageModel;

        public Builder withEmbeddingStoreContentRetriever(EmbeddingStoreContentRetriever embeddingStoreContentRetriever) {
            this.embeddingStoreContentRetriever = embeddingStoreContentRetriever;
            return this;
        }

        public Builder withWebSearchContentRetriever(WebSearchContentRetriever webSearchContentRetriever) {
            this.webSearchContentRetriever = webSearchContentRetriever;
            return this;
        }

        public Builder withChatLanguageModel(ChatLanguageModel chatLanguageModel) {
            this.chatLanguageModel = chatLanguageModel;
            return this;
        }

        public CorrectiveNodeFunctions build() {
            return new CorrectiveNodeFunctions(this);
        }
    }


    public CorrectiveStatefulBean retrieve(CorrectiveStatefulBean state) {
        log.info("---RETRIEVE---");
        log.debug("--- Input: " + state.toString());
        String question = state.getQuestion();

        // Retrieval
        List<Content> relevantDocuments = embeddingStoreContentRetriever.retrieve(Query.from(question));
        state.setDocuments(relevantDocuments.stream().map(Content::textSegment).map(TextSegment::text).collect(toList()));
        state.setQuestion(question);
        log.debug("--- Output: " + state.toString());
        return state;
    }

    public CorrectiveStatefulBean generate(CorrectiveStatefulBean state) {
        log.info("---GENERATE---");
        log.debug("--- Input: " + state.toString());
        String question = state.getQuestion();
        String context = String.join("\n\n", state.getDocuments());

        // RAG generation
        GenerateAnswer generateAnswer = new GenerateAnswer(question, context);
        Prompt prompt = StructuredPromptProcessor.toPrompt(generateAnswer);
        String generation = chatLanguageModel.generate(prompt.text());
        state.setGeneration(generation);
        log.debug("--- Output: " + state.toString());
        return state;
    }

    public CorrectiveStatefulBean gradeDocuments(CorrectiveStatefulBean state) {
        log.info("---CHECK DOCUMENT RELEVANCE TO QUESTION---");
        log.debug("--- Input: " + state.toString());
        String question = state.getQuestion();
        List<String> documents = state.getDocuments();

        // Score each document
        List<String> filteredDocs = new ArrayList<>();
        String webSearch = "No";
        for (String doc: documents) {
            GradeDocument gradeDocument = new GradeDocument(doc, question);
            Prompt prompt = StructuredPromptProcessor.toPrompt(gradeDocument);
            String score = chatLanguageModel.generate(prompt.text()); // {'score': 'yes'}
            if (score.contains("yes")) {
                log.info("---GRADE: DOCUMENT RELEVANT---");
                filteredDocs.add(doc);
            } else {
                log.info("---GRADE: DOCUMENT NOT RELEVANT---");
                webSearch = "Yes";
            }
        }
        state.setDocuments(filteredDocs);
        state.setQuestion(question);
        state.setWebSearch(webSearch);
        log.debug("--- Output: " + state.toString());
        return state;
    }


    public CorrectiveStatefulBean transformQuery(CorrectiveStatefulBean state){
        log.info("---TRANSFORM QUERY---");
        log.debug("--- Input: " + state.toString());
        String question = state.getQuestion();
        List<String> documents = state.getDocuments();

        // Re-write question
        RewriteQuery rewriteQuery = new RewriteQuery(question);
        Prompt prompt = StructuredPromptProcessor.toPrompt(rewriteQuery);
        String betterQuestion = chatLanguageModel.generate(prompt.text());
        state.setQuestion(betterQuestion);
        state.setDocuments(documents);
        log.debug("--- Output: " + state.toString());
        return state;
    }

    public CorrectiveStatefulBean webSearch(CorrectiveStatefulBean state){
        log.info("---WEB SEARCH---");
        log.debug("--- Input: " + state.toString());
        String question = state.getQuestion();
        List<String> documents = state.getDocuments();

        // Web search
        List<Content> webSearchResults = webSearchContentRetriever.retrieve(Query.from(question));
        documents.addAll(webSearchResults.stream().map(Content::textSegment).map(TextSegment::text).collect(toList()));
        state.setDocuments(documents);
        state.setQuestion(question);
        log.debug("--- Output: " + state.toString());
        return state;
    }
}
