package dev.langchain4j.rag.corrective.prompt;

import dev.langchain4j.model.input.structured.StructuredPrompt;

@StructuredPrompt({
        "You a question re-writer that converts an input question to a better version that is optimized \n",
        "for web search. Look at the input and try to reason about the underlying semantic intent / meaning. \n",

        "Here is the initial question: \n\n {{question}}. \n\n",
        "Improved question with no preamble: \n "
})
public class RewriteQuery {

    private String question;

    public RewriteQuery(String question) {
        this.question = question;
    }
}
