package dev.langchain4j.rag.corrective.internal;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.bge.small.en.v15.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.content.retriever.WebSearchContentRetriever;
import dev.langchain4j.rag.corrective.CorrectiveRag;
import dev.langchain4j.rag.corrective.workflow.CorrectiveNodeFunctions;
import dev.langchain4j.rag.corrective.workflow.CorrectiveStatefulBean;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.workflow.DefaultStateWorkflow;
import dev.langchain4j.workflow.WorkflowStateName;
import dev.langchain4j.workflow.node.Conditional;
import dev.langchain4j.workflow.node.Node;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

public class DefaultCorrectiveRag implements CorrectiveRag {

    private static final Logger log = LoggerFactory.getLogger(DefaultCorrectiveRag.class);

    private final EmbeddingStoreContentRetriever embeddingStoreContentRetriever;
    private final WebSearchContentRetriever webSearchContentRetriever;
    private final ChatLanguageModel chatLanguageModel;
    private final Boolean stream;
    private final Boolean generateWorkflowImage;
    private final Path workflowImageOutputPath;
    

    @Builder
    public DefaultCorrectiveRag(EmbeddingStoreContentRetriever embeddingStoreContentRetriever,
                                WebSearchContentRetriever webSearchContentRetriever,
                                ChatLanguageModel chatLanguageModel,
                                List<Document> documents,
                                Boolean stream,
                                Boolean generateWorkflowImage,
                                Path workflowImageOutputPath
                                ) {
        if (documents.isEmpty() && embeddingStoreContentRetriever == null) {
            throw new IllegalArgumentException("documents or embeddingStoreContentRetriever must be provided");
        }
        this.embeddingStoreContentRetriever = ensureNotNull(
                getOrDefault(embeddingStoreContentRetriever, DefaultCorrectiveRag.defaultContentRetriever(documents)),
                "embeddingStoreContentRetriever"
        );
        this.webSearchContentRetriever = ensureNotNull(webSearchContentRetriever, "webSearchContentRetriever");
        this.chatLanguageModel = ensureNotNull(chatLanguageModel, "chatLanguageModel");
        this.stream = getOrDefault(stream, false);

        // Check if workflowOutputPath is valid
        if (workflowImageOutputPath != null) {
            this.workflowImageOutputPath = workflowImageOutputPath;
            this.generateWorkflowImage = true;
        } else {
            this.workflowImageOutputPath = null;
            this.generateWorkflowImage = getOrDefault(generateWorkflowImage, false);
        }

    }

    @Override
    public AiMessage answer(UserMessage question) {
        // Define a stateful bean
        CorrectiveStatefulBean statefulBean = new CorrectiveStatefulBean();
        statefulBean.setQuestion(question.singleText()); // What are the types of agent memory?

        // Build corrective workflow
        DefaultStateWorkflow<CorrectiveStatefulBean> wf = correctiveWorkflow(statefulBean);

        // Run workflow in stream mode or not
        if (stream) {
            log.info("Running workflow in stream mode...");
            wf.runStream(node -> {
                log.debug("Processing node: " + node.getName());
            });
        } else {
            log.info("Running workflow in normal mode...");
            wf.run();
        }

        // Print transitions
        log.debug("Transitions: \n" + wf.prettyTransitions() + "\n");

        // Print generate final answer
        String finalAnswer = statefulBean.getGeneration();
        log.info("Final Answer: \n" + finalAnswer);

        // Generate workflow image
        if (generateWorkflowImage) {
            try {
                generateWorkflowImage(wf);
            } catch (Exception e) {
                log.warn("Error generating workflow image", e);
            }
        }
        return AiMessage.from(finalAnswer);
    }

    private DefaultStateWorkflow<CorrectiveStatefulBean> correctiveWorkflow(CorrectiveStatefulBean statefulBean) {
        // Create wrapper functions for nodes
        CorrectiveNodeFunctions cwf = new CorrectiveNodeFunctions.Builder()
                .withEmbeddingStoreContentRetriever(embeddingStoreContentRetriever)
                .withChatLanguageModel(chatLanguageModel)
                .withWebSearchContentRetriever(webSearchContentRetriever)
                .build();
        // Define functions for nodes
        Function<CorrectiveStatefulBean, CorrectiveStatefulBean> retrieve = state -> cwf.retrieve(statefulBean);
        Function<CorrectiveStatefulBean, CorrectiveStatefulBean> generate = state -> cwf.generate(statefulBean);
        Function<CorrectiveStatefulBean, CorrectiveStatefulBean> gradeDocuments = state -> cwf.gradeDocuments(statefulBean);
        Function<CorrectiveStatefulBean, CorrectiveStatefulBean> rewriteQuery = state -> cwf.transformQuery(statefulBean);
        Function<CorrectiveStatefulBean, CorrectiveStatefulBean> webSearch = state -> cwf.webSearch(statefulBean);
        // Create nodes
        Node<CorrectiveStatefulBean, CorrectiveStatefulBean> retrieveNode = Node.from("Retrieve Node", retrieve);
        Node<CorrectiveStatefulBean, CorrectiveStatefulBean> generateNode = Node.from("Generate Node", generate);
        Node<CorrectiveStatefulBean, CorrectiveStatefulBean> gradeDocumentsNode = Node.from("Grade Node", gradeDocuments);
        Node<CorrectiveStatefulBean, CorrectiveStatefulBean> rewriteQueryNode = Node.from("Re-Write Query Node", rewriteQuery);
        Node<CorrectiveStatefulBean, CorrectiveStatefulBean> webSearchNode = Node.from("WebSearch Node", webSearch);
        // Build workflow as a graph
        DefaultStateWorkflow<CorrectiveStatefulBean> wf = DefaultStateWorkflow.<CorrectiveStatefulBean>builder()
                .statefulBean(statefulBean)
                .addNodes(Arrays.asList(retrieveNode, generateNode, gradeDocumentsNode, rewriteQueryNode, webSearchNode))
                .build();
        //  Define edges between nodes
        wf.putEdge(retrieveNode, gradeDocumentsNode); // retrieveNode -> gradeDocumentsNode
        wf.putEdge(gradeDocumentsNode, Conditional.eval(obj -> { // gradeDocumentsNode -> rewriteQueryNode OR generateNode
            if (obj.getWebSearch().equals("Yes")) {
                log.info("---DECISION: ALL DOCUMENTS ARE NOT RELEVANT TO QUESTION, TRANSFORM QUERY---");
                return rewriteQueryNode;
            }else {
                log.info("---DECISION: GENERATE---");
                return generateNode;
            }
        }));
        wf.putEdge(rewriteQueryNode, webSearchNode);
        wf.putEdge(webSearchNode, generateNode);
        wf.putEdge(generateNode, WorkflowStateName.END);
        // Define node entrypoint
        wf.startNode(retrieveNode); // TODO - startNode method not added to interface
        return wf;
    }

    private static EmbeddingStoreContentRetriever defaultContentRetriever(List<Document> documents) {
        EmbeddingModel embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();
        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
        log.info("Using defaultContentRetriever, embeddingModel:{} embeddingStore:{}", embeddingModel.getClass().getName(), embeddingStore.getClass().getName());
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(250, 0))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        ingestor.ingest(documents);
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .minScore(0.6)
                .build();
    }

    private void generateWorkflowImage(DefaultStateWorkflow<CorrectiveStatefulBean> wf) throws IOException {
        if (workflowImageOutputPath != null) {
            wf.generateWorkflowImage(workflowImageOutputPath.toAbsolutePath().toString());
        } else {
            wf.generateWorkflowImage();
        }
    }

}
