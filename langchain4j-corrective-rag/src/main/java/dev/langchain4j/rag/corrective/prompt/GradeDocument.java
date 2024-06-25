package dev.langchain4j.rag.corrective.prompt;

import dev.langchain4j.model.input.structured.StructuredPrompt;

@StructuredPrompt({
        "You are a grader assessing relevance of a retrieved document to a user question.\n",

        "Here is the retrieved document: \n",

        "{{document}} \n",

        "Here is the user question: \n",

        "{{question}} \n",


        "If the document contains keywords related to the user question, grade it as relevant.",
        "It does not need to be a stringent test. The goal is to filter out erroneous retrievals.",
        "Give a binary score 'yes' or 'no' score to indicate whether the document is relevant to the question. \n",

        "Provide the binary score as a JSON with a single key 'score' and no premable or explanation."
})
public class GradeDocument {

    private String document;
    private String question;

    public GradeDocument(String document, String question) {
        this.document = document;
        this.question = question;
    }

}
