package dev.langchain4j.rag.corrective.workflow;

import lombok.Data;

import java.util.List;

@Data
public class CorrectiveStatefulBean {

    private String question;
    private String generation;
    private String webSearch;
    private List<String> documents;

    public CorrectiveStatefulBean() {
    }

    @Override
    public String toString() {
        return "CorrectiveStatefulBean{" +
                "question='" + question + '\'' +
                ", generation='" + generation + '\'' +
                ", webSearch='" + webSearch + '\'' +
                ", documents=" + documents +
                '}';
    }
}
