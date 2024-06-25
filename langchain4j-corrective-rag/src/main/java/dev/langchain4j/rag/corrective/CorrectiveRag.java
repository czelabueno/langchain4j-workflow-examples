package dev.langchain4j.rag.corrective;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

public interface CorrectiveRag {

    default String answer(String question){
        ensureNotNull(question, "question");
        return answer(new UserMessage(question)).text();
    }

    AiMessage answer(UserMessage question);
}
